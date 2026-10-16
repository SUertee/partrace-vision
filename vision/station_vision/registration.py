from __future__ import annotations
import cv2
import numpy as np


class FrameRegistration:
    """Optional phone-screen stabilization. Never accept an unverified warp."""
    def __init__(self, enabled: bool, min_inliers=24, min_ratio=0.3,orb_features=1800,match_ratio=.72,ransac_px=3.5,min_area_ratio=.55,max_area_ratio=1.8,max_shift_ratio=.23):
        self.enabled = enabled
        self.min_inliers, self.min_ratio = min_inliers, min_ratio
        self.orb = cv2.ORB_create(nfeatures=orb_features)
        self.match_ratio,self.ransac_px=match_ratio,ransac_px
        self.min_area_ratio,self.max_area_ratio,self.max_shift_ratio=min_area_ratio,max_area_ratio,max_shift_ratio
        self.reference_gray = None
        self.keypoints = None
        self.descriptors = None
        self.last_gray = None

    def process(self, frame):
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        if self.reference_gray is None:
            self.reference_gray = gray.copy()
            mask = np.zeros_like(gray)
            mask[int(h * .07):int(h * .83), int(w * .08):int(w * .94)] = 255
            self.keypoints, self.descriptors = self.orb.detectAndCompute(gray, mask)
        if not self.enabled:
            cut_distance = 0.0
            if self.last_gray is not None:
                a = cv2.calcHist([self.last_gray], [0], None, [64], [0, 256])
                b = cv2.calcHist([gray], [0], None, [64], [0, 256])
                cv2.normalize(a, a)
                cv2.normalize(b, b)
                cut_distance = float(cv2.compareHist(a, b, cv2.HISTCMP_BHATTACHARYYA))
            self.last_gray = gray
            return frame, np.eye(3), {"valid": cut_distance < .7, "mode": "fixed",
                                     "cut_distance": cut_distance}
        mask = np.zeros_like(gray)
        mask[int(h * .05):int(h * .88), int(w * .05):int(w * .97)] = 255
        kp, desc = self.orb.detectAndCompute(gray, mask)
        failure = {"valid": False, "mode": "orb", "reason": "insufficient_static_matches"}
        if desc is None or self.descriptors is None:
            return frame, np.eye(3), failure
        pairs = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(desc, self.descriptors, k=2)
        good = [m for pair in pairs if len(pair) == 2 for m, n in [pair] if m.distance < self.match_ratio * n.distance]
        if len(good) < self.min_inliers:
            return frame, np.eye(3), failure
        src = np.float32([kp[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([self.keypoints[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        matrix, inlier_mask = cv2.findHomography(src, dst, cv2.RANSAC, self.ransac_px)
        if matrix is None or inlier_mask is None or not np.isfinite(matrix).all():
            return frame, np.eye(3), failure
        inliers = int(inlier_mask.sum())
        ratio = inliers / len(good)
        corners = np.float32([[0, 0], [w, 0], [w, h], [0, h]]).reshape(-1, 1, 2)
        mapped = cv2.perspectiveTransform(corners, matrix)
        area_ratio = abs(cv2.contourArea(mapped)) / (w * h)
        shift = float(np.mean(np.linalg.norm(mapped - corners, axis=2))) / max(w, h)
        valid = (inliers >= self.min_inliers and ratio >= self.min_ratio
                 and self.min_area_ratio < area_ratio < self.max_area_ratio and shift < self.max_shift_ratio
                 and abs(np.linalg.det(matrix)) > 1e-8)
        quality = {"valid": valid, "mode": "orb", "matches": len(good),
                   "inliers": inliers, "inlier_ratio": ratio, "area_ratio": area_ratio,
                   "corner_shift_ratio": shift}
        if not valid:
            quality["reason"] = "warp_validation_failed"
            return frame, np.eye(3), quality
        aligned = cv2.warpPerspective(frame, matrix, (w, h))
        return aligned, np.linalg.inv(matrix), quality

