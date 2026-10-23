#!/bin/bash
# UV-2：把任务从虚拟屏移到主屏（交接），并保持页面状态。
#  用「设置」的子页面和「Google 日历」的新建事项草稿（输入了标题但没保存）验证：
#  移动后任务在主屏可见、虚拟屏变空、Activity 没有被销毁重建、页面状态/已输入内容保留。
source "$(dirname "$0")/lib.sh"
SET=com.android.settings
CAL=com.google.android.calendar

first_task() { tasks "$1" | head -1 | awk '{print $1}'; }
record_of() { # $1=包名片段 -> 该应用最上面 ActivityRecord 的标识
  adb shell dumpsys activity activities | grep -m1 -oE "Hist  #0: ActivityRecord\{[0-9a-f]+ u0 $1[^ ]*" | sed 's/Hist  #0: //'
}
move_and_check() { # $1=名称 $2=包名 $3=移动前页面断言文本(界面树) 
  local name=$1 pkg=$2 text=$3
  local VD T R0 R1 t0 t1
  VD=$(vdisplay); T=$(first_task "$VD")
  tree "$VD" >/dev/null; grep -q "$text" "$OUT/tree.txt"; check "${name}：移动前虚拟屏页面含「${text}」" $?
  R0=$(record_of "$pkg"); echo "  移动前 Activity：$R0"
  adb logcat -b events -c
  t0=$(date +%s%N); pa movetask --ei task "$T" --ei display 0; sleep 3; t1=$(date +%s%N)
  plog 2
  local MV; MV=$(tasks 0 | head -3)
  echo "  移动后 display 0："; echo "$MV" | sed 's/^/    /'
  echo "$MV" | head -1 | grep -q "$pkg"; check "${name}：任务出现在主屏最上面" $?
  echo "$MV" | head -1 | grep -q "visible=true"; check "${name}：主屏上该任务可见" $?
  [ -z "$(tasks "$VD")" ]; check "${name}：虚拟屏上已没有任务" $?
  R1=$(record_of "$pkg"); echo "  移动后 Activity：$R1"
  [ -n "$R0" ] && [ "$R0" = "$R1" ]; check "${name}：Activity 是同一个实例（没有被销毁重建）" $?
  adb logcat -d -b events | grep -q "wm_destroy_activity.*$pkg"; [ $? -ne 0 ]; check "${name}：事件日志里没有该应用的 wm_destroy_activity" $?
  echo "  top focused display：$(adb shell dumpsys window | grep -m1 -oE 'mTopFocusedDisplayId=[0-9]+')"
  tree 0 >/dev/null
}

echo "== 设置：子页面（Network & internet）"
boot; pa launch --es c $SET/.Settings; sleep 5
for _ in 1 2 3; do   # 进入子页（偶尔第一次点击时页面还没就绪，重试）
  clickt "Network & internet" true 3 >/dev/null
  tree "$(vdisplay)" >/dev/null; grep -q "Airplane mode" "$OUT/tree.txt" && break
done
move_and_check "设置" $SET "Airplane mode"
grep -q "Airplane mode" "$OUT/tree.txt"; check "设置：移动后主屏上仍是 Network & internet 子页（含 Airplane mode）" $?
adb shell input keyevent KEYCODE_HOME; sleep 1

echo "== 日历：新建事项草稿（已输入标题，未保存）"
boot; pa launch --es c $CAL; sleep 7
clickt "next page" true 1.5 >/dev/null; clickt "Got it" true 3 >/dev/null     # 首次引导（已完成则无影响）
clickt "Create new event" true 2.5 >/dev/null; clickt "Event" true 3 >/dev/null
typet "UV2 草稿 draft-123" >/dev/null
move_and_check "日历" $CAL "UV2 草稿 draft-123"
grep -q "UV2 草稿 draft-123" "$OUT/tree.txt"; check "日历：移动后主屏上草稿标题仍在" $?
# 清理：放弃草稿（在主屏上操作，不保存）
pat clicktext t "Cancel" --ez touch true --ei display 0; sleep 2; pat clicktext t "Discard" --ez touch true --ei display 0; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 1; adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 1

pa stop; sleep 1
echo "  停止后 ATM 显示屏：$(adb shell dumpsys activity activities | grep 'Display: mDisplayId=' | tr -d '\n ' )"
echo; [ $FAILS = 0 ] && echo "UV-2 结果: PASS" || echo "UV-2 结果: FAIL($FAILS)"; exit $FAILS
