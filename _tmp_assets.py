from PIL import Image, ImageDraw
import os
out = r"docs/ppt/assets"
big = Image.open(r"artifacts/diagrams/rag-pipeline.png").convert("RGB")
print("diagram size:", big.size)
sx, sy = big.size[0]/1680.0, big.size[1]/1210.0
def d(box, name):
    im = big.crop(tuple(int(v) for v in (box[0]*sx, box[1]*sy, box[2]*sx, box[3]*sy)))
    im.save(os.path.join(out, name), optimize=True)
    print(name, im.size)

d((40, 425, 848, 842), "diagram-write.png")
d((856, 425, 1680, 852), "diagram-query.png")
d((40, 870, 1660, 1010), "diagram-governance.png")
d((40, 1015, 1660, 1140), "diagram-platform.png")
d((40, 120, 1660, 405), "diagram-access-edge.png")

# --- brand mark (512px, gradient indigo, three chevrons) ---
S = 512
mark = Image.new("RGBA", (S, S), (0,0,0,0))
grad = Image.new("RGB", (S, S))
gd = ImageDraw.Draw(grad)
for y in range(S):
    for_x = y / (S-1)
    # vertical-ish gradient + slight horizontal: approximate diagonal
    for x in range(0, S, 4):
        t = (x/(S-1)*0.35 + for_x*0.65)
        r = int(0x59 + (0x2B-0x59)*t)
        g = int(0x68 + (0x35-0x68)*t)
        b = int(0xF2 + (0x9F-0xF2)*t)
        gd.rectangle([x, y, x+3, y], fill=(r,g,b))
mask = Image.new("L", (S, S), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([0,0,S-1,S-1], radius=int(10/36*S), fill=255)
mark.paste(grad, (0,0), mask)
dr = ImageDraw.Draw(mark)
k = S/36.0
def poly(pts, fill):
    dr.polygon([(x*k, y*k) for x,y in pts], fill=fill)
# top solid chevron (diamond-ish)
poly([(9.5,13.2),(18,9),(26.5,13.2),(18,17.5)], (255,255,255,255))
# second and third chevrons (stroked)
for y, alpha in ((18.4,168),(23.4,88)):
    pts = [(9.5*k, y*k),(18*k,(y+4.3)*k),(26.5*k,y*k)]
    dr.line(pts, fill=(255,255,255,alpha), width=int(1.8*k), joint="curve")
mark.save(os.path.join(out, "brand-mark.png"))
print("brand-mark", mark.size)
