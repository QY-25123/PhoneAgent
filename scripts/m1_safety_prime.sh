#!/bin/bash
# 设备上验证：Amazon 的 Prime 订阅按钮（文字只是 Submit）被硬拦截，而 No thanks 不受影响。
# 只点：Cart 标签、Proceed to checkout、No thanks（放行的）；对 Submit 的点击必须全部被拦截（BLOCKED），绝不能真的点中。
cd "$(dirname "$0")/.." || exit 2
source scripts/m1_lib.sh
FAIL=0
boot
pa launch --es c com.amazon.mShop.android.shopping; sleep 10
perceive safety_warm false >/dev/null   # 刚连接后第一次读界面树可能是空的，先读一次
clickt "Tab 3 of 5" true 9 >/dev/null
check_page() { # check_page NAME：感知当前页，并对其中所有可见的 Submit 按钮各点一次，期望全部 BLOCKED
  perceive $1 >/dev/null
  python3 - "$LOCAL/$1.snap.json" > $OUT/$1.targets <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for w in d["windows"]:
    for n in w["nodes"]:
        b = n["b"]
        if n.get("text") == "Submit" and n.get("clk") and n.get("vis") and b[3] - b[1] > 20:
            print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
PY
  local n=0
  while read -r x y; do
    n=$((n+1)); adb logcat -c; pa tap --ei x $x --ei y $y; sleep 1.5
    if adb logcat -d -s PA | grep -q "BLOCKED"; then echo "PASS  $1: 点击 Submit ($x,$y) 被拦截"; else echo "FAIL  $1: 点击 Submit ($x,$y) 没有被拦截"; FAIL=1; fi
  done < $OUT/$1.targets
  [ $n -gt 0 ] || { echo "FAIL  $1: 页面上没有找到 Submit 按钮"; FAIL=1; }
}
check_page safety_cart
clickt "Proceed to checkout" true 10 >/dev/null
check_page safety_popup
clickt "No thanks" true 10 | grep -q CLICKED && echo "PASS  No thanks 不受影响" || { echo "FAIL  No thanks 被误拦"; FAIL=1; }
pa stop
echo "总结果: $([ $FAIL = 0 ] && echo PASS || echo FAIL)"
exit $FAIL
