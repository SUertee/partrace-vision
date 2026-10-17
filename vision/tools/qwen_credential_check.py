"""Local credential availability only; no API calls and no key output."""
import os
import argparse
from pathlib import Path

from station_vision.multiframe_review.client import load_key,credential_path,ANKER_BASE


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--provider',choices=['anker','qianwen'],default='anker');args=parser.parse_args()
    base=ANKER_BASE if args.provider=='anker' else 'https://maas.qianwenaiapi.com/compatible-mode/v1'
    print('Credential source: '+('STATION_VLM_API_KEY' if os.environ.get('STATION_VLM_API_KEY')
          else str(credential_path(base))))
    try:
        if not load_key(base).strip():
            raise RuntimeError('Empty credential')
    except Exception:
        print('Credential load FAILED. Run .\\setup_qwen_key.ps1 in the same terminal.')
        return 1
    print('Credential load OK. No API request sent; server acceptance has not been tested.')
    return 0


if __name__=='__main__':
    raise SystemExit(main())
