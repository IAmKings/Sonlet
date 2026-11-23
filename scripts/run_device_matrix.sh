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
# 长稳用例与评测放同一进程会触发系统重启,单独派发(见 docs/device_matrix.md §3.1)
ISOLATED=(com.sonlet.app.LongRunDictationStressTest)
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

  # 先跑剥离了长稳的全量,再单独跑长稳;两次独立进程,避免评测与长稳互相拖垮
  EXCLUDES=()
  for CLS in "${ISOLATED[@]}"; do EXCLUDES+=(-e notClass "$CLS"); done
  run_am() {
    "${ADB[@]}" shell am instrument -w -r "${@:2}" "$RUNNER" > "$1" 2>&1 &
    local pid=$! waited=0 idle=0 marks=0
    while kill -0 "$pid" 2>/dev/null && (( waited < TIMEOUT )); do
      sleep 5; waited=$((waited + 5))
      # 卡死判定:只有超时不够用(MIUI 拦后台启动时进程一声不响地挂着),
      # 用"测试用例标记不再增长"作为停滞信号
      local now_marks
      now_marks=$(grep -c "INSTRUMENTATION_STATUS: test=" "$1" 2>/dev/null || echo 0)
      if [[ $now_marks -gt $marks ]]; then marks=$now_marks; idle=0; else idle=$((idle + 5)); fi
      (( idle >= 150 )) && { kill "$pid" 2>/dev/null; STALLED=1; return 125; }
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      "${ADB[@]}" shell am force-stop com.sonlet.app >/dev/null 2>&1 || true
      return 124
    fi
    wait "$pid" 2>/dev/null || true
    return 0
  }

  TIMED_OUT=0
  STALLED=0
  run_am "$LOG" "${EXCLUDES[@]}" || TIMED_OUT=1
  for CLS in "${ISOLATED[@]}"; do
    run_am "$LOG.$CLS" -e class "$CLS" || TIMED_OUT=1
    cat "$LOG.$CLS" >> "$LOG"
  done
  TIME=$(( $(date +%s) - START ))

  # 设备侧拦截的证据:后台启动被拒、系统崩溃、ANR 抢占焦点、或干脆超时
  REASON=-
  if grep -q "INSTRUMENTATION_ABORTED: System has crashed" "$LOG" 2>/dev/null; then
    REASON=system_crash
  elif "${ADB[@]}" logcat -d 2>/dev/null | grep -q "Abort background activity starts"; then
    REASON=background_start_denied
  elif (( STALLED )); then
    REASON=stalled
  elif (( TIMED_OUT )); then
    REASON=timeout
  fi

  if [[ $REASON != - ]]; then
    row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$UID_" blocked "$REASON" - - "$TIME"
    continue
  fi

  TESTS=$(sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' "$LOG" | awk '{s+=$1} END{print s+0}')
  FAIL=$(sed -n 's/.*Failures: \([0-9]*\).*/\1/p' "$LOG" | awk '{s+=$1} END{print s+0}')
  if (( FAIL == 0 )) && [[ $TESTS -gt 0 ]]; then RESULT=pass; else RESULT=fail; fi
  row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$UID_" "$RESULT" - "${TESTS:-0}" "${FAIL:-0}" "$TIME"
done

column -t -s $'\t' "$SUMMARY"
