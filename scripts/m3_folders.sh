#!/bin/bash
# 独立核对：打开 Gmail 的「已发送」和「草稿」文件夹，列出标题含 [PA测试] 的邮件（只读；输出只打印匹配 [PA测试] 的行）。
export LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/.." || exit 2
source scripts/m1_lib.sh
boot
pa launch --es c com.google.android.gm; sleep 7
perceive f_warm false >/dev/null
for folder in Sent Drafts; do
  clickt "Open navigation drawer" true 2.5 >/dev/null
  clickt "$folder" true 3 >/dev/null
  perceive f_$folder false >/dev/null
  echo "=== $folder"; grep -E 'PA测试' $LOCAL/f_$folder.txt | cut -c1-170 || true
  echo "（该文件夹共 $(grep -c '^\[' $LOCAL/f_$folder.txt) 个元素；标题行见上）"
done
pa stop
