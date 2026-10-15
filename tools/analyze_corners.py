# -*- coding: utf-8 -*-
"""精确测量堆叠卡片四角半径 + 找折角台阶。"""
from PIL import Image

def is_card(px):
    r, g, b = px[0], px[1], px[2]
    return r > 175 and g > 175 and b > 175

def measure(path):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    pix = im.load()
    print("== %s  %dx%d" % (path.split('\\')[-1], W, H))
    rows = []
    for y in range(0, H):
        xs = [x for x in range(0, W) if is_card(pix[x, y])]
        if len(xs) > 30:
            rows.append((y, min(xs), max(xs)))
    blocks = []
    cur = None
    for y, x0, x1 in rows:
        if cur and y - cur[1] <= 8:
            cur[1] = y
            cur[2] = min(cur[2], x0)
            cur[3] = max(cur[3], x1)
        else:
            if cur:
                blocks.append(cur)
            cur = [y, y, x0, x1]
    if cur:
        blocks.append(cur)
    cards = [b for b in blocks if b[1] - b[0] >= 40]
    print("cards: %d" % len(cards))
    for i, (y0, y1, x0, x1) in enumerate(cards):
        h = y1 - y0
        # 左边缘轮廓
        prof = []
        for y in range(y0, y1 + 1):
            xm = None
            for x in range(max(0, x0 - 25), x0 + 120):
                if is_card(pix[x, y]):
                    xm = x
                    break
            if xm is not None:
                prof.append((y, xm))
        if not prof:
            continue
        xs = sorted(p[1] for p in prof)
        stable = xs[len(xs) // 2]  # 中位数 ≈ 直边位置
        # 顶角半径：从顶部往下，x 首次 <= stable+1
        top_r = next((y - y0 for y, xm in prof if xm <= stable + 1), None)
        bot_r = next((y1 - y for y, xm in reversed(prof) if xm <= stable + 1), None)
        # 折角检测：角区（顶部 top 2*R 行）里 x 是否出现「先减后增」的台阶
        R = max(top_r or 20, 12) * 2
        seg = [(y, xm) for y, xm in prof if y <= y0 + R]
        steps = []
        prev = None
        for y, xm in seg:
            if prev is not None and xm > prev + 1:
                steps.append((y, prev, xm))
            prev = xm
        segB = [(y, xm) for y, xm in prof if y >= y1 - R]
        stepsB = []
        prev = None
        for y, xm in segB:
            if prev is not None and xm > prev + 1:
                stepsB.append((y, prev, xm))
            prev = xm
        print("card%d y=%d..%d h=%d  left_x=%d  top_r=%s bottom_r=%s%s%s" % (
            i, y0, y1, h, stable, top_r, bot_r,
            ("  TOP-STEPS:" + str(steps[:6])) if steps else "",
            ("  BOT-STEPS:" + str(stepsB[:6])) if stepsB else ""))
        # 右边缘
        profR = []
        for y in range(y0, y1 + 1):
            xm = None
            for x in range(min(W - 1, x1 + 25), x1 - 120, -1):
                if is_card(pix[x, y]):
                    xm = x
                    break
            if xm is not None:
                profR.append((y, xm))
        if profR:
            xsR = sorted(p[1] for p in profR)
            stableR = xsR[len(xsR) // 2]
            top_rR = next((y - y0 for y, xm in profR if xm >= stableR - 1), None)
            bot_rR = next((y1 - y for y, xm in reversed(profR) if xm >= stableR - 1), None)
            print("        right_x=%d  top_r=%s bottom_r=%s" % (stableR, top_rR, bot_rR))

measure(r"C:\Users\Administrator\AppData\Local\CodeBuddyExtension\Data\2b13b5cc-6bb2-446f-9f54-fcf0b9793935\VSCode\2b13b5cc-6bb2-446f-9f54-fcf0b9793935\history\ce867ff88de2c6414fc97af220b6757a\361bc0dc606547bbad4999942317f266\assets\390767dbd694be7098dc2f7d0cd6bdc3.0cc223732a.jpg")
