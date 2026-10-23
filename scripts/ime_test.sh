#!/bin/bash
# 输入法隔离回归测试：主屏键盘弹出时，在虚拟屏执行操作，检查用户的输入目标是否被改变。
#
# 用法: scripts/ime_test.sh [-p IME策略] [-m touch|semantic] [-b key|global]
#   -p  创建虚拟屏后调用 setDisplayImePolicy：0=LOCAL 1=FALLBACK_DISPLAY(系统默认) 2=HIDE(Agent 创建虚拟屏时的设置,脚本默认)
#       注意：策略按 display uniqueId 缓存，会影响之后同名虚拟屏，所以脚本每次都显式设置，避免上次实验残留
#   -l  只测“键盘弹出时在虚拟屏 am start --display 启动应用”这一个动作（不注入任何输入）
#   -m  操作方式：touch=模拟触摸(默认)  semantic=无障碍 ACTION_CLICK
#   -b  返回键方式：key=带 displayId 的 KeyEvent(默认)  global=GLOBAL_ACTION_BACK
# 默认配置：虚拟屏带 STEAL_TOP_FOCUS_DISABLED 标志，「用户打字时暂停」关闭，动作真实执行。
# 对照实验（去掉标志，应该失败，用来证明标志在起作用）：CFG='--ez steal false' scripts/ime_test.sh
# 前提：debug 版已安装、Shizuku 已运行并已授权、模拟器已显示软键盘(show_ime_with_hard_keyboard=1)。
# 每个动作后检查（全部满足才算 PASS）:
#   shown   主屏 mInputShown 仍为 true
#   focus   mCurFocusedWindow 仍是基线（主屏窗口）
#   client  mCurClient 仍是基线（输入连接的客户端没被切走）
#   topdisp mTopFocusedDisplayId 仍为 0
#   typing  用主屏软键盘点一个字母，主屏输入框确实收到了
# 退出码：全部 PASS 为 0，否则为 1。

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
POLICY=2; MODE=touch; BACK=key
LAUNCH_ONLY=0
while getopts "p:m:b:l" o; do
  case $o in p) POLICY=$OPTARG;; m) MODE=$OPTARG;; b) BACK=$OPTARG;; l) LAUNCH_ONLY=1;; *) exit 2;; esac
done
PKG=com.android.phoneagent
FAIL=0

pa() { adb shell am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd "$@" >/dev/null; }

# 主屏输入框内容：dump 主屏界面树，取含“你好”的 EditText 的 text 和中心坐标
main_text() { # 主屏输入框当前内容（由 debug 广播让 App 直接报告，比读界面树可靠，界面树里的文字更新有延迟）
  adb logcat -c; pa maintext; sleep 0.7; adb logcat -d -s PA | grep -m1 'MAIN_FIELD=' | sed 's/.*MAIN_FIELD=//' | tr -d '\r'
}

main_field() { # 输出: text<TAB>cx<TAB>cy（首次连接 UiAutomation 后界面树可能还没填充，重试几次）
  local out
  for _ in 1 2 3 4; do
    adb logcat -c; pa dump --ei display 0
    for _ in $(seq 20); do sleep 0.5; adb logcat -d -s PA | grep -q "界面树已输出" && break; done
    out=$(adb logcat -d -s PA | python3 -I -c '
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

ime_state() { # 输出 key=value 行
  local d w
  d=$(adb shell dumpsys input_method); w=$(adb shell dumpsys window)
  echo "shown=$(echo "$d" | grep -m1 -oE 'mInputShown=[a-z]+' | cut -d= -f2)"
  echo "focus=$(echo "$d" | grep -m1 -oE 'mCurFocusedWindow=[^ ]+' | cut -d= -f2)"
  echo "client=$(echo "$d" | grep -m1 -oE 'mCurClient=ClientState\{[^ ]+ mUid=[0-9]+ mPid=[0-9]+' | sed 's/.*mUid/mUid/')"
  echo "topdisp=$(echo "$w" | grep -m1 -oE 'mTopFocusedDisplayId=[0-9]+' | cut -d= -f2)"
}

val() { echo "$1" | grep "^$2=" | cut -d= -f2-; }

typing_ok() { # 主屏软键盘点 q，检查主屏输入框是否多了一个字符；然后点退格还原
  local before after
  before=$(main_text)
  adb shell input tap 58 1716; sleep 1
  after=$(main_text)
  adb shell input tap 994 2028; sleep 1   # 退格
  if [ "${#after}" -eq $(( ${#before} + 1 )) ]; then echo ok
  else
    echo "no(${before}->${after})"
    [ -n "$SHOT_DIR" ] && adb exec-out screencap -p > "$SHOT_DIR/typing_fail_$(date +%s).png"
  fi
}

echo "| 步骤 | shown | focus | client | topdisp | typing | 结果 |"
echo "|---|---|---|---|---|---|---|"
check() { # $1=步骤名
  local s shown focus client top typ res=PASS
  s=$(ime_state)
  shown=$(val "$s" shown); focus=$(val "$s" focus); client=$(val "$s" client); top=$(val "$s" topdisp)
  local c_shown=ok c_focus=ok c_client=ok c_top=ok
  [ "$shown" = true ] || { c_shown="FAIL($shown)"; res=FAIL; }
  [ "$focus" = "$B_FOCUS" ] || { c_focus="FAIL"; res=FAIL; }
  [ "$client" = "$B_CLIENT" ] || { c_client="FAIL(${client})"; res=FAIL; }
  [ "$top" = 0 ] || { c_top="FAIL($top)"; res=FAIL; }
  if [ "$shown" = true ]; then typ=$(typing_ok); else typ="skip(键盘未显示)"; fi
  [ "$typ" = ok ] || res=FAIL
  [ "$res" = PASS ] || FAIL=1
  echo "| $1 | $c_shown | $c_focus | $c_client | $c_top | $typ | $res |"
}

# ---------- 准备 ----------
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
pa stop; sleep 1
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
pa config --ez pause false $CFG   # 默认配置：只靠 STEAL_TOP_FOCUS_DISABLED 标志，不暂停。对照实验：CFG='--ez steal false'
pa connect; sleep 4
pa display; sleep 3
if [ -n "$POLICY" ]; then
  adb logcat -c; pa imepolicy --ei p "$POLICY"; sleep 1
  adb logcat -d -s PA | grep "IME 策略" | sed 's/.*PA *: /策略已设置: /'
fi
pa launch; sleep 4

# 主屏输入框获得焦点并弹出键盘
read -r _ CX CY < <(main_field)
for i in 1 2 3; do
  adb shell input tap "$CX" "$CY"; sleep 2
  [ "$(val "$(ime_state)" shown)" = true ] && break
done
BASE=$(ime_state); B_FOCUS=$(val "$BASE" focus); B_CLIENT=$(val "$BASE" client)
echo "配置: policy=${POLICY:-未设置} mode=$MODE back=$BACK  基线: focus=$B_FOCUS client=$B_CLIENT"
check "0 基线(主屏键盘已弹出)"

if [ $LAUNCH_ONLY = 1 ]; then
  pa launch; sleep 3; check "L 虚拟屏 am start --display 启动设置(无任何输入注入)"
  pa stop; sleep 1
  [ $FAIL = 0 ] && echo "总结果: PASS" || echo "总结果: FAIL"
  exit $FAIL
fi

# ---------- 动作 ----------
click() { if [ "$MODE" = semantic ]; then pa click --ei x "$1" --ei y "$2"; else pa tap --ei x "$1" --ei y "$2"; fi; }
goback() { if [ "$BACK" = global ]; then pa gback; else pa back; fi; }

click 540 540;                       sleep 3; check "a 点击设置搜索框($MODE)"
pa text --es t "你好，世界";          sleep 2; check "b 写入中文(ACTION_SET_TEXT)"
goback;                              sleep 3; check "c1 返回($BACK)"
click 540 540;                       sleep 3; check "c2 再次进入搜索页(自动请求输入法)"
goback;                              sleep 3; check "d 返回($BACK)"

pa stop; sleep 1
[ $FAIL = 0 ] && echo "总结果: PASS" || echo "总结果: FAIL"
exit $FAIL
