# M1 感知脚本共用函数（source 使用）：在虚拟屏上做一次感知并取回文件。
source "$(dirname "${BASH_SOURCE[0]}")/p0/lib.sh"
LOCAL=${M1_LOCAL:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/local-samples}   # 原始样本（含个人信息，git 忽略）
mkdir -p "$LOCAL"
perceive() { # perceive NAME [raw=true|false]：感知并把 txt/jpg（raw 时含 snap.json/png）拉到 $LOCAL
  local name=$1 raw=${2:-true}
  adb shell rm -f /sdcard/Android/data/$PKG/files/perceive/$name.\*
  adb logcat -c; pa perceive --es name "$name" --ez raw "$raw"
  for _ in $(seq 40); do sleep 0.5; adb logcat -d -s PA | grep -q "PERCEIVE $name" && break; done
  for ext in txt jpg snap.json png; do adb pull /sdcard/Android/data/$PKG/files/perceive/$name.$ext "$LOCAL/$name.$ext" >/dev/null 2>&1; done
  adb logcat -d -s PA | grep "PERCEIVE $name" | sed 's/.*PA *: //'
}
