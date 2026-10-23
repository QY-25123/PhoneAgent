#!/bin/bash
# UV-1：全屏观看的画面帧率。
# 方法：在虚拟屏上跑一个每帧都变化的动画页面（debug 的 FpsTestActivity，约 60 fps 的画面源），
#       主屏上 PhoneAgent 在前台显示预览。统计 10 秒内：
#         虚拟屏产出的帧数（ImageReader 回调）、转成预览的帧数（CPU 路径）、界面实际重绘的帧数（gfxinfo）、进程 CPU。
# 通过标准：底线 5 fps、目标 10 fps。用法：scripts/p0/uv1_fps.sh [fps列表，默认 "60 30 10 5"]
source "$(dirname "$0")/lib.sh"
LIST=${1:-"60 30 10 5"}
echo "| previewFps | 虚拟屏产出 | 转成预览(CPU 路径) | 界面显示 | PhoneAgent 主进程 CPU（占单核%） |"
echo "|---|---|---|---|---|"
for F in $LIST; do
  boot "--ei fps $F"; pa launch --es c $PKG/.FpsTestActivity; sleep 4
  adb logcat -c; pa fps --ei sec 12 --ez swipe false
  sleep 2   # 等测试进入稳定期再采样 CPU
  CPU=$(adb shell "top -b -d 2 -n 2" | grep -E ' com.android.phoneagent$' | tail -1 | awk '{print $9"%"}')
  sleep 6
  L=$(plog 6 | grep "FPS 结果" | tail -1)
  P=$(echo "$L" | sed -E 's/.*产出 [0-9]+ 帧（([0-9.]+) fps）.*转成预览 [0-9]+ 帧（([0-9.]+) fps）.*界面显示 [0-9]+ 帧（([0-9.]+) fps）.*/\1|\2|\3/')
  echo "| $F | $(echo $P | cut -d'|' -f1) fps | $(echo $P | cut -d'|' -f2) fps | $(echo $P | cut -d'|' -f3) fps | ${CPU:-?} |"
done
pa stop; sleep 1
