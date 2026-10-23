#!/bin/bash
# 渲染设计稿截图：用 debug 的 GalleryActivity 在模拟器上逐个渲染界面，adb screencap 保存到 docs/design/screens/。
# 用法：scripts/design/render_screens.sh [界面id ...]   不带参数则渲染全部（深色 + 浅色）
export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
PKG=com.android.phoneagent
OUT="$(cd "$(dirname "$0")/../.." && pwd)/docs/design/screens"
mkdir -p "$OUT"
IDS=("$@")
[ ${#IDS[@]} -eq 0 ] && IDS=(splash onboarding_1_name onboarding_2_shizuku onboarding_3_notification onboarding_4_overlay chat_empty chat_working chat_confirm chat_question chat_success chat_failure chat_handoff fullscreen fullscreen_steps floating_states drawer tasks settings)
adb shell svc power stayon true; adb shell input keyevent KEYCODE_WAKEUP
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
for id in "${IDS[@]}"; do
  for mode in dark light; do
    d=true; [ $mode = light ] && d=false
    adb shell am force-stop $PKG
    adb shell am start -n $PKG/.ui.GalleryActivity --es screen "$id" --ez dark $d >/dev/null
    sleep 2.5
    adb exec-out screencap -p > "$OUT/${id}_${mode}.png"
    echo "$id $mode"
  done
done
