#!/bin/bash
# 对脱敏样本（app/src/test/resources/perception）在设备上跑同一条感知管线，输出文本和标注截图（离线，不需要虚拟屏/Shizuku）。
# 用法：scripts/m1_samples.sh [输出目录，默认 docs/perception/]
cd "$(dirname "$0")/.." || exit 2
source scripts/p0/lib.sh
OUTDIR=${1:-docs/perception}; mkdir -p "$OUTDIR"
D=/sdcard/Android/data/$PKG/files/samples
adb shell rm -rf $D; adb shell mkdir -p $D
for f in app/src/test/resources/perception/*.snap.json app/src/test/resources/perception/*.png; do adb push "$f" $D/ >/dev/null; done
adb shell am start -n $PKG/.MainActivity --es route debug >/dev/null; sleep 1   # 保证进程在
for n in $(ls app/src/test/resources/perception/*.snap.json | xargs -n1 basename | sed 's/.snap.json//'); do
  adb logcat -c; pa perceivesample --es name "$n"; sleep 1.5
  adb logcat -d -s PA | grep "SAMPLE $n " | sed 's/.*PA *: //'
  adb pull $D/out/$n.txt "$OUTDIR/$n.txt" >/dev/null 2>&1; adb pull $D/out/$n.jpg "$OUTDIR/$n.jpg" >/dev/null 2>&1
done
