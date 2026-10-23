#!/bin/bash
# 返回键对比：带 displayId 的 KeyEvent(key) vs UiAutomation GLOBAL_ACTION_BACK(global)。
# 判定标准：返回只能作用于虚拟屏，主屏的 PhoneAgent(MainActivity) 必须保持不动。
# 两种场景：S1 虚拟屏是 top focused display；S2 主屏是 top focused display（先点一下主屏空白处）。
# 用法: scripts/back_test.sh        退出码：全部符合预期为 0

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
PKG=com.android.phoneagent
FAIL=0
pa() { adb shell am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd "$@" >/dev/null; }

vpage() { # 虚拟屏当前页面：home / network / 其他
  adb logcat -c; pa dump
  for _ in $(seq 20); do sleep 0.5; adb logcat -d -s PA | grep -q "界面树已输出" && break; done
  local l; l=$(adb logcat -d -s PA)
  if echo "$l" | grep -q 'text="Airplane mode"'; then echo network
  elif echo "$l" | grep -q 'text="Connected devices"'; then echo home
  else echo other; fi
}
main_alive() { # 主屏 MainActivity 是否仍在显示（display 0 的可见 task 里有 phoneagent）
  adb shell dumpsys activity activities | awk '/^  Display: mDisplayId=0 /{f=1;next} /^  Display: mDisplayId=/{f=0} f' | grep -q "A=10195:com.android.phoneagent.*visible=true" && echo yes || echo NO
}
topdisp() { adb shell dumpsys window | grep -m1 -oE 'mTopFocusedDisplayId=[0-9]+' | cut -d= -f2; }

echo "| 返回方式 | 场景 | 返回前 top display | 虚拟屏页面(前→后) | 主屏 App 仍在 | 结果 |"
echo "|---|---|---|---|---|---|"
run() { # $1=key|global $2=S1|S2
  pa stop; sleep 1; adb shell am force-stop $PKG; sleep 1
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
  pa connect; sleep 4; pa display; sleep 3; pa launch; sleep 4
  pa tap --ei x 400 --ei y 740; sleep 3          # 虚拟屏进入「Network & internet」子页
  local before after top alive res=PASS cmd=back
  [ "$1" = global ] && cmd=gback
  if [ "$2" = S2 ]; then adb shell input tap 900 200; sleep 2; fi   # 点主屏空白处，让主屏成为 top focused display
  top=$(topdisp); before=$(vpage)
  pa $cmd; sleep 3
  alive=$(main_alive); after=$(vpage)
  [ "$before" = network ] && [ "$after" = home ] && [ "$alive" = yes ] || { res=FAIL; FAIL=1; }
  echo "| $1 | $2 | $top | ${before}→${after} | $alive | $res |"
}
for m in key global; do for s in S1 S2; do run $m $s; done; done
pa stop
[ $FAIL = 0 ] && echo "总结果: PASS" || echo "总结果: FAIL"
exit $FAIL
