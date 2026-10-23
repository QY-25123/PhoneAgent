#!/bin/bash
# 真实跑一条任务：scripts/m2_task.sh "任务文字" ["对追问的回答1" "回答2" ...]
# 通过 debug 广播 say 提交（和对话页是同一个任务入口），轮询 logcat 里的 TASK 事件直到成功/失败/停止/只回复，
# 遇到追问就按顺序发送给定的回答。结束后把 runs/<时间>/ 拉到 $OUT/m2_runs/ 并打印摘要。
# 前提：debug 版已安装，Shizuku 已运行并授权，local.properties 里有 CLAUDE_API_KEY。
cd "$(dirname "$0")/.." || exit 2
source scripts/p0/lib.sh
TEXT=$1; shift
ANSWERS=("$@")
TIMEOUT=${TASK_TIMEOUT:-420}
say() { adb shell "am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd say --es text '$1'" >/dev/null; }
adb shell svc power stayon true; adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2     # 创建虚拟屏要在 App 可见时（前台服务）
adb logcat -c
START=$(date +%s)
say "$TEXT"
n=0; seen=0; RESULT=
while [ $(( $(date +%s) - START )) -lt $TIMEOUT ]; do
  sleep 2
  adb logcat -d -s PA | grep -E 'TASK |TASKRUN' | sed 's/.*PA *: //' > $OUT/m2_events.txt
  total=$(wc -l < $OUT/m2_events.txt)
  if [ "$total" -gt "$seen" ]; then
    tail -n +$((seen+1)) $OUT/m2_events.txt | while read -r l; do echo "  $l"; done
    # 新出现的追问：发送下一个回答
    if tail -n +$((seen+1)) $OUT/m2_events.txt | grep -q '^TASK 问：'; then
      if [ $n -lt ${#ANSWERS[@]} ]; then sleep 1; echo "  >>> 回答：${ANSWERS[$n]}"; say "${ANSWERS[$n]}"; n=$((n+1)); fi
    fi
    seen=$total
  fi
  if grep -qE '^TASK (成功|失败|停止)：' $OUT/m2_events.txt; then RESULT=$(grep -E '^TASK (成功|失败|停止)：' $OUT/m2_events.txt | tail -1); break; fi
  # 只回复、不开始任务（不支持/闲聊/还在开发中）：说了话、没有「开始」、也没有未回答的追问
  if grep -q '^TASK 说：' $OUT/m2_events.txt && ! grep -q '^TASK 开始：' $OUT/m2_events.txt && ! grep -q '^TASK 问：' $OUT/m2_events.txt && ! grep -q '复述\|现在去办' $OUT/m2_events.txt; then
    sleep 3; RESULT="（只回复，没有开始任务）"; break
  fi
done
[ -n "$RESULT" ] || RESULT="超时（${TIMEOUT}s）"
echo "结果: $RESULT  用时 $(( $(date +%s) - START ))s"
sleep 3
RUN=$(adb shell ls /sdcard/Android/data/$PKG/files/runs 2>/dev/null | tail -1 | tr -d '\r')
if [ -n "$RUN" ]; then
  mkdir -p $OUT/m2_runs; adb pull /sdcard/Android/data/$PKG/files/runs/$RUN $OUT/m2_runs/ >/dev/null 2>&1
  echo "运行记录: $OUT/m2_runs/$RUN"; python3 -c "import json;d=json.load(open('$OUT/m2_runs/$RUN/summary.json'));print('  步数',d['steps'],'模型调用',d['modelCalls'],'总耗时',d['totalMs']//1000,'s 费用≈\$',d['approxCostUsd'])" 2>/dev/null
fi
