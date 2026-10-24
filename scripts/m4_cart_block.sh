#!/bin/bash
# 设备上验证购物车硬拦截：未确认时点「Delete」必须被拦截（购物车不变）。会点购物车页的 Delete 按钮，所以只有被拦截才安全——
# 前提：购物车里只有测试用的商品。结果：PASS = 被拦截且购物车件数没变。
export LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/.." || exit 2
source scripts/m1_lib.sh
adb shell input keyevent KEYCODE_HOME; adb shell am force-stop com.amazon.mShop.android.shopping
boot
pa launch --es c com.amazon.mShop.android.shopping; sleep 10
perceive blk_warm false >/dev/null
clickt "Tab 3 of 5" true 9 >/dev/null
perceive blk_before false >/dev/null
before=$(grep -ao 'Subtotal ([0-9]* item[s]*)' $LOCAL/blk_before.txt | head -1)
echo "点之前：$before"
adb logcat -c; pa clicktext --es t "Delete" --ez touch true; sleep 3
res=$(adb logcat -d -s PA | grep -a -E 'BLOCKED|clickText' | grep -a -v '^\s*at ' | head -1 | sed 's/.*PA *: //')
echo "点击结果：$res"
perceive blk_after false >/dev/null
after=$(grep -ao 'Subtotal ([0-9]* item[s]*)' $LOCAL/blk_after.txt | head -1)
echo "点之后：$after"
pa stop
if echo "$res" | grep -q 'BLOCKED' && [ "$before" = "$after" ] && [ -n "$before" ]; then echo "总结果: PASS（未确认的删除被拦截，购物车没变）"; else echo "总结果: FAIL"; fi
