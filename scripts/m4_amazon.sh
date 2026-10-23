#!/bin/bash
# T3 Amazon 任务的真实测试（绝不下单、不订阅：特权侧硬拦截 + 页面级锁定）：
#   A 用购物车里现有的结算并交接；B 搜一个便宜商品加购、走到提交订单页并交接；C 按用户要求删除 B 加进去的那件（确认卡片）。
# 会往你的真实 Amazon 购物车里加一件测试商品（C 会再把它删掉），不会下单。
export LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/.." || exit 2
source scripts/p0/lib.sh
CASES=${CASES:-"A B C"}
PRODUCT=${PRODUCT:-AAA batteries 4 pack}
run() { local name=$1 text=$2; shift 2
  echo "=== 用例 $name：$text"
  TASK_TIMEOUT=420 bash scripts/m2_task.sh "$text" "$@" | tee $OUT/m4_case_$name.txt | grep -E '^  (TASK (说|问|确认卡片|交接|成功|失败|停止)|>>>)|^结果|步数'
}
handoff() { adb shell "am broadcast -a $PKG.CMD -n $PKG/.CmdReceiver --es cmd handoff" >/dev/null; sleep 4
  echo "  >>> 去完成：主屏前台 = $(adb shell dumpsys activity activities | grep -m1 'topResumedActivity' | sed 's/.*u0 //;s/ .*//')"; }
for c in $CASES; do case $c in
  A) run A "帮我把购物车带到结算页" ; handoff;;
  B) run B "在亚马逊搜 $PRODUCT，买一个，走到提交订单页停下，我自己付款" "全部结算" ; handoff;;
  C) run C "用购物车里现有的商品结算，但是先把 $PRODUCT 那件删掉" "@SEND" ; handoff;;
esac; done
