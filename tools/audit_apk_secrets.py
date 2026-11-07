#!/usr/bin/env python3
"""Fail the APK release if credentials or signing material are embedded in the APK."""
from __future__ import annotations
import re
import sys
import zipfile
from pathlib import Path

TOKEN_PATTERNS = [
    re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(rb"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(rb"sk-[A-Za-z0-9]{20,}"),
    re.compile(rb"AIza[0-9A-Za-z_-]{30,}"),
    re.compile(rb"hf_[A-Za-z0-9]{20,}"),
    re.compile(rb"Bearer\s+[A-Za-z0-9._~-]{24,}", re.IGNORECASE),
]
FORBIDDEN_SUFFIXES = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")

def scan_archive(path: Path) -> None:
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        forbidden = [n for n in names if n.lower().endswith(FORBIDDEN_SUFFIXES) or "debug.keystore" in n.lower()]
        if forbidden:
            raise SystemExit("Signing material embedded in APK: " + ", ".join(forbidden))
        hits = []
        for name in names:
            if name.endswith("/"):
                continue
            data = apk.read(name)
            if name.lower().endswith(".zip"):
                import io
                try:
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        for inner in nested.namelist():
                            if inner.endswith("/"):
                                continue
                            nested_data = nested.read(inner)
                            if any(pattern.search(nested_data) for pattern in TOKEN_PATTERNS):
                                hits.append(name + "!" + inner)
                except zipfile.BadZipFile:
                    pass
            if any(pattern.search(data) for pattern in TOKEN_PATTERNS):
                hits.append(name)
        if hits:
            raise SystemExit("Possible credential/token material found in APK entries: " + ", ".join(sorted(set(hits))))
    print("APK credential/signing-material scan: PASS")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: audit_apk_secrets.py path/to/app.apk")
    apk_path = Path(sys.argv[1])
    if not apk_path.is_file():
        raise SystemExit("APK not found: " + str(apk_path))
    scan_archive(apk_path)
