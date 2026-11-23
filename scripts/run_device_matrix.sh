#!/usr/bin/env bash
# 对每台已连接设备跑一遍仪器测试,汇总成一张表。
#
# 用法: scripts/run_device_matrix.sh [输出目录] [超时秒数] [--in-place]
#   --in-place  不卸载重装,直接测当前安装的包(保留 MIUI 按 UID 授的后台启动权限)
#
# 前提: 设备已解锁;MIUI 需授予「后台弹出界面」;已构建 debug 与 androidTest APK。
set -uo pipefail

OUT=${1:-build/device-matrix}
TIMEOUT=${2:-1800}
IN_PLACE=0
for arg in "$@"; do [[ $arg == --in-place ]] && IN_PLACE=1; done

APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=com.sonlet.app.test/androidx.test.runner.AndroidJUnitRunner
APP=com.sonlet.app
TEST_PKG=com.sonlet.app.test

# 长稳与评测放同一进程会触发系统重启,单独派发(见 docs/device_matrix.md §3.1)
ISOLATED=(com.sonlet.app.LongRunDictationStressTest)
# 探针:能否起 Activity。MIUI 拦后台启动时测试会一声不响地挂住,先花 90 秒问清楚
PROBE_CLASS=com.sonlet.app.feature.dictation.VoiceActionRowGestureTest
PROBE_TEST=holdButtonStaysComposedDuringStarting

SUMMARY="$OUT/summary.tsv"
mkdir -p "$OUT"
printf 'serial\tmodel\tandroid\tabi\texecution\tapp_uid\tresult\treason\ttests\tfail\ttime_s\n' > "$SUMMARY"

for f in "$APP_APK" "$TEST_APK"; do
  [[ -f $f ]] || { echo "缺少 $f，先跑 ./gradlew assembleDebug assembleDebugAndroidTest" >&2; exit 1; }
done

mapfile -t DEVICES < <(adb devices | awk '$2=="device"{print $1}')
[[ ${#DEVICES[@]} -gt 0 ]] || { echo "没有已连接设备" >&2; exit 1; }

row() { printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >> "$SUMMARY"; }

# 跑一次 am instrument;每 5 秒看一次用例标记是否还在增长,停滞或超时就中止。
# 返回 0=跑完, 124=墙钟超时, 125=停滞
run_am() {
  local log=$1; shift
  # 停滞阈值;0=不判停滞(单个长用例整场只报一次 test 标记,靠标记判活会误杀)
  local stall_idle=${STALL_IDLE:-150}
  : > "$log"
  adb -s "$SERIAL" shell am instrument -w -r "$@" "$RUNNER" > "$log" 2>&1 &
  local pid=$! waited=0 idle=0 marks=0 now
  while kill -0 "$pid" 2>/dev/null && (( waited < TIMEOUT )); do
    sleep 5; waited=$((waited + 5))
    now=$(grep -c "INSTRUMENTATION_STATUS: test=" "$log" 2>/dev/null || true)
    now=${now:-0}
    if (( now > marks )); then marks=$now; idle=0; else idle=$((idle + 5)); fi
    if (( stall_idle > 0 && idle >= stall_idle )); then
      kill "$pid" 2>/dev/null
      adb -s "$SERIAL" shell am force-stop "$APP" >/dev/null 2>&1 || true
      return 125
    fi
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    adb -s "$SERIAL" shell am force-stop "$APP" >/dev/null 2>&1 || true
    return 124
  fi
  wait "$pid" 2>/dev/null || true
  return 0
}

for SERIAL in "${DEVICES[@]}"; do
  MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
  ANDROID=$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')
  ABI=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
  EXECUTION=$([[ $IN_PLACE == 1 ]] && echo in_place || echo fresh_install)

  if [[ $IN_PLACE == 1 ]]; then
    # 就地测:只确认包在,绝不动安装状态——重装会换 appId,MIUI 的权限随之失效
    # 先落到变量再 grep:pipefail 下 grep -q 提前退出会让 tr 收 SIGPIPE,整条管道判失败
    LISTED=$(adb -s "$SERIAL" shell pm list packages | tr -d '\r')
    for pkg in "$APP" "$TEST_PKG"; do
      if ! grep -qx "package:$pkg" <<< "$LISTED"; then
        row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$EXECUTION" - fail missing_$pkg - - 0
        continue 2
      fi
    done
  else
    adb -s "$SERIAL" uninstall "$APP" >/dev/null 2>&1 || true
    adb -s "$SERIAL" install -r "$APP_APK" >/dev/null 2>&1 || { row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$EXECUTION" - fail install_error - - 0; continue; }
    adb -s "$SERIAL" install -r "$TEST_APK" >/dev/null 2>&1 || { row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$EXECUTION" - fail test_apk_error - - 0; continue; }
  fi

  UID_=$(adb -s "$SERIAL" shell dumpsys package "$APP" | awk -F= '/appId=/{print $2; exit}' | tr -d '\r')
  LOG="$OUT/$SERIAL.log"
  START=$(date +%s)
  adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true

  # 探针:Activity 起不来时后面全白跑,先问一个用例
  if ! run_am "$LOG.probe" -e class "$PROBE_CLASS#$PROBE_TEST"; then
    row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$EXECUTION" "$UID_" blocked background_start_denied - - "$(( $(date +%s) - START ))"
    continue
  fi

  RESULT_CODE=0
  EXCLUDES=()
  for CLS in "${ISOLATED[@]}"; do EXCLUDES+=(-e notClass "$CLS"); done
  run_am "$LOG" "${EXCLUDES[@]}" || RESULT_CODE=$?
  for CLS in "${ISOLATED[@]}"; do
    STALL_IDLE=0 run_am "$LOG.$CLS" -e class "$CLS" || RESULT_CODE=$?
    cat "$LOG.$CLS" >> "$LOG"
  done
  TIME=$(( $(date +%s) - START ))

  REASON=-
  if grep -q "INSTRUMENTATION_ABORTED: System has crashed" "$LOG" 2>/dev/null; then
    REASON=system_crash
  elif (( RESULT_CODE == 125 )); then
    REASON=stalled
  elif (( RESULT_CODE == 124 )); then
    REASON=timeout
  fi

  if [[ $REASON != - ]]; then
    row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$EXECUTION" "$UID_" blocked "$REASON" - - "$TIME"
    continue
  fi

  # 全绿时 JUnit 只打 "OK (N tests)",不打印 "Tests run: N, Failures: M"
  OK_TESTS=$(sed -n 's/^OK (\([0-9]*\) test.*/\1/p' "$LOG" | awk '{s+=$1} END{print s+0}')
  RAN=$(sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' "$LOG" | awk '{s+=$1} END{print s+0}')
  TESTS=$(( OK_TESTS + RAN ))
  FAIL=$(sed -n 's/.*Failures: \([0-9]*\).*/\1/p' "$LOG" | awk '{s+=$1} END{print s+0}')
  if (( FAIL == 0 )) && [[ $TESTS -gt 0 ]]; then RESULT=pass; else RESULT=fail; fi
  row "$SERIAL" "$MODEL" "$ANDROID" "$ABI" "$EXECUTION" "$UID_" "$RESULT" - "$TESTS" "$FAIL" "$TIME"
done

column -t -s $'\t' "$SUMMARY"
