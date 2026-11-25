from PIL import Image
import os
src = r"artifacts/screenshots"
out = r"docs/ppt/assets"
os.makedirs(out, exist_ok=True)

def crop(name, box, outname, pad_bg=None):
    im = Image.open(os.path.join(src, name)).convert("RGB")
    c = im.crop(box)
    c.save(os.path.join(out, outname), optimize=True)
    print(outname, c.size)

# 1) answer card (question bubble + answer + citation card)
crop("ask-answer-1440.png", (300, 245, 1130, 552), "shot-answer.png")
# 2) citation drawer (title, version chip, range, quote, highlighted source)
crop("ask-citation-drawer-1440.png", (833, 0, 1440, 545), "shot-citation.png")
# 3) eval metrics card + trend header (top card only)
crop("admin-eval-1440.png", (296, 232, 1400, 525), "shot-eval.png")
# 4) monitoring status + top service rows
crop("admin-monitoring-1440.png", (296, 232, 1400, 520), "shot-monitor.png")
# 5) access summary + role panels
crop("admin-access-1440.png", (296, 232, 1400, 612), "shot-access.png")
# 6) document version list (from dashboard)
crop("admin-dashboard-1440.png", (296, 430, 1400, 860), "shot-docs.png")
print("done")
