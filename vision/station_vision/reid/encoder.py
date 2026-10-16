"""Explicit OSNet preprocessing; no hidden ImageNet-only or random-weight fallback."""
from pathlib import Path
import hashlib
import cv2
import numpy as np
import torch
from PIL import Image
from torchvision import transforms


def read_image(path):
    return cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)


def crop_person(frame, bbox):
    x1, y1, x2, y2 = np.asarray(bbox[:4], dtype=float)
    if not np.isfinite([x1, y1, x2, y2]).all():
        return None
    h, w = frame.shape[:2]
    x1, y1 = max(0, int(np.floor(x1))), max(0, int(np.floor(y1)))
    x2, y2 = min(w, int(np.ceil(x2))), min(h, int(np.ceil(y2)))
    return frame[y1:y2, x1:x2].copy() if x2 > x1 and y2 > y1 else None


def crop_quality(crop, min_height=80, min_width=24, min_blur=15.0):
    if crop is None or not crop.size:
        return False, {'reason': 'empty_crop'}
    h, w = crop.shape[:2]
    blur = float(cv2.Laplacian(cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var())
    ok = h >= min_height and w >= min_width and blur >= min_blur
    return ok, dict(height=h, width=w, blur=round(blur, 3),
                    reason=None if ok else 'small_or_blurred_crop')


class OSNetEncoder:
    def __init__(self, root: Path, device='cuda', weights='models/reid/osnet_x0_5_msmt17.pth'):
        try:
            from ._vendor.osnet import osnet_x0_5
        except ImportError as exc:
            raise RuntimeError('Run .venv-track/Scripts/python.exe -m tools.prepare_person_reid first') from exc
        self.path = root / weights
        if not self.path.is_file():
            raise FileNotFoundError(f'OSNet ReID weights missing: {self.path}; run tools.prepare_person_reid')
        self.device = torch.device(device)
        self.model = osnet_x0_5(num_classes=1, pretrained=False)
        checkpoint = torch.load(self.path, map_location='cpu', weights_only=True)
        state = checkpoint.get('state_dict', checkpoint)
        state = {k.removeprefix('module.'): v for k, v in state.items()}
        expected = self.model.state_dict()
        missing = [k for k in expected if not k.startswith('classifier.') and
                   not k.endswith('num_batches_tracked') and
                   (k not in state or state[k].shape != expected[k].shape)]
        if missing:
            raise ValueError(f'Incompatible OSNet checkpoint; missing/mismatched backbone keys: {missing[:8]}')
        compatible = {k: v for k, v in state.items() if k in expected and
                      not k.startswith('classifier.') and v.shape == expected[k].shape}
        self.model.load_state_dict(compatible, strict=False)
        self.model.eval().to(self.device)
        self.transform = transforms.Compose([
            transforms.Resize((256, 128)), transforms.ToTensor(),
            transforms.Normalize([.485, .456, .406], [.229, .224, .225]),
        ])
        self.sha256 = hashlib.sha256(self.path.read_bytes()).hexdigest()

    @torch.inference_mode()
    def encode(self, crops):
        if not crops:
            return np.empty((0, 512), dtype=np.float32)
        images = [self.transform(Image.fromarray(cv2.cvtColor(c, cv2.COLOR_BGR2RGB))) for c in crops]
        features = self.model(torch.stack(images).to(self.device)).float()
        if features.ndim != 2 or not torch.isfinite(features).all() or (features.norm(dim=1) < 1e-8).any():
            raise RuntimeError('Invalid OSNet embedding output')
        return torch.nn.functional.normalize(features, dim=1).cpu().numpy()
