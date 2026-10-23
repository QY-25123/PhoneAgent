#!/bin/bash
# UV-3：同一 App 冲突的检测。
#  ① 开始前：能判断目标 App 是否在主屏前台
#  ② 运行中：用户从主屏启动同一 App（启动器图标 / am start --display 0）时，能检测到虚拟屏上的任务被抢走
#  ③ 用户启动请求被虚拟屏实例「吸收」（不带 display 的组件启动）：任务列表看不出来，记录能否用事件日志检测
#  ④ 无误报：虚拟屏内部导航和返回不触发
source "$(dirname "$0")/lib.sh"
SET=com.android.settings

fg_pkg() { # 主屏最上面的可见根任务的包名
  tasks 0 | grep "visible=true" | head -1 | sed -E 's/.*ComponentInfo\{([^/]*)\/.*/\1/'
}
vtask() { tasks "$1" | head -1 | awk '{print $1}'; }   # 虚拟屏第一个根任务 id，没有则为空

launcher_open_settings() { # 模拟用户：回桌面 → 上滑打开应用列表 → 点 Settings 图标（失败重试）
  for _ in 1 2 3; do
    adb shell input keyevent KEYCODE_HOME; sleep 2; adb shell input swipe 540 2000 540 600 300; sleep 2.5
    pat clicktext t "Settings" --ez touch true --ei display 0; sleep 4
    [ "$(fg_pkg)" = "$SET" ] && return 0
  done
  return 1
}

echo "== ① 开始前：主屏前台应用检测"
boot
F1=$(fg_pkg); echo "  主屏前台=$F1"; [ "$F1" = "$PKG" ]; check "主屏是 PhoneAgent 时识别为 $PKG" $?
adb shell am start --display 0 -n $SET/.Settings >/dev/null; sleep 2
F2=$(fg_pkg); echo "  主屏前台=$F2"; [ "$F2" = "$SET" ]; check "用户在主屏打开设置后识别为 $SET" $?
adb shell input keyevent KEYCODE_HOME; sleep 1.5
F3=$(fg_pkg); echo "  按 Home 后主屏前台=$F3"; [ "$F3" != "$SET" ]; check "回到桌面后不再识别为 $SET" $?

echo "== ② 运行中被抢走：启动器图标路径（真实用户路径）"
boot; pa launch --es c $SET/.Settings; sleep 4; VD=$(vdisplay)
T0=$(vtask $VD); echo "  虚拟屏任务=$T0"; [ -n "$T0" ]; check "启动后虚拟屏上有 $SET 任务" $?
launcher_open_settings
T1=$(vtask $VD); echo "  启动器点图标后：虚拟屏任务='${T1}' 主屏前台=$(fg_pkg)"
[ -z "$T1" ] || [ "$T1" != "$T0" ]; check "虚拟屏上的任务消失（可检测到被抢走）" $?
[ "$(fg_pkg)" = "$SET" ]; check "该 App 出现在主屏" $?

echo "== ② 运行中被抢走：am start --display 0 路径"
boot; pa launch --es c $SET/.Settings; sleep 4; VD=$(vdisplay); T0=$(vtask $VD)
adb shell am start --display 0 -n $SET/.Settings >/dev/null; sleep 3
T1=$(vtask $VD); echo "  虚拟屏任务='${T1}' 主屏前台=$(fg_pkg)"
[ -z "$T1" ]; check "虚拟屏任务消失（被整个移到主屏）" $?
[ "$(fg_pkg)" = "$SET" ]; check "该 App 出现在主屏" $?

echo "== ③ 吸收：不带 display 的组件启动"
boot; pa launch --es c $SET/.Settings; sleep 4; VD=$(vdisplay); T0=$(vtask $VD)
adb logcat -b events -c
adb shell am start -n $SET/.Settings >/dev/null; sleep 2.5
T1=$(vtask $VD)
[ "$T1" = "$T0" ]; check "任务列表没有变化（任务列表检测不到吸收）：${T0} → ${T1}" $?
EV=$(adb logcat -d -b events | grep -E "wm_task_moved: \[$T0," | grep -c .); CR=$(adb logcat -d -b events | grep -cE "wm_create_activity.*,$T0,")
echo "  事件日志：wm_task_moved×${EV}，wm_create_activity×${CR}"
[ "$EV" -ge 1 ] && [ "$CR" = 0 ]; check "事件日志里有孤立的 wm_task_moved（无 wm_create_activity），可作为吸收的检测信号" $?

echo "== ④ 无误报：虚拟屏内部导航/返回（任务 id 不变，没有外部启动）"
boot; pa launch --es c $SET/.Settings; sleep 4; VD=$(vdisplay); T0=$(vtask $VD)
clickt "Network" true 3 >/dev/null; pa back; sleep 2; clickt "Connected devices" true 3 >/dev/null; pa back; sleep 2
T1=$(vtask $VD); [ "$T0" = "$T1" ] && [ "$(fg_pkg)" = "$PKG" ]; check "内部导航后虚拟屏任务不变（${T0} → ${T1}），主屏仍是 PhoneAgent" $?

pa stop; sleep 1
echo; [ $FAILS = 0 ] && echo "UV-3 结果: PASS" || echo "UV-3 结果: FAIL($FAILS)"; exit $FAILS
