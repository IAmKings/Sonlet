#!/usr/bin/env python3
"""Verify the Sonlet release candidate against the planned version and shipped assets."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
# v0.1.0 的公开 APK 证书与 versionCode，用于验证升级路径。
PUBLISHED_SIGNER = "22fa1ab87ca46f2d6003b95983b9156b7e8b35d3810d91a6af4010d23339049b"
PUBLISHED_VERSION_CODE = 100
NATIVE_LIBRARIES = ("libonnxruntime.so", "libsherpa-onnx-jni.so")
LICENSE_ASSETS = (
    "NOTICE.txt",
    "funasr-LICENSE",
    "onnxruntime-LICENSE",
    "sherpa-onnx-LICENSE",
    "silero-vad-LICENSE",
)


def version_code(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)", tag)
    if not match:
        raise ValueError("版本须为 vMAJOR.MINOR.PATCH，次版本与修订号限 0～99")
    major, minor, patch = map(int, match.groups())
    code = major * 10000 + minor * 100 + patch
    if not PUBLISHED_VERSION_CODE < code <= 2_100_000_000:
        raise ValueError("versionCode 须大于已发布的 100 且不超过 2100000000")
    return code


def sdk_output(sdk, tool, *args):
    return subprocess.check_output([sdk / tool, *args], text=True)


def stream_sha256(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def verify_native_alignment(stream, name):
    header = struct.unpack("<16sHHIQQQIHHHHHH", stream.read(64))
    ident, _, machine = header[:3]
    offset, entry_size, entries = header[5], header[9], header[10]
    if not ident.startswith(b"\x7fELF\x02\x01") or machine != 183 or entry_size != 56:
        raise ValueError(f"原生库不是 arm64 ELF：{name}")

    stream.seek(offset)
    load_segments = 0
    page_size = 16384
    for _ in range(entries):
        kind, _, file_offset, vaddr, _, _, _, alignment = struct.unpack("<IIQQQQQQ", stream.read(entry_size))
        if kind == 1:  # PT_LOAD
            load_segments += 1
            if alignment < page_size or (vaddr - file_offset) % page_size:
                raise ValueError(f"原生库 LOAD 段未按 16 KB 对齐：{name}")
    if not load_segments:
        raise ValueError(f"原生库缺少 LOAD 段：{name}")


def verify_apk(apk, sdk, tag, code):
    certificate = sdk_output(sdk, "apksigner", "verify", "--print-certs", apk)
    signers = set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", certificate))
    if signers != {PUBLISHED_SIGNER}:
        raise ValueError("候选包签名证书与已发布版本不一致")
    sdk_output(sdk, "zipalign", "-c", "-P", "16", "4", apk)
    metadata = sdk_output(sdk, "aapt", "dump", "badging", apk)
    if not metadata.startswith(
        f"package: name='com.sonlet.app' versionCode='{code}' versionName='{tag[1:]}' "
    ):
        raise ValueError("APK 包名、versionCode 或 versionName 与候选版本不匹配")
    permissions = sdk_output(sdk, "aapt", "dump", "permissions", apk)
    if "android.permission.INTERNET" in permissions:
        raise ValueError("离线 APK 不应申请 INTERNET 权限")

    manifest = json.loads((ROOT / "models/manifest.json").read_text(encoding="utf-8"))
    vad = manifest["vad_model"]
    assets = {
        "assets/" + manifest["model_file"]: manifest["sha256_model"],
        "assets/" + manifest["tokens_file"]: manifest["sha256_tokens"],
        "assets/" + vad["file"]: vad["sha256"],
    }
    native_dir = ROOT / "app/src/main/jniLibs/arm64-v8a"
    for name in NATIVE_LIBRARIES:
        with (native_dir / name).open("rb") as stream:
            assets[f"lib/arm64-v8a/{name}"] = stream_sha256(stream)
    for name in LICENSE_ASSETS:
        license_file = ROOT / "docs/licenses" / name
        assets[f"assets/{name}"] = hashlib.sha256(license_file.read_bytes()).hexdigest()

    with ZipFile(apk) as package:
        native_paths = {name for name in package.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if any(not name.startswith("lib/arm64-v8a/") for name in native_paths):
            raise ValueError("APK 包含 arm64-v8a 以外的原生库")
        for name in sorted(native_paths):
            with package.open(name) as stream:
                verify_native_alignment(stream, name)
        for name, expected in assets.items():
            with package.open(name) as stream:
                if stream_sha256(stream) != expected:
                    raise ValueError(f"APK 校验失败：{name}")
    with apk.open("rb") as stream:
        print(f"候选包 {tag} (versionCode {code}) SHA-256 {stream_sha256(stream)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--build-tools", type=Path)
    args = parser.parse_args()
    try:
        code = version_code(args.tag)
        if args.apk is None:
            print(code)
        elif args.build_tools is None:
            parser.error("校验 APK 时须提供 --build-tools")
        else:
            verify_apk(args.apk, args.build_tools, args.tag, code)
    except (ValueError, KeyError, OSError, struct.error, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
