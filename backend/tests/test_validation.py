"""Request validation, credential revocation and immutable configuration tests."""

import copy
import hashlib
import json
from pathlib import Path

import pytest
from pydantic import TypeAdapter, ValidationError

from backend.core.auth import AuthRegistry, Principal
from backend.core.configuration import ConfigRegistry, config_signature
from backend.core.errors import ApiError
from backend.core.models import Config, Id, Query, RecordingJobInput, RecordingManifest, ReviewCreateRequest, UtcQuery


EXAMPLES = Path(__file__).resolve().parents[2] / "docs/api/examples/v0.3"


@pytest.mark.parametrize("native_id", ["native/event@123|x", " opaque/event@123|x ", "原生事件/设备@甲|1", "x" * 200])
def test_sdk_event_id_is_opaque_and_preserved(native_id):
    value = RecordingJobInput(job_id="job_1", camera_id="camera_1", config_version="test_v1",
                              sdk_event_id=native_id, duration_ms=1000)
    assert value.sdk_event_id == native_id


@pytest.mark.parametrize("native_id", [None, 123, True, "", " ", "\u3000\u00a0", "x" * 201,
                                        "native\x00id", "native\nid", "native\tid", "native\x7fid", "native\x85id"])
def test_sdk_event_id_rejects_invalid_values(native_id):
    with pytest.raises(ValidationError):
        RecordingJobInput(job_id="job_1", camera_id="camera_1", config_version="test_v1",
                          sdk_event_id=native_id, duration_ms=1000)


@pytest.fixture
def manifest():
    return json.loads((EXAMPLES / "recording-import-mock-request.json").read_text())


@pytest.fixture
def configuration():
    return {
        "config_version": "test_v1",
        "cameras": [{
            "camera_id": "camera_1", "name": "Test camera",
            "zones": [{"zone_id": "E3", "name": "Shelf", "roi": [[0, 0], [1, 0], [1, 1], [0, 1]]}],
        }],
    }


@pytest.fixture
def query():
    return {
        "recording_id": "recording_1", "camera_id": "camera_1", "zone_id": "E3",
        "start_offset_ms": 0, "end_offset_ms": 10,
    }


def test_existing_mock_contract_examples_are_valid(manifest):
    assert RecordingManifest.model_validate(manifest).recording.source_mode == "mock"
    review = json.loads((EXAMPLES / "review-create-request.json").read_text())
    assert ReviewCreateRequest.model_validate(review).review_id == review["review_id"]
    config = json.loads((EXAMPLES / "config-response.json").read_text())
    assert Config.model_validate(config["config"]).config_version


@pytest.mark.parametrize("invalid", [True, 1.0, "1", -1, 9223372036854775808])
def test_offsets_reject_boolean_coercions_negative_and_bigint_overflow(query, invalid):
    query["start_offset_ms"] = invalid
    with pytest.raises(ValidationError):
        Query.model_validate(query)


@pytest.mark.parametrize("identifier", ["", "../v1", "a b", "_prefix", "a" * 101, 12])
def test_ids_use_current_contract(identifier):
    with pytest.raises(ValidationError):
        TypeAdapter(Id).validate_python(identifier)


def test_queries_reject_empty_and_mixed_windows(query):
    with pytest.raises(ValidationError):
        Query.model_validate({**query, "end_offset_ms": 0})
    with pytest.raises(ValidationError):
        Query.model_validate({**query, "start": "2026-10-16T01:00:00Z"})
    with pytest.raises(ValidationError):
        Query.model_validate({**query, "reviewer_id": "forged_user"})


@pytest.mark.parametrize("timestamp", [
    "1969-12-31T23:59:59Z", "2026-02-30T01:00:00Z", "2026-10-16T01:00:00.0001Z",
    "2026-10-16T01:00:00+00:00", "2026-10-16 01:00:00Z", "2026-10-16T01:00:00z",
    "2026-10-16T01:00:60Z",
])
def test_utc_requires_valid_utc_calendar_millisecond_timestamp(query, timestamp):
    utc = {key: value for key, value in query.items() if not key.endswith("offset_ms")}
    with pytest.raises(ValidationError):
        UtcQuery.model_validate({**utc, "start": timestamp, "end": "2026-10-17T01:00:00Z"})


def test_utc_window_compares_instants_instead_of_text(query):
    utc = {key: value for key, value in query.items() if not key.endswith("offset_ms")}
    valid = UtcQuery.model_validate({**utc, "start": "1970-01-01T00:00:00Z", "end": "1970-01-01T00:00:00.001Z"})
    assert valid.start.endswith("Z")
    with pytest.raises(ValidationError):
        UtcQuery.model_validate({**utc, "start": "2026-10-16T01:00:00.1Z", "end": "2026-10-16T01:00:00.10Z"})


@pytest.mark.parametrize("bbox", [
    [0, 0, 0, 1], [0.9, 0, 0.2, 1], [0, 0.9, 1, 0.2], [True, 0, 1, 1],
    [float("nan"), 0, 1, 1], [0, 0, 1],
])
def test_bbox_rejects_nonphysical_or_non_numeric_boxes(manifest, bbox):
    manifest["evidence"][0]["bbox_json"] = bbox
    with pytest.raises(ValidationError):
        RecordingManifest.model_validate(manifest)


@pytest.mark.parametrize("mutate", [
    lambda data: data["events"].append(copy.deepcopy(data["events"][0])),
    lambda data: data["evidence"].append(copy.deepcopy(data["evidence"][0])),
    lambda data: data["evidence"][0].update(event_id="unknown"),
    lambda data: data["events"][0].update(end_offset_ms=120001),
    lambda data: data["evidence"][0].update(offset_ms=120001),
    lambda data: data["events"][0].update(start_offset_ms=50000),
    lambda data: data["events"][0].update(detection_source="automatic"),
    lambda data: data["recording"].update(started_at="2026-10-16T01:00:00Z"),
    lambda data: data["evidence"][0].update(file_name="pretend.png"),
])
def test_manifest_rejects_duplicate_or_unowned_objects_and_source_mixing(manifest, mutate):
    mutate(manifest)
    with pytest.raises(ValidationError):
        RecordingManifest.model_validate(manifest)


def test_context_frames_may_precede_and_follow_event_window(manifest):
    manifest["evidence"][0]["offset_ms"] = 1000
    assert RecordingManifest.model_validate(manifest).evidence[0].offset_ms == 1000
    manifest["events"][0]["end_offset_ms"] = manifest["events"][0]["start_offset_ms"]
    assert RecordingManifest.model_validate(manifest).events[0].start_offset_ms == 32000


def test_real_recordings_require_video_and_safe_unique_file_names(manifest):
    manifest["recording"].update(source_mode="prerecorded", file_name="video.mp4")
    manifest["events"][0]["detection_source"] = "manual"
    manifest["evidence"][0]["file_name"] = "frame.png"
    assert RecordingManifest.model_validate(manifest).recording.file_name == "video.mp4"
    for invalid in [None, "../video.mp4", "..", "file\\name.mp4", "file\x00.mp4"]:
        changed = copy.deepcopy(manifest)
        changed["recording"]["file_name"] = invalid
        with pytest.raises(ValidationError):
            RecordingManifest.model_validate(changed)
    manifest["evidence"][0]["file_name"] = "video.mp4"
    with pytest.raises(ValidationError):
        RecordingManifest.model_validate(manifest)


@pytest.mark.parametrize("roi", [
    [[0, 0], [1, 1]], [[0, 0], [0.5, 0.5], [1, 1]],
    [[0, 0], [1, 0], [1, 1], [1, 0]], [[0, 0], [1, 1], [0, 1], [1, 0]],
    [[-0.1, 0], [1, 0], [1, 1]], [[False, 0], [1, 0], [1, 1]],
    [[0, 0, 0], [1, 0], [1, 1]],
])
def test_roi_rejects_degenerate_crossed_and_out_of_range_polygon(configuration, roi):
    configuration["cameras"][0]["zones"][0]["roi"] = roi
    with pytest.raises(ValidationError):
        Config.model_validate(configuration)


def test_config_camera_and_zone_identity_uniqueness(configuration):
    changed = copy.deepcopy(configuration)
    changed["cameras"].append(copy.deepcopy(changed["cameras"][0]))
    with pytest.raises(ValidationError):
        Config.model_validate(changed)
    configuration["cameras"][0]["zones"] *= 2
    with pytest.raises(ValidationError):
        Config.model_validate(configuration)


def credential_file(path, token="private-test-token", **overrides):
    entry = {
        "token_sha256": hashlib.sha256(token.encode()).hexdigest(), "user_id": "user_1",
        "scopes": ["data:read"], "camera_ids": ["camera_1"],
    }
    entry.update(overrides)
    path.write_text(json.dumps({"tokens": [entry]}))
    path.chmod(0o600)
    return path


def test_auth_returns_only_principal_and_reads_token_revocation(tmp_path):
    path = credential_file(tmp_path / "auth.json")
    registry = AuthRegistry(path)
    principal = registry.authenticate("private-test-token")
    assert principal == Principal("user_1", frozenset({"data:read"}), frozenset({"camera_1"}), None)
    assert "private-test-token" not in repr(principal)
    credential_file(path, token="replacement-token", recording_ids=["recording_1"])
    with pytest.raises(ApiError) as failure:
        registry.authenticate("private-test-token")
    assert failure.value.status_code == 401
    assert registry.authenticate("replacement-token").recording_ids == frozenset({"recording_1"})


def test_auth_grant_revocation_and_empty_resource_grants(tmp_path):
    path = credential_file(tmp_path / "auth.json")
    registry = AuthRegistry(path)
    credential_file(path, scopes=[], camera_ids=[], recording_ids=[])
    assert registry.authenticate("private-test-token").scopes == frozenset()
    assert registry.authenticate("private-test-token").recording_ids == frozenset()


@pytest.mark.parametrize("mutation", [
    lambda path: path.chmod(0o644),
    lambda path: path.write_text('{"tokens": [], "tokens": []}'),
    lambda path: credential_file(path, user_id="../forged"),
    lambda path: credential_file(path, token_sha256="not-a-sha256"),
    lambda path: credential_file(path, scopes=["data:read", "data:read"]),
    lambda path: path.unlink(),
])
def test_auth_invalid_configuration_fails_closed_without_credentials(tmp_path, mutation):
    path = credential_file(tmp_path / "auth.json")
    registry = AuthRegistry(path)
    mutation(path)
    with pytest.raises(ApiError) as failure:
        registry.authenticate("private-test-token")
    assert failure.value.status_code == 503
    assert "private-test-token" not in str(failure.value)
    assert "auth.json" not in str(failure.value)


def test_auth_rejects_symlink_and_missing_file(tmp_path):
    real = credential_file(tmp_path / "real.json")
    link = tmp_path / "link.json"
    link.symlink_to(real)
    for path in (link, tmp_path / "missing.json"):
        with pytest.raises(ApiError) as failure:
            AuthRegistry(path)
        assert failure.value.status_code == 503


@pytest.mark.parametrize("token", ["", "not-real", "private-test-token ", "x" * 4097])
def test_auth_rejects_invalid_tokens(tmp_path, token):
    registry = AuthRegistry(credential_file(tmp_path / "auth.json"))
    with pytest.raises(ApiError) as failure:
        registry.authenticate(token)
    assert failure.value.status_code == 401


def test_config_registry_validates_pins_and_retains_isolated_startup_snapshot(tmp_path, configuration):
    path = tmp_path / "test_v1.json"
    path.write_text(json.dumps(configuration))
    snapshot = Config.model_validate(configuration).model_dump(mode="json")
    signature = config_signature(snapshot)
    registry = ConfigRegistry(tmp_path, pins={"test_v1": signature})
    assert registry.signature("test_v1") == signature
    returned = registry.load("test_v1")
    returned["cameras"][0]["name"] = "Modified copy"
    configuration["cameras"][0]["name"] = "Overwritten version"
    path.write_text(json.dumps(configuration))
    assert registry.load("test_v1")["cameras"][0]["name"] == "Test camera"
    with pytest.raises(ApiError) as failure:
        ConfigRegistry(tmp_path, pins={"test_v1": signature})
    assert failure.value.status_code == 503


def test_configuration_unknown_version_and_path_traversal(tmp_path, configuration):
    (tmp_path / "test_v1.json").write_text(json.dumps(configuration))
    registry = ConfigRegistry(tmp_path)
    with pytest.raises(ApiError) as missing:
        registry.load("missing")
    assert missing.value.status_code == 404
    with pytest.raises(ApiError) as invalid:
        registry.load("../test_v1")
    assert invalid.value.status_code == 422


@pytest.mark.parametrize("pins", [{}, {"test_v1": "0" * 64}, {"test_v1": "wrong"}, {"deleted": "0" * 64}])
def test_configuration_pins_fail_on_missing_added_or_changed_versions(tmp_path, configuration, pins):
    (tmp_path / "test_v1.json").write_text(json.dumps(configuration))
    with pytest.raises(ApiError) as failure:
        ConfigRegistry(tmp_path, pins=pins)
    assert failure.value.status_code == 503


def test_configuration_rejects_filename_version_conflict_duplicate_json_and_symlink(tmp_path, configuration):
    path = tmp_path / "other.json"
    path.write_text(json.dumps(configuration))
    with pytest.raises(ApiError):
        ConfigRegistry(tmp_path)
    path.unlink()
    path = tmp_path / "test_v1.json"
    path.write_text('{"config_version":"test_v1","config_version":"test_v1","cameras":[]}')
    with pytest.raises(ApiError):
        ConfigRegistry(tmp_path)
    path.unlink()
    external = tmp_path / "external"
    external.write_text(json.dumps(configuration))
    path.symlink_to(external)
    with pytest.raises(ApiError):
        ConfigRegistry(tmp_path)
