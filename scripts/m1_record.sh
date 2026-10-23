#!/bin/bash
# 重新录制 M1 的 7 个样本页到 local-samples/（原始数据，含个人信息，git 忽略）。之后用 scripts/m1_sanitize.py 脱敏。
# 只读操作：不保存日历事项、不发送邮件；Amazon 只到提交订单页，只点 Cart 标签、Proceed to checkout、No thanks。
cd "$(dirname "$0")/.." || exit 2
source scripts/m1_lib.sh
boot
pa launch --es c com.google.android.gm; sleep 7;  perceive gm_inbox
clickt "Compose" true 4;                          perceive gm_compose
pa back; sleep 2
pa launch --es c com.google.android.calendar; sleep 8;  perceive cal_main
clickt "Create" true 4;                           perceive cal_new_event
pa launch --es c com.amazon.mShop.android.shopping; sleep 10
clickt "Tab 3 of 5" true 9;                       perceive amz_cart
clickt "Proceed to checkout" true 10;             perceive amz_after_proceed
clickt "No thanks" true 10;                       perceive amz_checkout
pa stop
