"""Absolute path of this repository, resolved from this file's own location.

Import REPO instead of hardcoding a home directory so the repo works from any
clone path. Override with the IRMSC_ROOT environment variable.
"""
import os
from pathlib import Path

REPO = Path(os.environ.get("IRMSC_ROOT") or Path(__file__).resolve().parent.parent)
