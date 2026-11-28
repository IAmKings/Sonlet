#!/usr/bin/env python3
"""Verify the Sonlet release candidate against the planned version and shipped assets."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
# v0.1.0 起沿用的公开签名证书指纹，用于验证升级路径。
# 已登记的应用权限;新增权限必须同时更新本清单与 docs/release_checklist.md
ALLOWED_PERMISSIONS = {
    "android.permission.RECORD_AUDIO",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "com.sonlet.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}

PUBLISHED_SIGNER = "22fa1ab87ca46f2d6003b95983b9156b7e8b35d3810d91a6af4010d23339049b"
MAX_VERSION_CODE = 2_100_000_000
NATIVE_LIBRARIES = ("libonnxruntime.so", "libsherpa-onnx-jni.so")
LICENSE_ASSETS = (
    "NOTICE.txt",
    "funasr-LICENSE",
    "onnxruntime-LICENSE",
    "sherpa-onnx-LICENSE",
    "silero-vad-LICENSE",
    "sonlet-LICENSE",
)


def version_code(tag, published=0):
    match = re.fullmatch(r"v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)", tag)
    if not match:
        raise ValueError("版本须为 vMAJOR.MINOR.PATCH，次版本与修订号限 0～99")
    major, minor, patch = map(int, match.groups())
    code = major * 10000 + minor * 100 + patch
    if not published < code <= MAX_VERSION_CODE:
        raise ValueError(f"versionCode 须大于已发布的 {published} 且不超过 {MAX_VERSION_CODE}")
    return code


def max_version_code(tags):
    """从已公开发布的 tag 集合里取最大 versionCode；无法解析的 tag 跳过。"""
    best = 0
    for tag in tags:
        try:
            best = max(best, version_code(tag))
        except ValueError:
            continue
    return best


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
    # 0.1.7 起接入应用内更新:INTERNET 是唯一为"检查更新"申请的网络权限,必须存在;
    # 同时用白名单兜住依赖漂移带来的额外权限(WorkManager/ReleaseKite 会合并一堆)。
    required = {"android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"}
    missing = sorted(required - set(re.findall(r"android\.permission\.[A-Z_]+", permissions)))
    if missing:
        raise ValueError(f"APK 缺少更新功能所需权限: {missing}")
    extra = sorted(
        set(re.findall(r"android\.permission\.[A-Z_]+", permissions)) - ALLOWED_PERMISSIONS
    )
    if extra:
        raise ValueError(f"APK 申请了未登记的新权限: {extra}")

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
    parser.add_argument("tag", nargs="?")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument(
        "--published-version-code",
        type=int,
        default=0,
        help="已公开的最大 versionCode；新版本必须大于它，历史包复核时传 0",
    )
    parser.add_argument(
        "--list-published",
        action="store_true",
        help="从标准输入读取已发布 tag（每行一个），输出其中最大的 versionCode",
    )
    args = parser.parse_args()
    try:
        if args.list_published:
            print(max_version_code(line.strip() for line in sys.stdin if line.strip()))
            return
        if not args.tag:
            parser.error("缺少版本标签")
        code = version_code(args.tag, args.published_version_code)
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
