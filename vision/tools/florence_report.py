"""Rebuild Florence/KLT visual report without inference or API calls."""
import argparse
from pathlib import Path
from station_vision.florence.visualization import render_saved

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run_name')
    args = parser.parse_args()
    render_saved(Path(__file__).resolve().parents[1], args.run_name)
