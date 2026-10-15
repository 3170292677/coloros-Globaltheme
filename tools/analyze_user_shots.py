# -*- coding: utf-8 -*-
"""分析用户提供的两张截图：
1. 堆叠视图：逐卡测量左上/左下圆角半径，找「小折角」(边缘轮廓上的台阶/凸起)
2. 侧滑红框区：采样颜色，确认是不是系统灰而不是卡片磨砂底
"""
import sys
from PIL import Image

def is_card(px):
    r, g, b = px[0], px[1], px[2]
    # 卡片是白色/浅色磨砂，壁纸是灰 (~140) 或深色
    return r > 175 and g > 175 and b > 175

def analyze_stacked(path):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    pix = im.load()
    print("== %s  %dx%d" % (path, W, H))
    # 沿若干水平线找卡片左边缘 x(y)
    # 先按行扫描，统计每行卡片区间的数量，确定每张卡的 y 范围
    rows = []
    for y in range(0, H, 2):
        xs = [x for x in range(0, W, 2) if is_card(pix[x, y])]
        if len(xs) > 20:
            rows.append((y, min(xs), max(xs)))
    # 聚成卡块（y 连续段）
    blocks = []
    cur = None
    for y, x0, x1 in rows:
        if cur and y - cur[1] <= 6:
            cur[1] = y
            cur[2] = min(cur[2], x0)
            cur[3] = max(cur[3], x1)
        else:
            if cur:
                blocks.append(cur)
            cur = [y, y, x0, x1]
    if cur:
        blocks.append(cur)
    print("cards found: %d" % len(blocks))
    for i, (y0, y1, x0, x1) in enumerate(blocks):
        h = y1 - y0
        if h < 40:
            continue
        print("-- card %d: y=%d..%d (h=%d) x=%d..%d" % (i, y0, y1, h, x0, x1))
        # 左边缘轮廓 x(y)，步长1
        prof = []
        for y in range(y0, min(y1 + 1, H)):
            xm = None
            for x in range(max(0, x0 - 30), x0 + 80):
                if is_card(pix[x, y]):
                    xm = x
                    break
            prof.append((y, xm))
        # 顶部圆角半径：从 y0 往下，直到 x 收敛到稳定值
        stable = None
        for y, xm in prof:
            if xm is None:
                continue
            if stable is None:
                stable = xm
        # 估计半径：找到 x == stable 的第一个 y（即弧线贴合直边的位置）
        ys_full = None
        for y, xm in prof:
            if xm is not None and ys_full is not None and xm <= ys_full:
                pass
        # 简化：半径 = 第一个达到 stable 宽度的 y - y0
        top_r = None
        for y, xm in prof:
            if xm is not None and xm <= stable + 1:
                top_r = y - y0
                break
        bot_r = None
        for y, xm in reversed(prof):
            if xm is not None and xm <= stable + 1:
                bot_r = y1 - y
                break
        print("   left-edge stable x=%s  top_r~%s  bottom_r~%s" % (stable, top_r, bot_r))
        # 找「折角」：轮廓上 x 比 stable 小但之后又回到 stable 的凹陷/台阶
        # 即弧线未单调收敛
        notch = []
        prev = None
        for y, xm in prof:
            if xm is None:
                continue
            if prev is not None and xm > prev + 2:
                notch.append((y, prev, xm))
            prev = xm
        if notch:
            print("   !! notch/step at:", notch[:8])
        # 右边缘同理
        profR = []
        for y in range(y0, min(y1 + 1, H)):
            xm = None
            for x in range(min(W - 1, x1 + 30), x1 - 80, -1):
                if is_card(pix[x, y]):
                    xm = x
                    break
            profR.append((y, xm))
        stableR = None
        for y, xm in profR:
            if xm is not None:
                stableR = xm
                break
        notchR = []
        prev = None
        for y, xm in profR:
            if xm is None:
                continue
            if prev is not None and xm < prev - 2:
                notchR.append((y, prev, xm))
            prev = xm
        if notchR:
            print("   !! right notch/step at:", notchR[:8])

def sample(path, pts):
    im = Image.open(path).convert("RGB")
    pix = im.load()
    print("== %s" % path)
    for (x, y, label) in pts:
        if 0 <= x < im.size[0] and 0 <= y < im.size[1]:
            print("   (%d,%d) %s -> %s" % (x, y, label, pix[x, y]))

if __name__ == "__main__":
    stacked = r"C:\Users\Administrator\AppData\Local\CodeBuddyExtension\Data\2b13b5cc-6bb2-446f-9f54-fcf0b9793935\VSCode\2b13b5cc-6bb2-446f-9f54-fcf0b9793935\history\ce867ff88de2c6414fc97af220b6757a\361bc0dc606547bbad4999942317f266\assets\390767dbd694be7098dc2f7d0cd6bdc3.0cc223732a.jpg"
    menu = r"C:\Users\Administrator\AppData\Local\CodeBuddyExtension\Data\2b13b5cc-6bb2-446f-9f54-fcf0b9793935\VSCode\2b13b5cc-6bb2-446f-9f54-fcf0b9793935\history\ce867ff88de2c6414fc97af220b6757a\361bc0dc606547bbad4999942317f266\assets\914d358a19ebbde7b55b69a421356ab4.80a7ee7387.jpeg"
    analyze_stacked(stacked)
    # 红框区域大致在第二张图 x~270-470, y~270-390（红框本身），采样框内与卡片对比
    sample(menu, [
        (330, 325, "menu bg left of trash"),
        (430, 325, "menu bg right of gear"),
        (379, 285, "menu bg top middle"),
        (379, 370, "menu bg bottom middle"),
        (150, 325, "card area (truncated text)"),
        (150, 200, "card above (QQ card)"),
        (600, 325, "wallpaper right of menu"),
    ])
