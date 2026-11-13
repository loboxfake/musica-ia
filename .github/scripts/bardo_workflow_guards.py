#!/usr/bin/env python3
"""Bounded decoder and authoritative path guard for BARDO candidate workflows."""
from __future__ import annotations

import argparse
import base64
import binascii
import gzip
import hashlib
import io
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unicodedata
import zlib

MAX_PATCH_BYTES = 384 * 1024
MAX_COMPRESSED_BYTES = 400 * 1024
MAX_BASE64_CHARS = 4 * ((MAX_COMPRESSED_BYTES + 2) // 3)
MODO3_FILENAME = "Modo3MezclaVozUsuario.kt"
WORKFLOW_ALLOWED_PREFIXES = (
    "app/src/main/java/",
    "app/src/main/res/",
    "docs/",
)


class GuardError(ValueError):
    pass


def bounded_gzip_decompress(encoded: str) -> bytes:
    if not encoded or len(encoded) > MAX_BASE64_CHARS:
        raise GuardError("BARDO_PATCH_ENCODED_SIZE_INVALID")
    try:
        packed = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise GuardError("BARDO_PATCH_BASE64_INVALID") from exc
    if not packed or len(packed) > MAX_COMPRESSED_BYTES:
        raise GuardError("BARDO_PATCH_COMPRESSED_SIZE_INVALID")
    try:
        # GzipFile.read(size) asks zlib for at most size output bytes. Reading
        # MAX+1 detects expansion beyond the hard limit without materializing it.
        with gzip.GzipFile(fileobj=io.BytesIO(packed), mode="rb") as stream:
            patch = stream.read(MAX_PATCH_BYTES + 1)
    except (OSError, EOFError, zlib.error) as exc:
        raise GuardError("BARDO_PATCH_GZIP_INVALID") from exc
    if not patch:
        raise GuardError("BARDO_PATCH_EMPTY")
    if len(patch) > MAX_PATCH_BYTES:
        raise GuardError("BARDO_PATCH_SIZE_LIMIT_EXCEEDED")
    return patch


def parse_kotlin_collection(source: str, property_name: str, constructor: str) -> list[str]:
    pattern = (
        rf"private\s+val\s+{re.escape(property_name)}\s*=\s*"
        rf"{constructor}\(\s*(.*?)\n[ \t]{{4}}\)"
    )
    match = re.search(pattern, source, re.DOTALL)
    if not match:
        raise GuardError(f"BARDO_AUTHORITATIVE_POLICY_MISSING:{property_name}")
    values = re.findall(r'"([^"]+)"', match.group(1))
    # Kotlin setOf intentionally collapses duplicate literals; preserve that behavior.
    values = list(dict.fromkeys(values))
    if not values:
        raise GuardError(f"BARDO_AUTHORITATIVE_POLICY_INVALID:{property_name}")
    return values


def load_authoritative_policy(
    policy_path: pathlib.Path,
) -> tuple[set[str], tuple[str, ...], tuple[str, ...]]:
    source = policy_path.read_text(encoding="utf-8")
    exact = set(parse_kotlin_collection(source, "protectedExact", "setOf"))
    prefixes = tuple(parse_kotlin_collection(source, "protectedPrefixes", "listOf"))
    autonomous = tuple(parse_kotlin_collection(source, "autonomousPrefixes", "listOf"))
    if not exact or not prefixes or not autonomous:
        raise GuardError("BARDO_AUTHORITATIVE_POLICY_INCOMPLETE")
    return exact, prefixes, autonomous


def normalized_safe_path(path: str) -> bool:
    if (
        not path
        or path != path.strip()
        or path.startswith("/")
        or "\\" in path
        or ":" in path
        or path.startswith("~")
        or any(unicodedata.category(char) == "Cc" for char in path)
    ):
        return False
    parts = path.split("/")
    return not any(part in ("", ".", "..") for part in parts)


def path_violation(
    path: str,
    protected_exact: set[str],
    protected_prefixes: tuple[str, ...],
    autonomous_prefixes: tuple[str, ...],
) -> str | None:
    if not normalized_safe_path(path):
        return "BARDO_UNSAFE_PATH"
    if pathlib.PurePosixPath(path).name.lower() == MODO3_FILENAME.lower():
        return "BARDO_PROTECTED_MODO3"
    if path in protected_exact or any(path.startswith(prefix) for prefix in protected_prefixes):
        return "BARDO_PROTECTED_PATH"
    if not any(path.startswith(prefix) for prefix in autonomous_prefixes):
        return "BARDO_PATH_NOT_AUTONOMOUS"
    if not path.startswith(WORKFLOW_ALLOWED_PREFIXES):
        return "BARDO_PATH_OUTSIDE_CANDIDATE_ALLOWLIST"
    return None


def read_git_changes() -> list[tuple[str, str]]:
    raw = subprocess.check_output(
        ["git", "diff", "--name-status", "--no-renames", "-z"],
    )
    parts = raw.decode("utf-8", errors="strict").split("\0")
    if parts and parts[-1] == "":
        parts.pop()
    if len(parts) % 2:
        raise GuardError("BARDO_GIT_DIFF_FORMAT_INVALID")
    return [(parts[index], parts[index + 1]) for index in range(0, len(parts), 2)]


def check_changed_paths(
    policy_path: pathlib.Path,
    report_path: pathlib.Path,
    expected_path: pathlib.Path | None = None,
) -> list[str]:
    protected_exact, protected_prefixes, autonomous_prefixes = load_authoritative_policy(
        policy_path
    )
    changes = read_git_changes()
    if not changes:
        raise GuardError("BARDO_PATCH_EMPTY")
    if len(changes) > 30:
        raise GuardError("BARDO_TOO_MANY_CHANGED_FILES")

    accepted: list[str] = []
    for status, path in changes:
        if status != "M":
            raise GuardError(f"BARDO_PATCH_MUST_MODIFY_EXISTING_FILES:{status}:{path}")
        violation = path_violation(
            path, protected_exact, protected_prefixes, autonomous_prefixes
        )
        if violation:
            raise GuardError(f"{violation}:{path}")
        target = pathlib.Path(path)
        if target.is_symlink() or not target.is_file():
            raise GuardError(f"BARDO_PATCH_TARGET_NOT_REGULAR_FILE:{path}")
        summary = subprocess.check_output(
            ["git", "diff", "--summary", "--", path], text=True
        )
        if "mode change" in summary.lower() or "create mode" in summary.lower():
            raise GuardError(f"BARDO_PATCH_FILE_MODE_CHANGE:{path}")
        accepted.append(path)

    if len(accepted) != len(set(accepted)):
        raise GuardError("BARDO_PATCH_DUPLICATE_PATHS")

    if expected_path is not None:
        expected = expected_path.read_text(encoding="utf-8").splitlines()
        if not expected or sorted(accepted) != sorted(expected):
            raise GuardError("BARDO_CHANGED_FILES_DIFFER_FROM_VALIDATED_REPORT")

    report_path.write_text("\n".join(sorted(accepted)) + "\n", encoding="utf-8")
    print("Candidate paths accepted:")
    print("\n".join(sorted(accepted)))
    return accepted


def self_test() -> None:
    valid = b"diff --git a/docs/example.md b/docs/example.md\n"
    encoded = base64.b64encode(gzip.compress(valid)).decode("ascii")
    assert bounded_gzip_decompress(encoded) == valid

    exact_limit = b"x" * MAX_PATCH_BYTES
    exact_encoded = base64.b64encode(gzip.compress(exact_limit)).decode("ascii")
    assert bounded_gzip_decompress(exact_encoded) == exact_limit

    oversized = base64.b64encode(
        gzip.compress(b"x" * (MAX_PATCH_BYTES + 1))
    ).decode("ascii")
    try:
        bounded_gzip_decompress(oversized)
    except GuardError as exc:
        assert str(exc) == "BARDO_PATCH_SIZE_LIMIT_EXCEEDED"
    else:
        raise AssertionError("Oversized GZIP output was not rejected")

    for malformed in (
        base64.b64encode(gzip.compress(valid)[:-4]).decode("ascii"),
        base64.b64encode(
            gzip.compress(valid)[:-8] + bytes([gzip.compress(valid)[-8] ^ 0xFF]) + gzip.compress(valid)[-7:]
        ).decode("ascii"),
    ):
        try:
            bounded_gzip_decompress(malformed)
        except GuardError as exc:
            assert str(exc) == "BARDO_PATCH_GZIP_INVALID"
        else:
            raise AssertionError("Truncated or corrupt GZIP stream was not rejected")

    try:
        bounded_gzip_decompress("not-base64!")
    except GuardError as exc:
        assert str(exc) == "BARDO_PATCH_BASE64_INVALID"
    else:
        raise AssertionError("Invalid Base64 was not rejected")

    try:
        bounded_gzip_decompress("A" * (MAX_BASE64_CHARS + 1))
    except GuardError as exc:
        assert str(exc) == "BARDO_PATCH_ENCODED_SIZE_INVALID"
    else:
        raise AssertionError("Oversized Base64 input was not rejected")

    fixture = """
    private val protectedExact = setOf(
        "app/src/main/java/com/musicaia/desarrollo/Protected.kt"
    )
    private val protectedPrefixes = listOf(
        ".github/",
        "gradle/"
    )
    private val autonomousPrefixes = listOf(
        "app/src/main/java/",
        "docs/"
    )
    """
    exact = set(parse_kotlin_collection(fixture, "protectedExact", "setOf"))
    prefixes = tuple(parse_kotlin_collection(fixture, "protectedPrefixes", "listOf"))
    autonomous = tuple(parse_kotlin_collection(fixture, "autonomousPrefixes", "listOf"))
    assert path_violation(
        "app/src/main/java/com/musicaia/desarrollo/Protected.kt",
        exact, prefixes, autonomous
    ) == "BARDO_PROTECTED_PATH"
    assert path_violation(
        "nested/modo3mezclavozusuario.KT", exact, prefixes, autonomous
    ) == "BARDO_PROTECTED_MODO3"
    assert path_violation(
        "../docs/escape.md", exact, prefixes, autonomous
    ) == "BARDO_UNSAFE_PATH"
    assert path_violation(
        ".github/workflows/x.yml", exact, prefixes, autonomous
    ) == "BARDO_PROTECTED_PATH"
    assert path_violation(
        "docs/safe.md", exact, prefixes, autonomous
    ) is None

    real_policy = pathlib.Path(
        "app/src/main/java/com/musicaia/desarrollo/BardoProtectedPathPolicy.kt"
    )
    if real_policy.is_file():
        real_exact, real_prefixes, real_autonomous = load_authoritative_policy(real_policy)
        required_protected = (
            "app/src/main/java/com/musicaia/actualizaciones/BardoIncrementalUpdateManifest.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillWorkflowToolHandler.kt",
        )
        for path in required_protected:
            assert path in real_exact, f"Central policy unexpectedly omits {path}"
            assert path_violation(
                path, real_exact, real_prefixes, real_autonomous
            ) == "BARDO_PROTECTED_PATH"

    print("BARDO_WORKFLOW_GUARDS_POLICY_UNIT_TESTS: PASS")

    # Exercise the same git-diff path checker used by the candidate workflows.
    if real_policy.is_file():
        policy_absolute = real_policy.resolve()
        with tempfile.TemporaryDirectory(prefix="bardo-path-guard-") as temp_dir:
            old_cwd = pathlib.Path.cwd()
            try:
                os.chdir(temp_dir)
                subprocess.run(["git", "init", "--quiet"], check=True)
                subprocess.run(["git", "config", "user.name", "BARDO Guard Test"], check=True)
                subprocess.run(["git", "config", "user.email", "bardo-guard-test@example.invalid"], check=True)
                protected_path = pathlib.Path(
                    "app/src/main/java/com/musicaia/actualizaciones/"
                    "BardoIncrementalUpdateManifest.kt"
                )
                safe_path = pathlib.Path("docs/safe-candidate-note.md")
                protected_path.parent.mkdir(parents=True, exist_ok=True)
                safe_path.parent.mkdir(parents=True, exist_ok=True)
                protected_path.write_text("base\n", encoding="utf-8")
                safe_path.write_text("base\n", encoding="utf-8")
                subprocess.run(["git", "add", "--", str(protected_path), str(safe_path)], check=True)
                subprocess.run(["git", "commit", "--quiet", "-m", "guard test base"], check=True)

                safe_path.write_text("candidate update\n", encoding="utf-8")
                report_path = pathlib.Path(temp_dir) / "accepted-paths.txt"
                accepted = check_changed_paths(policy_absolute, report_path)
                assert accepted == [str(safe_path)]

                subprocess.run(["git", "add", "--", str(safe_path)], check=True)
                subprocess.run(["git", "commit", "--quiet", "-m", "safe candidate update"], check=True)
                protected_path.write_text("candidate must be rejected\n", encoding="utf-8")
                try:
                    check_changed_paths(policy_absolute, report_path)
                except GuardError as exc:
                    assert str(exc).startswith("BARDO_PROTECTED_PATH:")
                    assert "BardoIncrementalUpdateManifest.kt" in str(exc)
                else:
                    raise AssertionError(
                        "Candidate workflow path guard accepted a protected update manifest"
                    )
            finally:
                os.chdir(old_cwd)

    print("BARDO_WORKFLOW_GUARDS_SELF_TEST: PASS")


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
        return 0

    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    decode = subparsers.add_parser("decode")
    decode.add_argument("output", type=pathlib.Path)
    paths = subparsers.add_parser("check-paths")
    paths.add_argument("--policy", required=True, type=pathlib.Path)
    paths.add_argument("--report", required=True, type=pathlib.Path)
    paths.add_argument("--expected", type=pathlib.Path)
    args = parser.parse_args()

    try:
        if args.command == "decode":
            candidate = os.environ.get("CANDIDATE_ID", "")
            if not re.fullmatch(r"[0-9a-f]{32}", candidate):
                raise GuardError("BARDO_CANDIDATE_ID_INVALID")
            encoded = os.environ.get("PATCH_GZIP_B64", "")
            patch = bounded_gzip_decompress(encoded)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(patch)
            print(
                f"Candidate {candidate}; patch bytes={len(patch)}; "
                f"sha256={hashlib.sha256(patch).hexdigest()}"
            )
        elif args.command == "check-paths":
            check_changed_paths(args.policy, args.report, args.expected)
    except (GuardError, OSError, subprocess.CalledProcessError, UnicodeError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
