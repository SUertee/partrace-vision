"""Validated local media, privately staged before an import transaction publishes it.

Decoding establishes that bytes are media, not that the uploaded frame, bounding box,
or Vision interpretation is authentic. Those remain authorized producer assertions.
The media root is a private application directory, never an HTTP upload destination.
"""

from __future__ import annotations

from collections import OrderedDict
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import threading
from dataclasses import dataclass, field
from fractions import Fraction
from uuid import uuid4
import warnings

from backend.core.errors import ApiError


_STORAGE_KEY = re.compile(r"objects/[0-9a-f]{32}/[0-9a-f]{32}\.(?:mp4|png|jpg|jpeg)\Z")
_IMAGE_VALIDATION_CACHE_SIZE = 8192


def _invalid(message: str) -> ApiError:
    return ApiError(422, "validation_error", message)


def _basename(name: object) -> str:
    # In particular reject Windows paths, control characters and leading options.
    if (
        not isinstance(name, str)
        or not re.fullmatch(r"\w[\w .-]{0,199}", name, flags=re.UNICODE)
        or name.endswith((" ", "."))
        or name in (".", "..")
    ):
        raise _invalid("Media filenames must be plain basenames of at most 200 characters.")
    return name


@dataclass(slots=True)
class PreparedMedia:
    recording_storage_key: str | None
    evidence_keys: dict[str, str | None]
    digests: dict[str, str]
    _store: MediaStore = field(repr=False)
    _staged: Path | None = field(default=None, repr=False)
    _final: Path | None = field(default=None, repr=False)
    _committed: bool = field(default=False, init=False, repr=False)
    _rolled_back: bool = field(default=False, init=False, repr=False)

    @property
    def published(self) -> bool:
        """True after publication, until deliberate compensating cleanup.

        A DB connection loss during COMMIT has an ambiguous outcome: the caller
        must retain published media until that transaction's result is known.
        """
        return self._committed and not self._rolled_back

    def commit(self) -> None:
        """Atomically publish our unique directory; call before the DB commits."""
        if self._rolled_back:
            raise RuntimeError("Media has already been rolled back.")
        if self._committed:
            return
        if self._staged is not None:
            try:
                self._store._check_directories()
                if self._staged.is_symlink() or not self._staged.is_dir():
                    raise OSError("Unsafe staging directory")
                assert self._final is not None
                if self._final.exists() or self._final.is_symlink():
                    raise OSError("Media destination already exists")
                # Both directories live on one filesystem. No client-provided ID
                # or basename participates in the published destination.
                self._staged.rename(self._final)
            except OSError as exc:
                raise ApiError(503, "media_storage_unavailable", "Media cannot be published.") from exc
        self._committed = True

    def rollback(self) -> None:
        """Remove only this import's files, including after a failed DB commit."""
        if self._rolled_back:
            return
        target = self._final if self._committed else self._staged
        if target is not None:
            try:
                self._store._check_directories()
                if target.is_symlink():
                    # Never follow a substituted directory outside the store.
                    target.unlink()
                elif target.exists():
                    shutil.rmtree(target)
            except OSError as exc:
                raise ApiError(503, "media_storage_unavailable", "Media cleanup failed.") from exc
        self._rolled_back = True


class MediaStore:
    def __init__(
        self,
        root: Path,
        ffprobe_path: str = "ffprobe",
        max_bytes: int = 256 * 1024 * 1024,
        *,
        ffmpeg_path: str = "ffmpeg",
        duration_tolerance_ms: int = 100,
        validation_timeout_seconds: float = 120,
        max_pixels: int = 16_777_216,
    ) -> None:
        if max_bytes < 1 or duration_tolerance_ms < 0 or validation_timeout_seconds <= 0 or max_pixels < 1:
            raise ValueError("Media resource limits must be positive.")
        root = Path(root)
        if root.is_symlink():
            raise ValueError("Media root must not be a symbolic link.")
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root = root.resolve(strict=True)
        self.ffprobe_path = ffprobe_path
        self.ffmpeg_path = ffmpeg_path
        self.max_bytes = max_bytes
        self.duration_tolerance_ms = duration_tolerance_ms
        self.validation_timeout_seconds = validation_timeout_seconds
        self.max_pixels = max_pixels
        # Cache only validation results, never image bytes. File identity and both
        # timestamps invalidate normal writes/replacements, including same-size
        # corruption; the bound also covers a default 5000-frame result bundle.
        self._image_validation_cache: OrderedDict[str, tuple[tuple[int, ...], bool]] = OrderedDict()
        self._image_validation_lock = threading.Lock()
        for path in (self.root / ".staging", self.root / "objects"):
            if path.is_symlink():
                raise ValueError("Media directories must not be symbolic links.")
            path.mkdir(exist_ok=True, mode=0o700)
        self._check_directories()

    def _check_directories(self) -> None:
        for directory in (self.root, self.root / ".staging", self.root / "objects"):
            if directory.is_symlink() or not directory.is_dir():
                raise OSError("Unsafe media directory")

    def prepare(self, manifest: dict, uploads: dict[str, bytes]) -> PreparedMedia:
        """Validate all input, hash actual bytes, and leave a private staged bundle.

        The multipart parser must reject duplicate parts before constructing its
        uploads dictionary; a dictionary cannot represent duplicate filenames.
        Public manifest structure and all cross-object facts are validated by the
        import service before calling this method.
        """
        recording = manifest["recording"]
        evidence = manifest["evidence"]
        source_mode = recording["source_mode"]
        evidence_keys = {item["evidence_id"]: None for item in evidence}
        if source_mode == "mock":
            if (
                uploads
                or recording["file_name"] is not None
                or recording["started_at"] is not None
                or any(item["file_name"] is not None for item in evidence)
                or any(item["detection_source"] != "mock" for item in manifest["events"])
            ):
                raise _invalid("Mock imports must have no media, no real start time and only mock events.")
            return PreparedMedia(None, evidence_keys, {}, self)
        if source_mode not in ("prerecorded", "sdk") or recording["file_name"] is None:
            raise _invalid("Real imports require a continuous MP4 video.")

        video_name = _basename(recording["file_name"])
        if Path(video_name).suffix.lower() != ".mp4":
            raise _invalid("The source recording must be an MP4 video.")
        declared = [video_name]
        frame_names: dict[str, str] = {}
        for item in evidence:
            if item["file_name"] is not None:
                name = _basename(item["file_name"])
                if Path(name).suffix.lower() not in (".png", ".jpg", ".jpeg"):
                    raise _invalid("Evidence must be a PNG or JPEG image.")
                declared.append(name)
                frame_names[item["evidence_id"]] = name
        if len({name.casefold() for name in declared}) != len(declared):
            raise _invalid("Every media filename must be referenced exactly once.")
        for name in uploads:
            _basename(name)
        if set(declared) != set(uploads):
            raise _invalid("Uploaded media must match every declared filename with no extra files.")
        total = 0
        for content in uploads.values():
            if not isinstance(content, bytes) or not content:
                raise _invalid("Media uploads must contain nonempty bytes.")
            total += len(content)
            if total > self.max_bytes:
                raise ApiError(413, "payload_too_large", "The media bundle exceeds the upload limit.")

        staged: Path | None = None
        prepared: PreparedMedia | None = None
        try:
            self._check_directories()
            staged = Path(tempfile.mkdtemp(prefix="import-", dir=self.root / ".staging"))
            bundle_key = uuid4().hex
            final = self.root / "objects" / bundle_key
            keys: dict[str, str] = {}
            paths: dict[str, Path] = {}
            digests: dict[str, str] = {}
            for name, content in uploads.items():
                private_name = uuid4().hex + Path(name).suffix.lower()
                path = staged / private_name
                with path.open("xb") as stream:
                    os.chmod(path, 0o600)
                    stream.write(content)
                paths[name] = path
                keys[name] = f"objects/{bundle_key}/{private_name}"
                digests[name] = hashlib.sha256(content).hexdigest()
            prepared = PreparedMedia(
                keys[video_name],
                {item["evidence_id"]: keys[item["file_name"]] if item["file_name"] else None for item in evidence},
                digests,
                self,
                staged,
                final,
            )
            self._validate_video(paths[video_name], recording["duration_ms"])
            for name in frame_names.values():
                self._validate_image(uploads[name], Path(name).suffix.lower())
                self._remember_image_validation(keys[name], self._file_signature(paths[name].stat()), True)
            return prepared
        except BaseException as exc:
            if prepared is not None:
                prepared.rollback()
            elif staged is not None:
                shutil.rmtree(staged)
            if isinstance(exc, OSError):
                raise ApiError(503, "media_storage_unavailable", "Media cannot be staged.") from exc
            raise

    def _run(self, command: list[str]) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(
                command,
                capture_output=True,
                text=True,
                errors="replace",
                check=True,
                timeout=self.validation_timeout_seconds,
            )
        except (FileNotFoundError, PermissionError) as exc:
            raise ApiError(503, "media_validation_unavailable", "Media validation tools are unavailable.") from exc
        except subprocess.TimeoutExpired as exc:
            raise ApiError(503, "media_validation_unavailable", "Media validation exceeded its time limit.") from exc
        except subprocess.CalledProcessError as exc:
            # Never expose ffmpeg diagnostics or local filesystem paths.
            raise _invalid("Media could not be decoded.") from exc

    def _validate_video(self, path: Path, duration_ms: int) -> None:
        result = self._run([
            self.ffprobe_path, "-v", "error", "-protocol_whitelist", "file,pipe",
            "-show_entries",
            "format=format_name,duration:stream=codec_type,codec_name,width,height,duration,avg_frame_rate,r_frame_rate,nb_frames:stream_disposition=attached_pic",
            "-of", "json", str(path),
        ])
        try:
            probe = json.loads(result.stdout)
            streams = [stream for stream in probe["streams"] if stream.get("codec_type") == "video"]
            if len(streams) != 1:
                raise ValueError("Expected one video stream")
            stream = streams[0]
            if "mp4" not in probe["format"]["format_name"].split(","):
                raise ValueError("Expected MP4 container")
            if not stream.get("codec_name") or stream.get("disposition", {}).get("attached_pic", 0):
                raise ValueError("Expected continuous video")
            width, height = int(stream["width"]), int(stream["height"])
            if width <= 0 or height <= 0 or width * height > self.max_pixels:
                raise ValueError("Video dimensions exceed limits")
            duration = float(stream.get("duration") or probe["format"]["duration"]) * 1000
            fps = float(Fraction(stream.get("avg_frame_rate") or stream["r_frame_rate"]))
            if not math.isfinite(duration) or duration <= 0 or not math.isfinite(fps) or fps <= 0:
                raise ValueError("Invalid video timebase")
            if stream.get("nb_frames", "N/A") != "N/A" and int(stream["nb_frames"]) < 2:
                raise ValueError("A still frame is not continuous video")
            tolerance = max(self.duration_tolerance_ms, 1000 / fps)
            if abs(duration - duration_ms) > tolerance:
                raise ValueError("Video duration does not match the manifest")
        except (KeyError, TypeError, ValueError, ZeroDivisionError, OverflowError) as exc:
            raise _invalid("A continuous MP4 video with matching duration is required.") from exc

        # Inspecting container metadata alone does not establish decodability.
        decoded = self._run([
            self.ffmpeg_path, "-nostdin", "-v", "error", "-xerror", "-threads", "1",
            "-err_detect", "explode", "-protocol_whitelist", "file,pipe", "-i", str(path),
            "-map", "0:v:0", "-an", "-sn", "-dn", "-progress", "pipe:1",
            "-stats_period", "10", "-f", "null", "-",
        ])
        frames = re.findall(r"^frame=(\d+)\s*$", decoded.stdout, flags=re.MULTILINE)
        if not frames or int(frames[-1]) < 2:
            raise _invalid("A recording must contain at least two decodable video frames.")

    def _validate_image(self, content: bytes, extension: str) -> None:
        try:
            from PIL import Image, UnidentifiedImageError
        except ImportError as exc:
            raise ApiError(503, "media_validation_unavailable", "Image validation is unavailable.") from exc
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(content)) as image:
                    expected = "PNG" if extension == ".png" else "JPEG"
                    if image.format != expected or getattr(image, "n_frames", 1) != 1:
                        raise ValueError("Expected one image matching its filename")
                    width, height = image.size
                    if width <= 0 or height <= 0 or width * height > self.max_pixels:
                        raise ValueError("Image dimensions exceed limits")
                    image.verify()
                with Image.open(io.BytesIO(content)) as image:
                    image.load()  # verify alone is not a full pixel decode.
        except (UnidentifiedImageError, OSError, ValueError, SyntaxError, EOFError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
            raise _invalid("Evidence must be a decodable single PNG or JPEG image.") from exc

    def resolve(self, storage_key: str) -> Path:
        """Resolve only our generated keys, refusing symlinks in every component."""
        missing = ApiError(404, "media_missing", "The media file is unavailable.")
        if not isinstance(storage_key, str) or not _STORAGE_KEY.fullmatch(storage_key):
            raise missing
        try:
            self._check_directories()
            current = self.root
            for component in storage_key.split("/"):
                current = current / component
                if current.is_symlink():
                    raise missing
            if not current.is_file() or not current.resolve(strict=True).is_relative_to(self.root):
                raise missing
            return current
        except OSError as exc:
            raise missing from exc

    @staticmethod
    def _file_signature(metadata: os.stat_result) -> tuple[int, ...]:
        return (metadata.st_dev, metadata.st_ino, metadata.st_size,
                metadata.st_mtime_ns, metadata.st_ctime_ns)

    def _cached_image_validation(self, storage_key: str, signature: tuple[int, ...]) -> bool | None:
        with self._image_validation_lock:
            cached = self._image_validation_cache.get(storage_key)
            if cached is not None and cached[0] == signature:
                self._image_validation_cache.move_to_end(storage_key)
                return cached[1]
        return None

    def _remember_image_validation(self, storage_key: str, signature: tuple[int, ...], valid: bool) -> None:
        with self._image_validation_lock:
            self._image_validation_cache[storage_key] = (signature, valid)
            self._image_validation_cache.move_to_end(storage_key)
            while len(self._image_validation_cache) > _IMAGE_VALIDATION_CACHE_SIZE:
                self._image_validation_cache.popitem(last=False)

    def available(self, storage_key: str) -> bool:
        """Use the same image validity rules for metadata, media and Agent citations.

        A recording metadata lookup checks file bounds without re-decoding MP4.
        Previously validated, unchanged images need only a stat lookup; changed
        images undergo the same bounded full decode required during import.
        """
        try:
            path = self.resolve(storage_key)
            metadata = path.stat(follow_symlinks=False)
            if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= self.max_bytes:
                return False
            if path.suffix == ".mp4":
                return True
            cached = self._cached_image_validation(storage_key, self._file_signature(metadata))
            if cached is not None:
                return cached
            self.read(storage_key)
            return True
        except (ApiError, OSError):
            return False

    def read(self, storage_key: str) -> bytes:
        path = self.resolve(storage_key)
        try:
            descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(descriptor, "rb") as stream:
                metadata = os.fstat(stream.fileno())
                if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= self.max_bytes:
                    raise OSError("Invalid stored media file")
                content = stream.read(self.max_bytes + 1)
                signature = self._file_signature(metadata)
                if (not content or len(content) > self.max_bytes
                        or self._file_signature(os.fstat(stream.fileno())) != signature):
                    raise OSError("Invalid stored media size")
                if path.suffix != ".mp4":
                    valid = self._cached_image_validation(storage_key, signature)
                    if valid is None:
                        try:
                            self._validate_image(content, path.suffix)
                        except ApiError as error:
                            if error.code != "validation_error":
                                raise
                            valid = False
                        else:
                            valid = True
                        self._remember_image_validation(storage_key, signature, valid)
                    if not valid:
                        raise OSError("Invalid stored image")
                return content
        except OSError as exc:
            raise ApiError(404, "media_missing", "The media file is unavailable.") from exc
