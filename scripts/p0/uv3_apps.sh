#!/bin/bash
# UV-3 补测：把同一 App 冲突检测放到真实目标 App（Gmail、Google 日历）上。
# 与 uv3_conflict.sh（用设置）相同的四个场景：开始前检测、启动器图标抢走、不带 display 的吸收、内部导航无误报。
source "$(dirname "$0")/lib.sh"

fg_pkg() { tasks 0 | grep "visible=true" | head -1 | sed -E 's/.*ComponentInfo\{([^/]*)\/.*/\1/'; }
vtask() { tasks "$1" | head -1 | awk '{print $1}'; }
launcher_open() { # $1=包名 $2=启动器里的文案（回桌面，必要时上滑打开应用列表后点图标）
  for _ in 1 2 3; do
    adb shell input keyevent KEYCODE_HOME; sleep 2
    pat clicktext t "$2" --ez touch true --ei display 0; sleep 3
    [ "$(fg_pkg)" = "$1" ] && return 0
    adb shell input keyevent KEYCODE_HOME; sleep 2; adb shell input swipe 540 2000 540 600 300; sleep 2.5
    pat clicktext t "$2" --ez touch true --ei display 0; sleep 4
    [ "$(fg_pkg)" = "$1" ] && return 0
  done
  return 1
}
dismiss_popups() { # 首次启动的引导/权限弹窗：权限一律不授予（PRD 6.7.3）
  for t in "Don" "GOT IT" "TAKE ME TO GMAIL" "Got it" "next page"; do pat clicktext t "$t" --ez touch true; sleep 1.5; done
}

run_app() { # $1=包名 $2=启动器文案 $3=标签
  local APP=$1 LABEL=$2 NAME=$3 COMP VD T0 T1
  COMP=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$APP" | tail -1 | tr -d '\r')
  echo; echo "######## $NAME（$COMP）"
  echo "== ① 开始前：主屏前台应用检测"
  boot; adb shell am start --display 0 -n "$COMP" >/dev/null; sleep 4
  [ "$(fg_pkg)" = "$APP" ]; check "$NAME：用户在主屏打开后识别为 $APP（实际 $(fg_pkg)）" $?
  adb shell input keyevent KEYCODE_HOME; sleep 2
  [ "$(fg_pkg)" != "$APP" ]; check "$NAME：回桌面后不再识别" $?
  echo "== ② 运行中被抢走：启动器图标路径（真实用户路径）"
  boot; pa launch --es c "$APP"; sleep 8; dismiss_popups; VD=$(vdisplay); T0=$(vtask "$VD")
  [ -n "$T0" ]; check "$NAME：启动后虚拟屏上有任务（${T0}）" $?
  launcher_open "$APP" "$LABEL"
  T1=$(vtask "$VD"); echo "  启动器点图标后：虚拟屏任务='${T1}' 主屏前台=$(fg_pkg)"
  [ -z "$T1" ] || [ "$T1" != "$T0" ]; check "$NAME：虚拟屏上的任务消失（可检测到被抢走）" $?
  [ "$(fg_pkg)" = "$APP" ]; check "$NAME：该 App 出现在主屏" $?
  echo "== ③ 吸收：不带 display 的组件启动"
  boot; pa launch --es c "$APP"; sleep 8; dismiss_popups; VD=$(vdisplay); T0=$(vtask "$VD")
  adb logcat -b events -c; adb shell am start -n "$COMP" >/dev/null; sleep 3; T1=$(vtask "$VD")
  EV=$(adb logcat -d -b events | grep -cE "wm_task_moved: \[$T0,"); CR=$(adb logcat -d -b events | grep -cE "wm_create_activity.*,$T0,")
  echo "  任务 ${T0} → ${T1}；事件日志：wm_task_moved×${EV}，wm_create_activity×${CR}；主屏前台=$(fg_pkg)"
  if [ "$T1" = "$T0" ]; then echo "  结果：被吸收（任务列表不变），事件信号 moved=${EV} create=${CR}"
  else echo "  结果：任务被移动/抢走（任务列表可检测）"; fi
  echo "== ④ 无误报：虚拟屏内部导航（任务 id 不变）"
  boot; pa launch --es c "$APP"; sleep 8; dismiss_popups; VD=$(vdisplay); T0=$(vtask "$VD")
  pa back; sleep 2; pa swipe; sleep 2; T1=$(vtask "$VD")
  [ "$T0" = "$T1" ] && [ "$(fg_pkg)" = "$PKG" ]; check "$NAME：内部操作后任务不变（${T0} → ${T1}），主屏仍是 PhoneAgent" $?
}

run_app com.google.android.gm "Gmail" "Gmail"
run_app com.google.android.calendar "Calendar" "Google 日历"
pa stop; sleep 1
echo; [ $FAILS = 0 ] && echo "UV-3(Gmail/日历) 结果: PASS" || echo "UV-3(Gmail/日历) 结果: FAIL($FAILS)"; exit $FAILS
