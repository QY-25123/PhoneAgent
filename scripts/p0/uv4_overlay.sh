#!/bin/bash
# UV-4：悬浮窗（TYPE_APPLICATION_OVERLAY + 不可聚焦）
#  验证：显示、拖动、吸附边缘；显示/拖动/点击/点停止 期间不改变 top focused display，不收起/抢走用户键盘、输入连接，
#  用户仍能在主屏软键盘上打字；虚拟屏上有动作时同样不受影响。
# 前提：已授予悬浮窗权限：adb shell appops set com.android.phoneagent SYSTEM_ALERT_WINDOW allow
source "$(dirname "$0")/lib.sh"
CAL=com.google.android.calendar

ime_state() {
  local d w; d=$(adb shell dumpsys input_method); w=$(adb shell dumpsys window)
  echo "shown=$(echo "$d" | grep -m1 -oE 'mInputShown=[a-z]+' | cut -d= -f2)"
  echo "focus=$(echo "$d" | grep -m1 -oE 'mCurFocusedWindow=[^ ]+' | cut -d= -f2)"
  echo "client=$(echo "$d" | grep -m1 -oE 'mCurClient=ClientState\{[^ ]+ mUid=[0-9]+ mPid=[0-9]+' | sed 's/.*mUid/mUid/')"
  echo "topdisp=$(echo "$w" | grep -m1 -oE 'mTopFocusedDisplayId=[0-9]+' | cut -d= -f2)"
}
val() { echo "$1" | grep "^$2=" | cut -d= -f2-; }
main_text() { adb logcat -c; pa maintext; sleep 0.7; adb logcat -d -s PA | grep -m1 'MAIN_FIELD=' | sed 's/.*MAIN_FIELD=//' | tr -d '\r'; }
ov_dump() { adb shell dumpsys window windows | grep -A45 "PhoneAgent Overlay" | head -60; }
ov_frame() { ov_dump | grep -m1 -oE "frame=\[[0-9,]+\]\[[0-9,]+\]" | head -1; }   # frame=[l,t][r,b]
ov_ready() { ov_dump | grep -m1 -oE "isReadyForDisplay\(\)=[a-z]+" | cut -d= -f2; }
overlay() { adb shell "am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd overlay --es op $1 --es t '${2:-执行中}'" >/dev/null; }

ov_pt() { # ov_pt body|stop|left|right -> "x y"：悬浮窗上的点（取自当前 frame）
  ov_frame | python3 -I -c '
import sys,re
l,t,r,b=map(int,re.findall(r"\d+",sys.stdin.read())); cy=(t+b)//2; w=sys.argv[1]
print({"body":(l+(r-l)//3,cy),"stop":(r-45,cy),"left":(l+60,cy),"right":(r-45,cy)}[w][0], cy)' "$1"
}
main_field_xy() { # 主屏输入框中心（界面树偶尔没填充，重试）
  for _ in 1 2 3 4; do
    tree 0 >/dev/null
    local xy; xy=$(python3 -I - "$OUT/tree.txt" <<'PY'
import sys,re
for l in open(sys.argv[1]):
    if "EditText" in l and "你好" in l:
        a=list(map(int,re.search(r"\[(\d+) (\d+) (\d+) (\d+)\]",l).groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
    [ -n "$xy" ] && { echo "$xy"; return; }
    sleep 1
  done
}

same_state() { # same_state 名称：对比基线
  local s; s=$(ime_state)
  local rc=1 desc; desc=$(echo $s | tr '\n' ' ')
  [ "$(val "$s" shown)" = true ] && [ "$(val "$s" focus)" = "$B_FOCUS" ] && [ "$(val "$s" client)" = "$B_CLIENT" ] && [ "$(val "$s" topdisp)" = 0 ] && rc=0
  check "$1：键盘仍显示、焦点窗口/输入连接不变、top display=0（${desc}）" $rc
}
typing_works() {
  local b a; b=$(main_text); adb shell input tap 58 1716; sleep 1; a=$(main_text); adb shell input tap 994 2028; sleep 1
  [ "${#a}" -eq $(( ${#b} + 1 )) ]; check "$1：主屏软键盘仍能打字（${b} → ${a}）" $?
}

boot; pa launch --es c $CAL; sleep 7
# 主屏输入框弹出键盘
read -r CX CY < <(main_field_xy)
for _ in 1 2 3; do adb shell input tap "$CX" "$CY"; sleep 2; [ "$(val "$(ime_state)" shown)" = true ] && break; done
B=$(ime_state); B_FOCUS=$(val "$B" focus); B_CLIENT=$(val "$B" client)
echo "基线：$(echo $B | tr '\n' ' ')"
[ "$(val "$B" shown)" = true ]; check "准备：主屏键盘已弹出" $?

echo "== 显示"
overlay on "第 3 步 · 点击日历"; sleep 2
echo "  窗口：$(ov_dump | grep -m1 -oE 'fl=[A-Z_ ]+' | head -1)  frame=$(ov_frame) ready=$(ov_ready)"
[ "$(ov_ready)" = true ]; check "悬浮窗已显示在屏幕上（isReadyForDisplay）" $?
ov_dump | grep -m1 "fl=" | grep -q "NOT_FOCUSABLE"; check "窗口带 FLAG_NOT_FOCUSABLE" $?
same_state "显示悬浮窗后"; typing_works "显示悬浮窗后"

echo "== 拖动并吸附"
F0=$(ov_frame); echo "  拖动前 $F0"
read -r PX PY < <(ov_pt body); echo "  从 $PX,$PY 拖到 800,1200"; adb logcat -c; adb shell input swipe "$PX" "$PY" 800 1200 500; sleep 1.5
F1=$(ov_frame); echo "  拖动后 $F1"; adb logcat -d -s PA | grep OVERLAY_SNAP | sed 's/.*PA *: //'
[ "$F0" != "$F1" ]; check "悬浮窗被拖动（位置变化）" $?
R=$(echo "$F1" | grep -oE "\]\[[0-9]+" | grep -oE "[0-9]+" | head -1)
[ "$R" = 1080 ]; check "松手后吸附到右边缘（右边界=1080，实际 $R）" $?
same_state "拖动后"; typing_works "拖动后"
read -r PX PY < <(ov_pt body); adb shell input swipe "$PX" "$PY" 150 900 500; sleep 1.5
F2=$(ov_frame); L=$(echo "$F2" | grep -oE "frame=\[[0-9]+" | grep -oE "[0-9]+")
echo "  再拖到左侧：$F2"; [ "$L" = 0 ]; check "再拖到左侧后吸附到左边缘（左边界=0，实际 $L）" $?

echo "== 点击悬浮窗主体"
read -r PX PY < <(ov_pt body); adb logcat -c; adb shell input tap "$PX" "$PY"; sleep 1.5
adb logcat -d -s PA | grep -q OVERLAY_CLICK; check "点击小窗触发点击回调（日志 OVERLAY_CLICK）" $?
same_state "点击小窗后"; typing_works "点击小窗后"

echo "== 虚拟屏上有动作时（小窗在、主屏键盘在）"
pa swipe; sleep 2.5; same_state "虚拟屏滑动后"
[ "$(ov_ready)" = true ]; check "虚拟屏有动作后悬浮窗仍显示" $?
typing_works "虚拟屏滑动后"

echo "== 点小窗上的停止按钮"
read -r PX PY < <(ov_pt stop); adb logcat -c; adb shell input tap "$PX" "$PY"; sleep 3
adb logcat -d -s PA | grep -q OVERLAY_STOP; check "点停止触发 Agent.stop（日志 OVERLAY_STOP）" $?
echo "  $(plog 3 | tr '\n' '|')"

overlay off; sleep 1; pa stop; sleep 1
echo; [ $FAILS = 0 ] && echo "UV-4 结果: PASS" || echo "UV-4 结果: FAIL($FAILS)"; exit $FAILS
