#!/bin/bash
# T2 邮件任务的真实测试（真实邮件只发给 qijie_jerry@163.com，主题带 [PA测试] 前缀）：
#   S1 直接发送、S2 直接发送、M 修改一次后发送、C 取消。会产生真实的模型费用和真实邮件。
export LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/.." || exit 2
source scripts/p0/lib.sh
CASES=${CASES:-"S1 S2 M C"}
run() { # run 名字 文字 [回答/@选择...]
  local name=$1 text=$2; shift 2
  echo "=== 用例 $name：$text"
  TASK_TIMEOUT=360 bash scripts/m2_task.sh "$text" "$@" | tee $OUT/m3_case_$name.txt | grep -E '^  (TASK (说|问|确认卡片|成功|失败|停止)|>>>)|^结果|步数'
  echo "$name|$(grep '^结果' $OUT/m3_case_$name.txt | cut -c1-80)|$(grep '步数' $OUT/m3_case_$name.txt | tr -s ' ')" >> $OUT/m3_suite.rows
}
rm -f $OUT/m3_suite.rows
for c in $CASES; do case $c in
  S1) run S1 "给 qijie_jerry@163.com 发邮件，说周五的评审改到下午3点，地点不变。主题用「[PA测试] 周五评审改期」" "@SEND";;
  S2) run S2 "帮我给 qijie_jerry@163.com 发封邮件：告诉他明天上午的会议取消了。主题写「[PA测试] 会议取消」" "@SEND";;
  M)  run M "给 qijie_jerry@163.com 写邮件，说本周日聚餐定在晚上7点，主题「[PA测试] 聚餐时间」" "@MODIFY" "把时间改成晚上8点，并加一句请回复确认" "@SEND";;
  C)  run C "给 qijie_jerry@163.com 写邮件，说下周一的报告我晚点交，主题「[PA测试] 报告延期」" "@CANCEL";;
esac; done
echo "---- 汇总"; cat $OUT/m3_suite.rows
