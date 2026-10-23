#!/bin/bash
# UserActivityGuard 验收测试。
#   a) 主屏键盘弹出期间，agent 动作被暂停，用户输入始终进入主屏输入框
#   b) 键盘收起 2 秒后，agent 自动继续
#   c) agent 动作完成后焦点回到主屏；此时在主屏模拟返回键（不带 displayId，模拟用户手势），作用在主屏而不是虚拟屏
# 前提：debug 版已安装、Shizuku 已运行并已授权。退出码：全部通过为 0。

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
PKG=com.android.phoneagent
FAIL=0
RES=()
pa() { adb shell am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd "$@" >/dev/null; }
mark() { adb shell log -t PA "$1"; }   # 往 logcat 打标记，和 agent 日志用同一个时钟

wait_dump() { for _ in $(seq 30); do sleep 0.5; adb logcat -d -s PA | grep -q "界面树已输出" && return; done; }
dump_display() { adb logcat -c; pa dump --ei display "$1"; wait_dump; adb logcat -d -s PA; }   # $1=display id（省略用虚拟屏需自己传）
vdisplay() { adb shell dumpsys display | grep -oE "^  Display [0-9]+:" | grep -v " 0:" | grep -oE "[0-9]+" | head -1; }

main_text() { # 主屏输入框当前内容（由 debug 广播让 App 直接报告，比读界面树可靠）
  adb logcat -c; pa maintext; sleep 0.7; adb logcat -d -s PA | grep -m1 'MAIN_FIELD=' | sed 's/.*MAIN_FIELD=//' | tr -d '\r'
}

main_field() { # text<TAB>cx<TAB>cy，取含“你好”的主屏 EditText
  local out
  for _ in 1 2 3 4; do
    out=$(dump_display 0 | python3 -I -c '
import sys,re
for l in sys.stdin:
    if "EditText" in l and "你好" in l:
        m=re.search(r"\[(\d+) (\d+) (\d+) (\d+)\]",l); t=re.search(r"text=\"(.*?)\"( |$)",l)
        a=list(map(int,m.groups())); print("%s\t%d\t%d"%(t.group(1),(a[0]+a[2])//2,(a[1]+a[3])//2)); break
')
    [ -n "$out" ] && { echo "$out"; return; }
    sleep 1
  done
}
ime_state() {
  local d w; d=$(adb shell dumpsys input_method); w=$(adb shell dumpsys window)
  echo "shown=$(echo "$d" | grep -m1 -oE 'mInputShown=[a-z]+' | cut -d= -f2)"
  echo "focus=$(echo "$d" | grep -m1 -oE 'mCurFocusedWindow=[^ ]+' | cut -d= -f2)"
  echo "client=$(echo "$d" | grep -m1 -oE 'mCurClient=ClientState\{[^ ]+ mUid=[0-9]+ mPid=[0-9]+' | sed 's/.*mUid/mUid/')"
  echo "topdisp=$(echo "$w" | grep -m1 -oE 'mTopFocusedDisplayId=[0-9]+' | cut -d= -f2)"
}
val() { echo "$1" | grep "^$2=" | cut -d= -f2-; }
main_alive() { adb shell dumpsys activity activities | awk '/^  Display: mDisplayId=0 /{f=1;next} /^  Display: mDisplayId=/{f=0} f' | grep -q "A=10195:com.android.phoneagent.*visible=true" && echo yes || echo no; }

record() { # $1=名称 $2=PASS|FAIL $3=详情
  RES+=("| $1 | $2 | $3 |"); [ "$2" = PASS ] || FAIL=1
}

# ---------- 准备 ----------
adb shell svc power stayon true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
pa stop; sleep 1; adb shell am force-stop $PKG; sleep 1
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
pa config --ez pause true $CFG   # 安全开关默认关，这里测它所以打开；CFG 可追加其他实验开关
pa connect; sleep 4; pa display; sleep 3; pa launch; sleep 4
VD=$(vdisplay)
read -r T0 CX CY < <(main_field)
for _ in 1 2 3; do
  adb shell input tap "$CX" "$CY"; sleep 2
  [ "$(val "$(ime_state)" shown)" = true ] && break
done
BASE=$(ime_state); B_FOCUS=$(val "$BASE" focus); B_CLIENT=$(val "$BASE" client)
echo "虚拟屏 display=$VD  主屏输入框初始文字=「${T0}」 基线 focus=$B_FOCUS client=$B_CLIENT"
[ "$(val "$BASE" shown)" = true ] && [ "$(val "$BASE" topdisp)" = 0 ] || { echo "准备失败：主屏键盘没弹出"; exit 2; }

# ---------- a) 键盘弹出期间 agent 动作被暂停 ----------
adb logcat -c
pa tap --ei x 540 --ei y 540          # 点设置搜索框（若被执行会进入搜索页并抢走焦点）
pa swipe                              # 排在点击后面的第二个动作（同样应被暂停）
A_OK=1; A_DETAIL=""
EXPECT="$T0"
for i in 1 2 3; do
  sleep 1.5
  adb shell input tap 58 1716         # 主屏软键盘点 q
  sleep 1
  EXPECT="${EXPECT}q"
  S=$(ime_state)
  [ "$(val "$S" shown)" = true ] && [ "$(val "$S" focus)" = "$B_FOCUS" ] && [ "$(val "$S" client)" = "$B_CLIENT" ] && [ "$(val "$S" topdisp)" = 0 ] \
    || { A_OK=0; A_DETAIL="$A_DETAIL 第${i}次打字时状态变化($(echo $S | tr '\n' ' '))"; }
done
LOG=$(adb logcat -d -s PA)   # 先取日志：main_field/dump_display 会清 logcat
GOT=$(main_text)
[ "$GOT" = "$EXPECT" ] || { A_OK=0; A_DETAIL="$A_DETAIL 主屏输入框内容「${GOT}」≠期望「${EXPECT}」"; }
echo "$LOG" | grep -q "GUARD 用户正在输入，暂停：点击" || { A_OK=0; A_DETAIL="$A_DETAIL 日志里没有暂停记录"; }
echo "$LOG" | grep -qE "PA +: 点击 \(" && { A_OK=0; A_DETAIL="$A_DETAIL 暂停期间点击被执行了"; }
echo "$LOG" | grep -qE "PA +: 滑动" && { A_OK=0; A_DETAIL="$A_DETAIL 暂停期间滑动被执行了"; }
echo "$(dump_display 0)" | grep -q "等待用户输入完成" || { A_OK=0; A_DETAIL="$A_DETAIL 主屏界面上没有显示「等待用户输入完成」"; }
echo "$(dump_display "$VD")" | grep -q 'text="Connected devices"' || { A_OK=0; A_DETAIL="$A_DETAIL 虚拟屏页面不在设置首页"; }
[ $A_OK = 1 ] && record "a 键盘弹出期间动作被暂停，用户输入始终进入主屏输入框" PASS "连续打字 3 次，主屏输入框「${GOT}」；键盘/焦点/输入连接/top display 全程不变；界面显示「等待用户输入完成」；虚拟屏未被操作" \
              || record "a 键盘弹出期间动作被暂停，用户输入始终进入主屏输入框" FAIL "$A_DETAIL"

# 把打进去的 3 个 q 删掉，还原用户输入框
for _ in 1 2 3; do adb shell input tap 994 2028; sleep 0.4; done

# ---------- b) 键盘收起 2 秒后 agent 自动继续 ----------
adb shell input keyevent KEYCODE_BACK    # 用户收起键盘（不带 displayId，作用在主屏）
mark MARK_HIDE
EXEC=""
for _ in $(seq 40); do
  sleep 0.5
  EXEC=$(adb logcat -d -v epoch -s PA | grep -E "PA +: 点击 \(" | head -1 | awk '{print $1}')
  [ -n "$EXEC" ] && break
done
if [ -z "$EXEC" ]; then
  record "b 键盘收起 2 秒后 agent 自动继续" FAIL "20 秒内动作没有继续执行"
else
  HIDE=$(adb logcat -d -v epoch -s PA | grep MARK_HIDE | head -1 | awk '{print $1}')
  DELTA=$(python3 -c "print(int((float('$EXEC')-float('$HIDE'))*1000))")
  # 下限留 150ms：标记是 adb 返回后才打的，比真实收起时刻晚
  if [ $DELTA -ge 1850 ] && [ $DELTA -le 6000 ]; then
    record "b 键盘收起 2 秒后 agent 自动继续" PASS "收起后 ${DELTA}ms 执行（要求 ≥2000ms）"
  else
    record "b 键盘收起 2 秒后 agent 自动继续" FAIL "收起后 ${DELTA}ms 才执行，不在 [1850,6000]ms 内"
  fi
fi
sleep 4   # 等第二个动作（滑动）和焦点补切完成
pa text --es t "你好，世界"; sleep 4   # 搜索页已打开，用户没在打字，写入不应被暂停

# ---------- c) 动作完成后焦点在主屏；主屏返回键作用在主屏 ----------
C_OK=1; C_DETAIL=""
S=$(ime_state)
[ "$(val "$S" topdisp)" = 0 ] || { C_OK=0; C_DETAIL="$C_DETAIL 动作完成后 top display=$(val "$S" topdisp)（应为 0）"; }
V_BEFORE=$(dump_display "$VD")
echo "$V_BEFORE" | grep -q 'text="你好，世界"' || { C_OK=0; C_DETAIL="$C_DETAIL 虚拟屏没有进入搜索页并写入中文"; }
adb shell input keyevent KEYCODE_BACK     # 用户手势：不带 displayId
sleep 2
V_AFTER=$(dump_display "$VD")
echo "$V_AFTER" | grep -q 'text="你好，世界"' || { C_OK=0; C_DETAIL="$C_DETAIL 返回键作用到了虚拟屏（搜索页被关掉）"; }
[ "$(main_alive)" = no ] || { C_OK=0; C_DETAIL="$C_DETAIL 主屏 PhoneAgent 没有响应返回键"; }
[ $C_OK = 1 ] && record "c 动作完成后焦点回主屏，主屏返回键作用在主屏" PASS "动作完成后 top display=0；用户返回键关掉了主屏的 PhoneAgent，虚拟屏搜索页保持不变" \
              || record "c 动作完成后焦点回主屏，主屏返回键作用在主屏" FAIL "$C_DETAIL"

adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 1
pa stop; sleep 2

echo; echo "| 验收项 | 结果 | 说明 |"; echo "|---|---|---|"
printf '%s\n' "${RES[@]}"
[ $FAIL = 0 ] && echo "总结果: PASS" || echo "总结果: FAIL"
exit $FAIL
