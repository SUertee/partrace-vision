"""Media boundaries, real image decoding and bounded external video validation."""

import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

from backend.core.errors import ApiError
from backend.storage.media import MediaStore


def manifest(*, source="prerecorded", evidence=True):
    return {
        "schema_version": "0.3-mvp-draft",
        "recording": {
            "recording_id": "recording_1", "camera_id": "camera_1",
            "config_version": "v1", "source_mode": source,
            "started_at": None, "duration_ms": 500,
            "file_name": None if source == "mock" else "input.mp4",
        },
        "events": [{"event_id": "event_1", "detection_source": "mock" if source == "mock" else "automatic"}],
        "evidence": [{
            "evidence_id": "evidence_1", "event_id": "event_1", "offset_ms": 200,
            "bbox_json": None, "file_name": None if source == "mock" else "frame.png",
        }] if evidence else [],
    }


def image_bytes(format="PNG"):
    from PIL import Image

    content = io.BytesIO()
    Image.new("RGB", (8, 8), (20, 40, 60)).save(content, format=format)
    return content.getvalue()


def probe_result(*, duration="0.5", frames="5", fps="10/1", format_name="mov,mp4,m4a,3gp,3g2,mj2"):
    return subprocess.CompletedProcess([], 0, json.dumps({
        "streams": [{
            "codec_type": "video", "codec_name": "h264", "width": 128,
            "height": 96, "duration": duration, "avg_frame_rate": fps,
            "nb_frames": frames, "disposition": {"attached_pic": 0},
        }],
        "format": {"format_name": format_name, "duration": duration},
    }), "")


@pytest.fixture
def store(tmp_path):
    return MediaStore(tmp_path / "media")


def assert_error(status, operation):
    with pytest.raises(ApiError) as raised:
        operation()
    assert raised.value.status_code == status
    return raised.value


def bypass_video_decode(monkeypatch, store):
    # Lifecycle tests don't claim to validate a real stream. Decoder behavior has
    # separate tests, including generated actual media when ffmpeg is installed.
    monkeypatch.setattr(store, "_validate_video", lambda *args: None)


def test_mock_has_no_files_or_private_directories(store):
    prepared = store.prepare(manifest(source="mock"), {})
    assert prepared.recording_storage_key is None
    assert prepared.evidence_keys == {"evidence_1": None}
    assert prepared.digests == {}
    prepared.commit()
    prepared.rollback()
    assert list((store.root / ".staging").iterdir()) == []
    assert list((store.root / "objects").iterdir()) == []


@pytest.mark.parametrize("change", ["upload", "video_name", "frame_name", "real_clock", "real_event"])
def test_mock_never_accepts_real_media_or_claims(store, change):
    payload = manifest(source="mock")
    uploads = {}
    if change == "upload":
        uploads["input.mp4"] = b"bytes"
    elif change == "video_name":
        payload["recording"]["file_name"] = "input.mp4"
    elif change == "frame_name":
        payload["evidence"][0]["file_name"] = "frame.png"
    elif change == "real_clock":
        payload["recording"]["started_at"] = "2026-10-16T00:00:00Z"
    else:
        payload["events"][0]["detection_source"] = "automatic"
    assert_error(422, lambda: store.prepare(payload, uploads))


@pytest.mark.parametrize("name", ["../input.mp4", "/tmp/input.mp4", "C:\\input.mp4", "a/b.mp4", "-input.mp4", "a\x00.mp4", ".mp4", "a.mp4 ", "a\n.mp4"])
def test_rejects_unsafe_client_basenames(store, name):
    payload = manifest(evidence=False)
    payload["recording"]["file_name"] = name
    assert_error(422, lambda: store.prepare(payload, {name: b"bytes"}))
    assert list((store.root / ".staging").iterdir()) == []


@pytest.mark.parametrize("names", [("frame.png", "frame.png"), ("frame.png", "FRAME.PNG")])
def test_rejects_duplicate_filename_references_even_on_case_insensitive_filesystems(store, names):
    payload = manifest()
    payload["evidence"][0]["file_name"] = names[0]
    second = copy.deepcopy(payload["evidence"][0])
    second.update(evidence_id="evidence_2", file_name=names[1])
    payload["evidence"].append(second)
    uploads = {"input.mp4": b"video", names[0]: b"image", names[1]: b"image"}
    assert_error(422, lambda: store.prepare(payload, uploads))


@pytest.mark.parametrize("uploads", [{}, {"input.mp4": b"video"}, {"input.mp4": b"video", "frame.png": b"image", "extra.png": b"extra"}])
def test_requires_exact_uploaded_file_set(store, uploads):
    assert_error(422, lambda: store.prepare(manifest(), uploads))


def test_real_sources_require_video_and_reject_still_image_as_recording(store):
    payload = manifest(evidence=False)
    payload["recording"]["file_name"] = None
    assert_error(422, lambda: store.prepare(payload, {}))
    payload["recording"]["file_name"] = "input.png"
    assert_error(422, lambda: store.prepare(payload, {"input.png": b"bytes"}))


def test_upload_limit_is_combined_actual_bytes(tmp_path):
    store = MediaStore(tmp_path / "media", max_bytes=8)
    assert_error(413, lambda: store.prepare(manifest(), {"input.mp4": b"12345", "frame.png": b"6789"}))
    assert list((store.root / ".staging").iterdir()) == []


def test_private_keys_atomic_publish_hashes_and_compensating_cleanup(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    content = image_bytes()
    uploads = {"input.mp4": b"unit-test-decoder-bypassed", "frame.png": content}
    prepared = store.prepare(manifest(), uploads)
    assert prepared.published is False
    assert prepared.digests == {name: hashlib.sha256(body).hexdigest() for name, body in uploads.items()}
    assert "input.mp4" not in prepared.recording_storage_key
    key = prepared.evidence_keys["evidence_1"]
    assert_error(404, lambda: store.read(key))
    prepared.commit()
    assert prepared.published is True
    assert store.read(key) == content
    prepared.commit()  # local repeat doesn't publish another copy
    prepared.rollback()  # compensation when the database commit subsequently fails
    assert prepared.published is False
    prepared.rollback()
    assert_error(404, lambda: store.read(key))
    assert list((store.root / ".staging").iterdir()) == []
    assert list((store.root / "objects").iterdir()) == []
    with pytest.raises(RuntimeError):
        prepared.commit()


def test_rollback_never_removes_another_import(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    payload = manifest(evidence=False)
    first = store.prepare(payload, {"input.mp4": b"first"})
    second = store.prepare(payload, {"input.mp4": b"second"})
    first.commit()
    second.commit()
    first.rollback()
    assert store.read(second.recording_storage_key) == b"second"
    second.rollback()


def test_publish_refuses_existing_destination_and_rollback_preserves_it(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    prepared = store.prepare(manifest(evidence=False), {"input.mp4": b"ours"})
    prepared._final.mkdir()
    marker = prepared._final / "existing"
    marker.write_bytes(b"not ours")
    assert_error(503, prepared.commit)
    prepared.rollback()
    assert marker.read_bytes() == b"not ours"
    assert list((store.root / ".staging").iterdir()) == []


def test_explicit_missing_frame_has_no_storage_key(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    payload = manifest()
    payload["evidence"][0]["file_name"] = None
    prepared = store.prepare(payload, {"input.mp4": b"decoder-bypassed"})
    assert prepared.evidence_keys == {"evidence_1": None}
    prepared.rollback()


@pytest.mark.parametrize("extension,format", [(".png", "PNG"), (".jpg", "JPEG"), (".jpeg", "JPEG")])
def test_images_must_fully_decode_and_match_format(store, extension, format):
    store._validate_image(image_bytes(format), extension)
    assert_error(422, lambda: store._validate_image(b"not an image", extension))
    other = image_bytes("JPEG" if format == "PNG" else "PNG")
    assert_error(422, lambda: store._validate_image(other, extension))


def test_truncated_image_and_pixel_bomb_rejected(store, tmp_path):
    assert_error(422, lambda: store._validate_image(image_bytes()[:30], ".png"))
    tiny_limit = MediaStore(tmp_path / "small", max_pixels=10)
    assert_error(422, lambda: tiny_limit._validate_image(image_bytes(), ".png"))


def test_decoder_failure_cleans_staging(store, monkeypatch):
    def unavailable(*args):
        raise ApiError(503, "media_validation_unavailable", "Unavailable")
    monkeypatch.setattr(store, "_validate_video", unavailable)
    assert_error(503, lambda: store.prepare(manifest(evidence=False), {"input.mp4": b"bytes"}))
    assert list((store.root / ".staging").iterdir()) == []


def test_corrupt_image_rejects_whole_bundle_and_cleans_video(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    assert_error(422, lambda: store.prepare(manifest(), {"input.mp4": b"decoder-bypassed", "frame.png": b"not png"}))
    assert list((store.root / ".staging").iterdir()) == []
    assert list((store.root / "objects").iterdir()) == []


@pytest.mark.parametrize("failure", [FileNotFoundError(), PermissionError(), subprocess.TimeoutExpired("ffprobe", 1)])
def test_missing_tool_and_timeout_are_503_and_do_not_expose_commands(store, monkeypatch, failure):
    def run(*args, **kwargs):
        assert kwargs["timeout"] == 120
        assert kwargs["check"] is True
        raise failure
    monkeypatch.setattr(subprocess, "run", run)
    error = assert_error(503, lambda: store._validate_video(Path("/private/secret.mp4"), 500))
    assert "secret" not in str(error)


@pytest.mark.parametrize("overrides", [{"duration": "3"}, {"duration": "NaN"}, {"frames": "1"}, {"fps": "0/0"}, {"format_name": "image2"}])
def test_probe_metadata_must_describe_continuous_matching_video(store, monkeypatch, overrides):
    monkeypatch.setattr(store, "_run", lambda command: probe_result(**overrides))
    assert_error(422, lambda: store._validate_video(Path("input.mp4"), 500))


def test_probe_metadata_without_successful_full_decode_is_insufficient(store, monkeypatch):
    calls = []
    def run(command):
        calls.append(command)
        if command[0] == store.ffprobe_path:
            return probe_result()
        return subprocess.CompletedProcess(command, 0, "frame=1\nprogress=end\n", "")
    monkeypatch.setattr(store, "_run", run)
    assert_error(422, lambda: store._validate_video(Path("input.mp4"), 500))
    assert len(calls) == 2


def test_one_frame_duration_error_allowed_but_large_error_rejected(store, monkeypatch):
    def run(command):
        if command[0] == store.ffprobe_path:
            return probe_result()
        return subprocess.CompletedProcess(command, 0, "frame=5\nprogress=end\n", "")
    monkeypatch.setattr(store, "_run", run)
    store._validate_video(Path("input.mp4"), 550)
    assert_error(422, lambda: store._validate_video(Path("input.mp4"), 800))


def test_real_ffmpeg_decode_with_generated_video_and_probe_substitute(store, monkeypatch, tmp_path):
    """Actual FFmpeg decode; probe is substituted when host lacks ffprobe."""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        pytest.skip("Actual FFmpeg decoding requires a locally installed ffmpeg")
    source = tmp_path / "source.mp4"
    subprocess.run([
        ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi", "-i",
        "color=c=blue:s=128x96:r=10", "-t", "0.5", "-c:v", "mpeg4", str(source),
    ], check=True, capture_output=True, timeout=30)
    store.ffmpeg_path = ffmpeg
    actual_run = store._run
    def run(command):
        if command[0] == store.ffprobe_path:
            return probe_result()
        return actual_run(command)
    monkeypatch.setattr(store, "_run", run)
    prepared = store.prepare(manifest(), {"input.mp4": source.read_bytes(), "frame.png": image_bytes()})
    prepared.commit()
    assert store.read(prepared.recording_storage_key) == source.read_bytes()
    prepared.rollback()
    assert_error(422, lambda: store.prepare(manifest(evidence=False), {"input.mp4": b"disguised garbage"}))


@pytest.mark.parametrize("key", ["../../outside", "/tmp/outside", "objects/../outside", "objects\\outside", ".staging/private", "https://example.test/image.png"])
def test_storage_keys_are_not_paths_or_urls(store, key):
    assert_error(404, lambda: store.read(key))


def test_missing_media_and_symlinked_files_are_404(store, tmp_path):
    key = "objects/" + "a" * 32 + "/" + "b" * 32 + ".png"
    assert_error(404, lambda: store.read(key))
    target = store.root / key
    target.parent.mkdir()
    outside = tmp_path / "outside.png"
    outside.write_bytes(b"private")
    target.symlink_to(outside)
    assert_error(404, lambda: store.read(key))
    target.unlink()
    target.parent.rmdir()
    target.parent.symlink_to(tmp_path, target_is_directory=True)
    assert_error(404, lambda: store.read(key))


def test_store_rejects_symlinked_managed_directories(tmp_path):
    outside = tmp_path / "outside"
    outside.mkdir()
    root = tmp_path / "media"
    root.mkdir()
    (root / "objects").symlink_to(outside, target_is_directory=True)
    with pytest.raises(ValueError):
        MediaStore(root)


@pytest.mark.parametrize("damage", ["empty", "truncated", "corrupt", "oversized", "pixels"])
def test_published_image_damage_is_missing_and_repaired_bytes_are_available(tmp_path, monkeypatch, damage):
    from PIL import Image

    store = MediaStore(tmp_path / "media", max_bytes=4096, max_pixels=64)
    bypass_video_decode(monkeypatch, store)
    original = image_bytes()
    prepared = store.prepare(manifest(), {"input.mp4": b"decoder-bypassed", "frame.png": original})
    prepared.commit()
    key = prepared.evidence_keys["evidence_1"]
    path = store.resolve(key)
    assert store.available(key)
    assert store.read(key) == original
    before = path.stat()
    if damage == "empty":
        content = b""
    elif damage == "truncated":
        content = original[:-12]
    elif damage == "corrupt":
        content = b"x" * len(original)
    elif damage == "oversized":
        content = b"x" * (store.max_bytes + 1)
    else:
        output = io.BytesIO()
        Image.new("RGB", (9, 8)).save(output, format="PNG")
        content = output.getvalue()
    path.write_bytes(content)
    if damage == "corrupt":
        # Even an equal-size rewrite with a restored mtime must invalidate the
        # cached result; the inode's ctime changes independently of that value.
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
    assert not store.available(key)
    error = assert_error(404, lambda: store.read(key))
    assert error.code == "media_missing"
    assert store.resolve(key) == path  # Keep the durable key and evidence ID.
    path.write_bytes(original)
    assert store.available(key)
    assert store.read(key) == original


def test_truncated_stored_jpeg_is_missing(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    payload = manifest()
    payload["evidence"][0]["file_name"] = "frame.jpg"
    prepared = store.prepare(payload, {"input.mp4": b"decoder-bypassed", "frame.jpg": image_bytes("JPEG")})
    prepared.commit()
    key = prepared.evidence_keys["evidence_1"]
    path = store.resolve(key)
    path.write_bytes(path.read_bytes()[:-20])
    assert not store.available(key)
    assert assert_error(404, lambda: store.read(key)).code == "media_missing"


def test_unchanged_images_reuse_validation_and_recording_metadata_skips_video_decode(store, monkeypatch):
    bypass_video_decode(monkeypatch, store)
    validate = store._validate_image
    validated = []

    def tracked(content, extension):
        validated.append(content)
        validate(content, extension)

    monkeypatch.setattr(store, "_validate_image", tracked)
    prepared = store.prepare(manifest(), {"input.mp4": b"decoder-bypassed", "frame.png": image_bytes()})
    prepared.commit()
    for _ in range(3):
        assert store.available(prepared.evidence_keys["evidence_1"])
        assert store.read(prepared.evidence_keys["evidence_1"]) == image_bytes()
        assert store.available(prepared.recording_storage_key)
    assert len(validated) == 1  # The successful import already decoded this file.


def test_image_validation_cache_has_a_fixed_bound(store, monkeypatch):
    import backend.storage.media as media_module

    monkeypatch.setattr(media_module, "_IMAGE_VALIDATION_CACHE_SIZE", 2)
    bypass_video_decode(monkeypatch, store)
    keys = []
    for _ in range(3):
        prepared = store.prepare(manifest(), {"input.mp4": b"decoder-bypassed", "frame.png": image_bytes()})
        prepared.commit()
        keys.append(prepared.evidence_keys["evidence_1"])
    assert len(store._image_validation_cache) == 2
    assert keys[0] not in store._image_validation_cache
    assert store.available(keys[0])  # An evicted entry is validated again.
    assert len(store._image_validation_cache) == 2
