"""CJK visualization font selection for Windows and Ubuntu."""
import os
from pathlib import Path


def display_font():
    configured = os.environ.get('STATION_VISION_FONT')
    if configured:
        if not Path(configured).is_file():
            raise RuntimeError('STATION_VISION_FONT does not point to a font file')
        return configured
    for filename in ('C:/Windows/Fonts/msyh.ttc',
                     '/System/Library/Fonts/PingFang.ttc',
                     '/System/Library/Fonts/STHeiti Medium.ttc',
                     '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
                     '/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc'):
        if Path(filename).is_file():
            return filename
    raise RuntimeError('Install fonts-noto-cjk on Ubuntu or set STATION_VISION_FONT')
