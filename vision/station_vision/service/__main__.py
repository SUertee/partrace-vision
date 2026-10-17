import argparse
from pathlib import Path

import uvicorn

from .app import create_app
from .config import load_config


def main():
    parser = argparse.ArgumentParser(description='Single-worker A/B vision service')
    parser.add_argument('--config', default='configs/vision_service.json')
    args = parser.parse_args()
    config = load_config(Path(__file__).resolve().parents[2], args.config)
    # No reload/multiple Uvicorn workers: one queue and one shared archive writer.
    uvicorn.run(create_app(config), host=config.values['host'], port=config.values['port'], workers=1)


if __name__ == '__main__':
    main()
