# P0 验证脚本共用函数（source 使用）。依赖：debug 版已安装，Shizuku 已运行并授权。
export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
PKG=com.android.phoneagent
OUT=${P0_OUT:-/tmp/p0_out}   # 截图/界面树输出目录（可能含个人信息，不要提交）
mkdir -p "$OUT"
pa() { adb shell am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd "$@" >/dev/null; }
pat() { # pat CMD KEY 'value with spaces' [更多参数]：带空格的字符串参数
  local cmd=$1 key=$2 val=$3; shift 3
  adb shell "am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd $cmd --es $key '$val' $*" >/dev/null
}
boot() { # 干净启动：停止、重启 App、连接、建虚拟屏。$1 可追加 config 参数
  adb shell svc power stayon true; adb shell input keyevent KEYCODE_WAKEUP
  pa stop; sleep 1; adb shell am force-stop $PKG; sleep 1
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
  pa config --ez keepfull true --ez pause false $1; pa connect; sleep 4; pa display; sleep 3
}
vdisplay() { adb shell dumpsys display | grep -oE "^  Display [0-9]+:" | grep -v " 0:" | grep -oE "[0-9]+" | head -1; }
tasks() { # tasks DISPLAY：每行 `rootId childIds 组件 visible=`
  adb logcat -c; pa tasks --ei display "$1"; sleep 1.5
  adb logcat -d -s PA | sed 's/.*PA *: //' | grep -vE "任务列表已输出|^$|beginning|^TASKS"
}
plog() { adb logcat -d -s PA | grep -vE "I PA +: {5,}" | sed 's/.*PA *: //' | tail -${1:-8}; }
tree() { # tree [display] -> $OUT/tree.txt，输出行数
  adb shell rm -f /sdcard/Android/data/$PKG/files/tree.txt; adb logcat -c
  if [ -n "$1" ]; then pa dump --ei display "$1"; else pa dump; fi
  for _ in $(seq 30); do sleep 0.5; adb shell test -f /sdcard/Android/data/$PKG/files/tree.txt && break; done
  adb pull /sdcard/Android/data/$PKG/files/tree.txt "$OUT/tree.txt" >/dev/null 2>&1; wc -l < "$OUT/tree.txt"
}
shot() { # shot NAME -> $OUT/NAME.png（虚拟屏全分辨率画面）
  adb shell rm -f /sdcard/Android/data/$PKG/files/shot.png; pa shot; sleep 1
  adb pull /sdcard/Android/data/$PKG/files/shot.png "$OUT/$1.png" >/dev/null 2>&1 && echo "$OUT/$1.png"
}
clickt() { # clickt 文案 [touch=true] [等待秒]：按文案点击虚拟屏上的控件（硬拦截生效）
  adb logcat -c; pat clicktext t "$1" --ez touch "${2:-true}"; sleep "${3:-2.5}"; plog 1
}
typeat() { # typeat X Y 文字：向坐标处的可编辑节点写文字（按节点定位；「当前聚焦」在 Gmail 等界面不可靠）
  adb shell "am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd textat --ei x $1 --ei y $2 --es t '$3'" >/dev/null; sleep 2; plog 1
}
typet() { pat text t "$1"; sleep 2; plog 1; }
FAILS=0
check() { # check 描述 条件（0=通过）
  if [ "$2" = 0 ]; then echo "  PASS  $1"; else echo "  FAIL  $1"; FAILS=$((FAILS+1)); fi
}
