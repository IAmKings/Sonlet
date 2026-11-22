#!/usr/bin/env bash
# 对每台已连接设备跑一遍仪器测试，汇总成一张表。
# 用法: scripts/run_device_matrix.sh [输出目录] [超时秒数]
# 前提: 设备已解锁；MIUI 需授予「后台弹出界面」；已构建 debug 与 androidTest APK。
set -uo pipefail

OUT=${1:-build/device-matrix}
TIMEOUT=${2:-1800}
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=com.sonlet.app.test/androidx.test.runner.AndroidJUnitRunner
SUMMARY="$OUT/summary.tsv"

mkdir -p "$OUT"
printf 'serial\tmodel\tandroid\tabi\tapp_uid\tresult\treason\ttests\tfail\ttime_s\n' > "$SUMMARY"

for f in "$APP_APK" "$TEST_APK"; do
  [[ -f $f ]] || { echo "缺少 $f，先跑 ./gradlew assembleDebug assembleDebugAndroidTest" >&2; exit 1; }
done

mapfile -t DEVICES < <(adb devices | awk '$2=="device"{print $1}')
[[ ${#DEVICES[@]} -gt 0 ]] || { echo "没有已连接设备" >&2; exit 1; }

row() { printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >> "$SUMMARY"; }

for SERIAL in "${DEVICES[@]}"; do
  ADB=(adb -s "$SERIAL")
  MODEL=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')
  ANDROID=$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')
  ABI=$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')

  "${ADB[@]}" uninstall com.sonlet.app >/dev/null 2>&1 || true
  "${ADB[@]}" install -r "$APP_APK" >/dev/null || { row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" - fail install_error - - 0; continue; }
  "${ADB[@]}" install -r "$TEST_APK" >/dev/null || { row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" - fail test_apk_error - - 0; continue; }
  UID_=$("${ADB[@]}" shell dumpsys package com.sonlet.app | awk -F= '/appId=/{print $2; exit}' | tr -d '\r')

  LOG="$OUT/$SERIAL.log"
  "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
  START=$(date +%s)
  "${ADB[@]}" shell am instrument -w -r "$RUNNER" > "$LOG" 2>&1 &
  PID=$!
  WAITED=0
  while kill -0 "$PID" 2>/dev/null && (( WAITED < TIMEOUT )); do sleep 5; WAITED=$((WAITED + 5)); done
  TIMED_OUT=0
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null
    "${ADB[@]}" shell am force-stop com.sonlet.app >/dev/null 2>&1 || true
    TIMED_OUT=1
  fi
  TIME=$(( $(date +%s) - START ))

  # 设备侧拦截的证据:后台启动被拒、系统崩溃、ANR 抢占焦点、或干脆超时
  REASON=-
  if grep -q "INSTRUMENTATION_ABORTED: System has crashed" "$LOG" 2>/dev/null; then
    REASON=system_crash
  elif "${ADB[@]}" logcat -d 2>/dev/null | grep -q "Abort background activity starts"; then
    REASON=background_start_denied
  elif (( TIMED_OUT )); then
    REASON=timeout
  fi

  if [[ $REASON != - ]]; then
    row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$UID_" blocked "$REASON" - - "$TIME"
    continue
  fi

  TESTS=$(sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' "$LOG" | tail -1)
  FAIL=$(sed -n 's/.*Failures: \([0-9]*\).*/\1/p' "$LOG" | tail -1)
  if grep -q "^OK (" "$LOG"; then RESULT=pass; else RESULT=fail; fi
  row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$UID_" "$RESULT" - "${TESTS:-0}" "${FAIL:-0}" "$TIME"
done

column -t -s $'\t' "$SUMMARY"
