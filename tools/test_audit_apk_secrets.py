#!/usr/bin/env python3
"""Regression tests for the APK credential and signing-material scanner."""
from __future__ import annotations
import importlib.util
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("audit_apk_secrets.py")
SPEC = importlib.util.spec_from_file_location("audit_apk_secrets", MODULE_PATH)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)
MODEL_ENTRY = "assets/mlkit_label_default_model/mobile_ica_8bit_with_metadata_tflite"
TOKEN = b"ghp_" + b"A" * 25

class ApkSecretAuditTests(unittest.TestCase):
    def make_apk(self, entries):
        handle = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        handle.close()
        path = Path(handle.name)
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        self.addCleanup(path.unlink, missing_ok=True)
        return path

    def test_exact_known_mlkit_binary_model_false_positive_is_ignored(self):
        AUDIT.scan_archive(self.make_apk({MODEL_ENTRY: TOKEN}))

    def test_token_in_any_other_entry_is_rejected(self):
        with self.assertRaisesRegex(SystemExit, "Possible credential/token material"):
            AUDIT.scan_archive(self.make_apk({"assets/user-config.bin": TOKEN}))

    def test_signing_material_is_rejected_even_when_empty(self):
        with self.assertRaisesRegex(SystemExit, "Signing material embedded"):
            AUDIT.scan_archive(self.make_apk({"assets/release-upload-key.jks": b"not a real key"}))

    def test_token_inside_nested_zip_is_rejected(self):
        nested_buffer = io.BytesIO()
        with zipfile.ZipFile(nested_buffer, "w") as nested:
            nested.writestr("config.txt", TOKEN)
        with self.assertRaisesRegex(SystemExit, "Possible credential/token material"):
            AUDIT.scan_archive(self.make_apk({"assets/imported.zip": nested_buffer.getvalue()}))

if __name__ == "__main__":
    unittest.main()
