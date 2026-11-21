#!/usr/bin/env bash
# 0.1.2 -> 0.1.3 升级路径验证的机械部分:快照、铺基线、覆盖安装。
# 需要人工确认的环节(造数据、看界面)由 docs/upgrade_verification_plan.md 描述。
set -euo pipefail

PKG="com.sonlet.app"

usage() {
  cat <<'USAGE'
用法:
  verify_upgrade.sh download  <tag> [dir]         从 GitHub Release 取包并按平台 digest 校验
  verify_upgrade.sh snapshot                      记录当前安装状态(versionCode/dataDir/appId/权限/app ops)
  verify_upgrade.sh baseline <apk> --yes          卸载后安装基线包(会清空应用数据)
  verify_upgrade.sh upgrade  <apk>                覆盖安装新包并比对快照
USAGE
}

adb_cmd() { adb shell "$@"; }

snapshot() {
  local dump ops
  dump=$(adb_cmd dumpsys package "$PKG")
  ops=$(adb_cmd dumpsys appops | awk '/Package com.sonlet.app:/{f=1} f{print; if (++n>=8) exit}')
  printf 'versionCode=%s\n' "$(printf '%s\n' "$dump" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)"
  printf 'versionName=%s\n' "$(printf '%s\n' "$dump" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)"
  printf 'dataDir=%s\n' "$(printf '%s\n' "$dump" | awk -F= '/dataDir=/{print $2; exit}')"
  printf 'appId=%s\n' "$(printf '%s\n' "$dump" | awk -F= '/appId=/{print $2; exit}')"
  printf 'record_audio=%s\n' "$(printf '%s\n' "$dump" | grep -m1 'RECORD_AUDIO: granted=' | sed 's/^ *//' || true)"
  echo '--- appops ---'
  printf '%s\n' "$ops"
}

case "${1:-}" in
  download)
    tag="${2:?缺少版本 tag,如 v0.1.3}"
    dir="${3:-/tmp}"
    asset="Sonlet-${tag#v}-arm64-v8a.apk"
    gh release download "$tag" -p "$asset" -D "$dir" --clobber
    want=$(gh release view "$tag" --json assets --jq ".assets[] | select(.name==\"$asset\") | .digest" | sed 's/^sha256://')
    got=$(shasum -a 256 "$dir/$asset" | awk '{print $1}')
    echo "asset=$dir/$asset"
    echo "digest=$got"
    if [ "$want" = "$got" ]; then
      echo "摘要一致"
    else
      echo "摘要不一致: 平台 $want" >&2
      exit 1
    fi
    ;;
  snapshot)
    snapshot
    ;;
  baseline)
    apk="${2:?缺少 APK 路径}"
    [ "${3:-}" = "--yes" ] || { echo "会清空应用数据,确认请加 --yes" >&2; exit 2; }
    adb uninstall "$PKG" >/dev/null 2>&1 || true
    adb install "$apk" | tail -1
    snapshot
    ;;
  upgrade)
    apk="${2:?缺少 APK 路径}"
    before=$(mktemp); after=$(mktemp)
    snapshot > "$before"
    echo "--- install -r ---"
    adb install -r "$apk" | tail -1
    snapshot > "$after"
    echo "--- diff(before -> after) ---"
    diff -u "$before" "$after" || true
    echo "--- 冷启动 ---"
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$PKG/.MainActivity" | grep -E "TotalTime|WaitTime" || true
    echo "--- 崩溃检查 ---"
    adb logcat -d -t 400 | grep -E "FATAL|AndroidRuntime" | head -5 || echo "无崩溃记录"
    rm -f "$before" "$after"
    ;;
  *)
    usage; exit 1
    ;;
esac
