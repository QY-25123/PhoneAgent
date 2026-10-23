#!/usr/bin/env python3
"""把 local-samples/ 里录制的原始样本（含个人信息）脱敏后写到测试资源目录。

用法: scripts/m1_sanitize.py NAME [NAME...]
  输入  local-samples/NAME.snap.json、NAME.png   （原始，git 忽略，永不提交）
  输出  app/src/test/resources/perception/NAME.snap.json  脱敏快照：结构、类名、资源 id、边界、操作标志原样保留
        app/src/test/resources/perception/NAME.png         涂灰截图：被改写过的文字/图片所在区域涂成灰块，缩到 720px 宽

规则是**白名单**：只有明确属于界面固定文案（按钮名、标签、日期时间、价格、数量描述等）的字符串原样保留，
其余一律换成等长的占位文字（保留长度，截断逻辑的测试还能用）；邮箱、姓名、地址、卡号等专门替换。
"""
import json, re, sys, os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "local-samples")
DST = os.path.join(ROOT, "app/src/test/resources/perception")

KEEP_EXACT = {s.lower() for s in """
Show Calendar List and Settings drawer|October, Show date picker|Search|Jump to Today|Nothing planned. Tap to create.|
Create new event and more|Cancel|CANCEL|Collapse event sheet|Save|Add title|All-day|Does not repeat|Add people|View schedules|
Add video conferencing|Add location|Add notification|Remove notification|Default color|Add description|Add attachment|Back|
Pacific Daylight Time|Search in mail|Open navigation drawer|Primary|Dismiss|Dismiss tip|More options|Compose|Meet|Navigate up|
Attach file|Send|From|To|Add Cc/Bcc|Subject|Compose email|Help me write|Promotions|99+ new|Mail, More than 99 new notifications|
Unsubscribe|No thanks|Cart|Lists|Buy Again|Keep shopping for|Search or ask a question|scan it|Shopping Cart|All Carts|
Proceed to checkout|Place your order|Join Prime|Join Prime Young Adults for 18-24 year olds.|Deselect all items|Saved for later|In Stock|
Back to cart|Items:|Order total:|Shipping & handling:|Estimated tax to be collected:|Change payment method|Change delivery address|
Choose your delivery option:|Use a gift card, points, voucher, or promo code|Add gift options|Add delivery instructions|
Pickup near this address|Submit|Without Prime|With Prime|Select a payment method for Prime|Amazon Prime Terms|privacy notice|
conditions of use|and|.|).|here|this page|Add|Pay|Get|off|for|Amazon Visa|See details|FREE Returns|FREE delivery|Minimum quantity reached, Delete item|
Increase item quantity|Default to this delivery address and payment method.|Not interested in specific items|1|•|&|Sold by|
Ships from Amazon.com|Alexa Ask a question|Amazon Prime Logo|Get faster delivery|Pickup here|for this order.|
Fees & taxes calculated at checkout|Use Prime for Young Adults cash back|Sustainability features
""".replace("\n", "").split("|") if s.strip()}

# 日期、时间、价格、数量等：不是个人信息的固定格式文案
KEEP_REGEX = [re.compile(p) for p in [
    r"^(Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day,? \d{1,2} \w+ \d{4}, Open (Day|Schedule) View$",
    r"^(Start|End) (date|time): .+$", r"^New event: .+$",
    r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \w{3} \d{1,2}, \d{4}$", r"^\d{1,2}:\d{2} (AM|PM)$",
    r"^\w{3} \d{1,2} – (\w{3} )?\d{1,2}$", r"^(January|February|March|April|May|June|July|August|September|October|November|December)( \d{4})?$",
    r"^[\w' ]+, All day:$", r"^\d+ minutes before$",
    r"^\d{1,2}:\d{2} (AM|PM)$", r"^\w{3} \d{1,2}$",
    r"^\$[\d,.]+( available)?$", r"^Subtotal \(\d+ items?\): \$[\d,.]+$", r"^\d[\d.,K+]* out of 5 stars$", r"^[\d,]+$", r"^\d+K\+ bought in past month$",
    r"^[A-Za-z ]+ (Button|Tab) \d of \d$", r"^[A-Za-z. ]+ \d+ items? Tab \d of \d$",
    r"^(Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day, \w+\.? \d{1,2}$", r"^\w{3}, \w{3} \d{1,2}$", r"^Arriving by \w{3} \d{1,2}, \d{4}$",
    r"^\$[\d,.]+ (Delivery|Two Day Delivery)$", r"^[\d.]+ mi$",
]]

# 前缀是固定文案、后面是商品名的：保留前缀，商品名换成占位
PRODUCT_PREFIX = re.compile(
    r"^(Delete|Select|Save|Share|Compare|Move to cart|View more actions for|Add to cart,|Increase quantity by one, Quantity is \d+,|"
    r"Join Prime to buy this item at) (.+)$", re.S)

EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")

def norm(s): return re.sub(r"\s+", " ", s).strip()

def filler(n):
    base = "Sample text "
    return (base * (n // len(base) + 1))[:max(1, n)].rstrip() or "x"

def sanitize(s):
    """返回 (新字符串, 是否改写)。"""
    n = norm(s)
    if not n: return s, False
    t = EMAIL.sub("test.user@example.com", n)
    if re.match(r"^Signed in as .*Account and settings\.?$", t, re.S):
        return "Signed in as Test User test.user@example.com\nAccount and settings.", True
    if n.lower() in KEEP_EXACT or any(r.match(n) for r in KEEP_REGEX):
        return s, False
    m = PRODUCT_PREFIX.match(n)
    if m:
        return f"{m.group(1)} Sample Product Title", True
    if re.match(r"^Paying with \w+ \d{4}", n): return n.replace(re.search(r"\d{4}", n).group(0), "0000").replace("Mastercard", "Visa"), True
    if re.match(r"^Visa\*+\d{4}$", n): return "Visa****0000", True
    if re.match(r"^Delivering to ", n): return "Delivering to Test User", True
    if re.match(r"^\d+ .*(AVE|ST|RD|BLVD).*\d{5}", n, re.I): return "1 Sample St, Sampleville, CA, 00000, United States", True
    if re.match(r"^\w+, enjoy a 7-day trial", n): return "Test, enjoy a 7-day trial of Prime for only $0.99!", True
    return filler(len(n)), True

def main(names):
    os.makedirs(DST, exist_ok=True)
    for name in names:
        snap = json.load(open(f"{SRC}/{name}.snap.json"))
        redact = []
        for w in snap["windows"]:
            if w.get("title"): w["title"] = sanitize(w["title"])[0]
            for n in w["nodes"]:
                changed = False
                for k in ("text", "desc", "hint"):
                    if k in n and n[k]:
                        n[k], c = sanitize(n[k]); changed |= c
                cls = n.get("cls", "")
                if changed or (("ImageView" in cls or "ImageButton" in cls) and "text" not in n and "desc" not in n and n["b"][2] - n["b"][0] > 60):
                    redact.append(n["b"])
        out = json.dumps(snap, ensure_ascii=False, separators=(",", ":"))
        assert not EMAIL.search(out.replace("test.user@example.com", "")), f"{name}: 仍含邮箱"
        open(f"{DST}/{name}.snap.json", "w", encoding="utf-8").write(out)
        img = Image.open(f"{SRC}/{name}.png").convert("RGB")
        d = ImageDraw.Draw(img)
        for l, t, r, b in redact:
            if r > l and b > t: d.rectangle([l, t, r, b], fill=(128, 128, 128))
        img = img.resize((720, int(img.height * 720 / img.width)), Image.LANCZOS)
        img.save(f"{DST}/{name}.png", optimize=True)
        print(f"{name}: 节点 {sum(len(w['nodes']) for w in snap['windows'])}，涂灰区域 {len(redact)}，{len(out)//1024}KB")

if __name__ == "__main__":
    main(sys.argv[1:])
