#!/bin/bash
# 验证日历里确实有这个事项：scripts/m2_verify_event.sh "标题片段" "开始时间 yyyy-MM-dd HH:mm" "结束时间 yyyy-MM-dd HH:mm"
# 通过 adb 直接查日历内容提供者（时区 America/Los_Angeles，和模拟器一致）。输出 PASS/FAIL；只读。
export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
TITLE=$1; WANT_S=$2; WANT_E=$3
rows=$(adb shell "content query --uri content://com.android.calendar/events --projection _id:title:dtstart:dtend:deleted --where \"title LIKE '%$TITLE%' AND deleted=0\"" 2>/dev/null | tr -d '\r')
python3 - "$TITLE" "$WANT_S" "$WANT_E" <<PY
import sys, re, datetime, zoneinfo
title, ws, we = sys.argv[1:4]
tz = zoneinfo.ZoneInfo("America/Los_Angeles")
rows = '''$rows'''
found = []
for l in rows.splitlines():
    m = re.search(r"_id=(\d+), title=(.*), dtstart=(\d+), dtend=(\d+)", l)
    if m: found.append((int(m.group(1)), m.group(2), datetime.datetime.fromtimestamp(int(m.group(3)) / 1000, tz).strftime("%Y-%m-%d %H:%M"), datetime.datetime.fromtimestamp(int(m.group(4)) / 1000, tz).strftime("%Y-%m-%d %H:%M")))
ok = [f for f in found if f[2] == ws and f[3] == we]
print(("PASS" if len(ok) == 1 else "FAIL") + f"  找到 {len(found)} 个标题含「{title}」的事项，时间匹配 {len(ok)} 个；期望 {ws} → {we}")
for f in found: print("   ", f)
sys.exit(0 if len(ok) == 1 else 1)
PY
