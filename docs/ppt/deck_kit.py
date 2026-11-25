# -*- coding: utf-8 -*-
"""Design kit for the 知源 deck — paper / ink / indigo editorial system.

All units are inches unless noted. Slide canvas is 13.333 x 7.5 in (16:9).
"""
from pathlib import Path

from lxml import etree
from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

# ---------------------------------------------------------------- palette
C = {
    "paper": "F6F5F1",
    "paper2": "EFEEE8",
    "surface": "FFFFFF",
    "ink": "15171C",
    "ink2": "4C525B",
    "ink3": "6C727A",
    "line": "E6E3DB",
    "lineStrong": "D8D4CA",
    "accent": "3948D4",
    "accentInk": "2A36A8",
    "accentSoft": "EDEFFC",
    "accentLight": "8C97F2",
    "success": "0E7355",
    "successSoft": "E7F3EE",
    "warning": "9A5B09",
    "warningSoft": "FBF1DF",
    "danger": "B02A37",
    "dangerSoft": "FBECEE",
    "info": "29527A",
    "infoSoft": "EEF2F8",
    "sidebar": "101318",
    "darkCard": "171B22",
    "darkLine": "2A2F38",
    "darkMuted": "9DA3AE",
    "darkInk": "F6F5F1",
    "white": "FFFFFF",
}

SANS_LATIN = "Segoe UI Variable Display"
SANS_EA = "Microsoft YaHei UI"
MONO = "Cascadia Mono"

W, H = 13.333, 7.5
M = 0.62  # page margin
TOTAL_SLIDES = 21


def rgb(hexstr):
    hexstr = hexstr.lstrip("#")
    return RGBColor.from_string(hexstr.upper())


def _set_font_elem(rpr, tag, face):
    el = rpr.find(qn(tag))
    if el is None:
        el = etree.SubElement(rpr, qn(tag))
    el.set("typeface", face)


def style_run(run, size=13, color=None, bold=False, font="sans", char_spacing=None, italic=False):
    font_obj = run.font
    font_obj.size = Pt(size)
    font_obj.bold = bold
    font_obj.italic = italic
    if color:
        font_obj.color.rgb = rgb(color)
    rpr = run._r.get_or_add_rPr()
    if font == "mono":
        _set_font_elem(rpr, "a:latin", MONO)
        _set_font_elem(rpr, "a:ea", SANS_EA)
        _set_font_elem(rpr, "a:cs", MONO)
    else:
        _set_font_elem(rpr, "a:latin", SANS_LATIN)
        _set_font_elem(rpr, "a:ea", SANS_EA)
        _set_font_elem(rpr, "a:cs", SANS_LATIN)
    if char_spacing:
        rpr.set("spc", str(int(char_spacing * 100)))


def _apply_para(p, spec, defaults):
    spec = spec if isinstance(spec, dict) else {"runs": [{"t": spec}]}
    p.alignment = spec.get("align", defaults["align"])
    p.line_spacing = spec.get("line_spacing", defaults["line_spacing"])
    if "space_before" in spec:
        p.space_before = Pt(spec["space_before"])
    p.space_after = Pt(spec.get("space_after", defaults["space_after"]))
    runs = spec.get("runs")
    if runs is None:
        runs = [{"t": spec.get("text", "")}]
    for r in runs:
        if isinstance(r, str):
            r = {"t": r}
        run = p.add_run()
        run.text = r["t"]
        style_run(
            run,
            size=r.get("size", spec.get("size", defaults["size"])),
            color=r.get("color", spec.get("color", defaults["color"])),
            bold=r.get("bold", spec.get("bold", defaults["bold"])),
            font=r.get("font", spec.get("font", defaults["font"])),
            char_spacing=r.get("char_spacing", spec.get("char_spacing")),
            italic=r.get("italic", False),
        )


def text(slide, x, y, w, h, content, *, size=13, color=None, bold=False, font="sans",
         align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, line_spacing=1.3,
         space_after=0, char_spacing=None, margins=(0, 0, 0, 0), wrap=True):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = wrap
    tf.margin_left, tf.margin_right, tf.margin_top, tf.margin_bottom = [Inches(v) for v in margins]
    tf.vertical_anchor = anchor
    tf.auto_size = None
    defaults = {
        "size": size,
        "color": color or C["ink"],
        "bold": bold,
        "font": font,
        "align": align,
        "line_spacing": line_spacing,
        "space_after": space_after,
        "char_spacing": char_spacing,
    }
    if isinstance(content, str):
        content = [content]
    for i, spec in enumerate(content):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        _apply_para(p, spec, defaults)
    return box


def _strip_style(shp):
    """Drop the theme style reference (default shadows / outlines) from a shape."""
    el = shp._element
    style = el.find(qn("p:style"))
    if style is not None:
        el.remove(style)


def box(slide, x, y, w, h, fill=None, line=None, lw=0.75, radius=None, shadow=None,
        shape=MSO_SHAPE.RECTANGLE, dash=None, transparency=None):
    if radius:
        shape = MSO_SHAPE.ROUNDED_RECTANGLE
    shp = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    _strip_style(shp)
    if radius:
        shp.adjustments[0] = max(0.0, min(0.5, radius / min(w, h)))
    if fill:
        shp.fill.solid()
        shp.fill.fore_color.rgb = rgb(fill)
        if transparency:
            shp.fill.transparency = transparency
    else:
        shp.fill.background()
    if line:
        shp.line.color.rgb = rgb(line)
        shp.line.width = Pt(lw)
        if dash is not None:
            shp.line.dash_style = dash
    else:
        shp.line.fill.background()
    if shadow:
        add_shadow(shp, **shadow)
    return shp


def add_shadow(shp, blur=10, dist=3, direction=5400000, alpha=7, color="15171C"):
    """blur/dist in points."""
    spPr = shp._element.spPr
    for old in spPr.findall(qn("a:effectLst")):
        spPr.remove(old)
    effect = etree.SubElement(spPr, qn("a:effectLst"))
    sh = etree.SubElement(effect, qn("a:outerShdw"))
    sh.set("blurRad", str(int(blur * 12700)))
    sh.set("dist", str(int(dist * 12700)))
    sh.set("dir", str(direction))
    sh.set("algn", "ctr")
    sh.set("rotWithShape", "0")
    clr = etree.SubElement(sh, qn("a:srgbClr"))
    clr.set("val", color)
    etree.SubElement(clr, qn("a:alpha")).set("val", str(int(alpha * 1000)))


def line(slide, x1, y1, x2, y2, color=None, width=1.0, dash=None, head=False, tail=False):
    conn = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1),
                                      Inches(x2), Inches(y2))
    _strip_style(conn)
    conn.line.color.rgb = rgb(color or C["line"])
    conn.line.width = Pt(width)
    if dash is not None:
        conn.line.dash_style = dash
    ln = conn.line._get_or_add_ln()
    if head:
        he = etree.SubElement(ln, qn("a:headEnd"))
        he.set("type", "triangle")
        he.set("w", "med")
        he.set("len", "med")
    if tail:
        te = etree.SubElement(ln, qn("a:tailEnd"))
        te.set("type", "triangle")
        te.set("w", "med")
        te.set("len", "med")
    return conn


def arrow(slide, x1, y1, x2, y2, color=None, width=1.2, dash=None):
    return line(slide, x1, y1, x2, y2, color=color, width=width, dash=dash, tail=True)


def chip(slide, x, y, w, h, label, fg=None, bg=None, border=None, size=9, bold=True,
         mono=False, radius=0.06, line_width=0.75):
    box(slide, x, y, w, h, fill=bg, line=border, lw=line_width, radius=radius)
    text(slide, x, y, w, h, label, size=size, color=fg or C["ink2"], bold=bold,
         font="mono" if mono else "sans", align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE,
         line_spacing=1.0)
    return (x, y, w, h)


def pic(slide, path, x, y, w=None, h=None):
    path = str(path)
    if w is not None and h is None:
        with Image.open(path) as im:
            h = w * im.size[1] / im.size[0]
    elif h is not None and w is None:
        with Image.open(path) as im:
            w = h * im.size[0] / im.size[1]
    return slide.shapes.add_picture(path, Inches(x), Inches(y), Inches(w), Inches(h))


def bg(slide, color):
    box(slide, 0, 0, W, H, fill=color)


# ---------------------------------------------------------------- chrome
def header(slide, eyebrow, title, subtitle=None, dark=False, title_size=30, top=0.40,
           eyebrow_color=None):
    ink = C["darkInk"] if dark else C["ink"]
    muted = C["darkMuted"] if dark else C["ink3"]
    text(slide, M, top, 11.8, 0.24,
         [{"runs": [{"t": eyebrow}], "char_spacing": 1.6}],
         size=10, color=eyebrow_color or (C["accentLight"] if dark else C["accentInk"]), bold=True)
    text(slide, M, top + 0.24, 12.1, 0.62, title, size=title_size, color=ink, bold=True,
         line_spacing=1.12)
    y = top + 0.24 + title_size / 72.0 * 1.32
    if subtitle:
        text(slide, M, y, 11.9, 0.30, subtitle, size=12.5, color=muted, line_spacing=1.25)
        y += 0.34
    return y


def footer(slide, num, dark=False, total=TOTAL_SLIDES, label="知源 · 企业级 RAG 知识平台"):
    line_color = C["darkLine"] if dark else C["line"]
    muted = C["darkMuted"] if dark else C["ink3"]
    line(slide, M, 7.08, W - M, 7.08, color=line_color, width=0.75)
    text(slide, M, 7.14, 7.0, 0.22, label, size=8.5, color=muted, line_spacing=1.0)
    text(slide, W - M - 1.2, 7.14, 1.2, 0.22, f"{num:02d} / {total}", size=8.5, color=muted,
         align=PP_ALIGN.RIGHT, line_spacing=1.0, font="mono")


def notes(slide, body):
    slide.notes_slide.notes_text_frame.text = body


def new_slide(prs, bg_color="paper"):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    bg(slide, C[bg_color])
    return slide


def new_deck():
    prs = Presentation()
    prs.slide_width = Inches(W)
    prs.slide_height = Inches(H)
    return prs


def mono_value(slide, x, y, w, value, label, color=None, value_size=30, label_size=9.5,
               dark=False):
    text(slide, x, y, w, 0.52, value, size=value_size, color=color or (C["darkInk"] if dark else C["ink"]),
         bold=True, font="mono", line_spacing=1.0)
    text(slide, x, y + 0.50, w, 0.24, label, size=label_size,
         color=C["darkMuted"] if dark else C["ink3"], line_spacing=1.0)


# ---------------------------------------------------------------- diagrams
def flow_node(slide, cx, y, num, title, body, color, dark=False, title_size=13.2,
              body_size=9.8, width=2.05, box_h=1.55):
    """A rail node: numbered badge on the rail, title/body below."""
    box(slide, cx - 0.17, y - 0.17, 0.34, 0.34, fill=color, line=None, radius=0.17)
    text(slide, cx - 0.17, y - 0.17, 0.34, 0.34, str(num), size=9.5, color=C["white"],
         bold=True, font="mono", align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, line_spacing=1.0)
    line(slide, cx, y + 0.17, cx, y + 0.43, color=color, width=0.9)
    top = y + 0.45
    text(slide, cx - width / 2, top, width, 0.52, title, size=title_size,
         color=C["darkInk"] if dark else C["ink"], bold=True, line_spacing=1.15,
         align=PP_ALIGN.CENTER)
    if body:
        text(slide, cx - width / 2, top + 0.50, width, box_h, body, size=body_size,
             color=C["darkMuted"] if dark else C["ink2"], line_spacing=1.35,
             align=PP_ALIGN.CENTER)
