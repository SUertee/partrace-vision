"""Portable image calibration: validate PNG, seed ORB and scale region coordinates."""
import base64
import hashlib

import cv2
import numpy as np

from ..registration import FrameRegistration


def decode_reference(config):
    calibration=config.get('shelf_calibration',{})
    if calibration.get('source')!='manual_image':raise ValueError('Not an image calibration')
    reference=calibration.get('reference_image',{})
    encoded=reference.get('data_base64')
    if reference.get('mime_type')!='image/png' or not isinstance(encoded,str) or not 0<len(encoded)<=64_000_000:
        raise ValueError('图片标定JSON缺少有效的内嵌PNG参考图。')
    try:raw=base64.b64decode(encoded,validate=True)
    except (ValueError,TypeError) as exc:raise ValueError('参考图片编码无效。') from exc
    digest=hashlib.sha256(raw).hexdigest()
    if reference.get('sha256') is not None and reference['sha256']!=digest:
        raise ValueError('参考图片哈希不匹配。')
    image=cv2.imdecode(np.frombuffer(raw,np.uint8),cv2.IMREAD_COLOR)
    if image is None:raise ValueError('无法解码标定参考图。')
    height,width=image.shape[:2]
    if calibration.get('width')!=width or calibration.get('height')!=height:
        raise ValueError('标定尺寸与内嵌参考图片不一致。')
    from .manual import validate_zones
    validate_zones(config.get('shelf_zones'),width,height)
    return image,raw,digest


def prepare_registration(config,width,height):
    """Image-mode matrices map resized reference-image coordinates to each frame.

    Unlike old first-frame mode, even the first video frame must match the
    external reference. Invalid warps never produce active shelf polygons.
    """
    from .manual import validate_zones
    registration=FrameRegistration(enabled=True,**config.get('runtime_parameters',{}).get('registration',{}))
    zones=config.get('shelf_zones',[])
    calibration=config.get('shelf_calibration',{})
    if calibration.get('source')!='manual_image':
        return registration,zones,{'reference_kind':'video_first_frame'}
    image,raw,digest=decode_reference(config)
    ih,iw=image.shape[:2]
    if abs((iw/ih)/(width/height)-1)>.01:
        raise ValueError('标定图与视频宽高比不同。请使用同一B机位、不裁剪、不带播放器边框的截图。')
    sx,sy=width/iw,height/ih
    scaled=[{**z,'polygon':[[round(x*sx,2),round(y*sy,2)] for x,y in z['polygon']]} for z in zones]
    # Clamp only the numerical edge of a uniform resize, never an out-of-view region.
    for z in scaled:
        z['polygon']=[[min(width-1,x),min(height-1,y)] for x,y in z['polygon']]
    scaled=validate_zones(scaled,width,height)
    reference=cv2.resize(image,(width,height),interpolation=cv2.INTER_AREA if sx<1 else cv2.INTER_LINEAR)
    registration.process(reference)
    if registration.descriptors is None or len(registration.descriptors)<registration.min_inliers:
        raise ValueError('标定图片静态纹理不足，无法核对视频机位。请换清晰且货架完整的截图。')
    return registration,scaled,{'reference_kind':'external_image','reference_image_sha256':digest,
        'reference_width':iw,'reference_height':ih,'reference_scale_xy':[sx,sy],
        'source_kind':calibration['reference_image'].get('source_kind'),
        'reference_source_time_ms':calibration['reference_image'].get('source_time_ms'),
        'alignment_checked_per_frame':True,'reference_png':raw}
