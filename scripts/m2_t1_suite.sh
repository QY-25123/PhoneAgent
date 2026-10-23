#!/bin/bash
export LC_ALL=en_US.UTF-8
# T1 日历任务的真实测试：不同说法各跑一次（含一次需要追问），跑完用日历内容提供者核对事项和时间。
# 事项标题都带 [$TAG] 前缀。结果表追加到 $OUT/m2_suite.md。会产生真实的模型调用费用（每次几美分）和真实的日历事项。
cd "$(dirname "$0")/.." || exit 2
source scripts/p0/lib.sh
CASES=${CASES:-"B C D E F"}
TAG=${TAG:-PA测试}   # 事项标题前缀 [TAG]；换一个 TAG 重跑不会和之前的事项混在一起
run_case() { # run_case NAME 文字 标题片段 开始 结束 [追问回答...]
  local name=$1 text=$2 title=$3 ws=$4 we=$5; shift 5
  echo "=== 用例 $name：$text"
  TASK_TIMEOUT=300 bash scripts/m2_task.sh "$text" "$@" | tee $OUT/m2_case_$name.txt | grep -E '^  (TASK (说|问|成功|失败)|>>>)|^结果|步数' | cut -c1-200
  local v; v=$(bash scripts/m2_verify_event.sh "$title" "$ws" "$we"); echo "$v" | head -3
  echo "$name|$text|$(echo "$v" | head -1 | cut -c1-4)|$(grep '步数' $OUT/m2_case_$name.txt | tr -s ' ')" >> $OUT/m2_suite.rows
}
rm -f $OUT/m2_suite.rows
for c in $CASES; do case $c in
  B) run_case B "明天上午10点到10点45，日历加个 [$TAG] 牙医预约" "[$TAG] 牙医预约" "2026-10-24 10:00" "2026-10-24 10:45";;
  C) run_case C "帮我加个日历事项" "[$TAG] 周会" "2026-10-26 09:00" "2026-10-26 10:00" "标题是 [$TAG] 周会，下周一上午9点";;
  D) run_case D "设置一个提醒事项：下周五下午2点提醒我 [$TAG] 交报告" "[$TAG] 交报告" "2026-10-30 14:00" "2026-10-30 15:00";;
  E) run_case E "10月30号晚上7点半约个饭局 [$TAG] 和Alice吃饭，两个小时" "[$TAG] 和Alice吃饭" "2026-10-30 19:30" "2026-10-30 21:30";;
  F) run_case F "晚点提醒我 [$TAG] 喝水" "[$TAG] 喝水" "2026-10-23 20:00" "2026-10-23 21:00" "今天晚上8点";;
esac; done
echo "---- 汇总"; cat $OUT/m2_suite.rows
