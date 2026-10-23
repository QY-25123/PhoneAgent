#!/bin/bash
# 回归入口：改动 priv/、Agent、UserActivityGuard 之后都跑一遍。
#   guard_test.sh  用户活动守卫验收（暂停/恢复/焦点回主屏）
#   back_test.sh   返回键：带 displayId 的 KeyEvent 必须只作用于虚拟屏
#   ime_test.sh    输入法隔离：键盘弹出时对虚拟屏下达一串动作，用户的输入目标不能变（现在由守卫暂停动作来保证）
# 前提：debug 版已安装，Shizuku 已运行并授权。任何一项失败则退出码为 1。
cd "$(dirname "$0")" || exit 2
FAIL=0
for t in guard_test back_test ime_test; do
  echo "=================== $t"
  ./$t.sh || FAIL=1
done
echo "=================== 回归总结果: $([ $FAIL = 0 ] && echo PASS || echo FAIL)"
exit $FAIL
