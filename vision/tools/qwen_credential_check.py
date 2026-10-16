"""Local credential availability only; no API calls and no key output."""
import os
from pathlib import Path

from station_vision.multiframe_review.client import load_key


def main():
    print('Credential source: '+('STATION_VLM_API_KEY' if os.environ.get('STATION_VLM_API_KEY')
          else str(Path(os.environ.get('LOCALAPPDATA',''))/'StationVision/qwen-key.dpapi')))
    try:
        if not load_key().strip():
            raise RuntimeError('Empty credential')
    except Exception:
        print('Credential load FAILED. Run .\\setup_qwen_key.ps1 in the same terminal.')
        return 1
    print('Credential load OK. No API request sent; server acceptance has not been tested.')
    return 0


if __name__=='__main__':
    raise SystemExit(main())
