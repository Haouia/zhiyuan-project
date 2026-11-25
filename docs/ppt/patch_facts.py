# -*- coding: utf-8 -*-
"""按 docs/ppt/fact-pack.md 校正演示稿中的过时数字（2026-11-25）。

背景：build_deck.py 在一次 PowerShell 文本替换中损坏（PS 5.1 双重转码），
以已生成的 pptx 为准直接修补文字，可编辑性不变。
运行前请确认 pptx 未被打开占用。
"""
from pathlib import Path

from pptx import Presentation
from pptx.util import Inches

PPTX = Path(__file__).with_name("知源-企业级RAG知识平台-项目演示.pptx")

# 段落级精确替换：key 必须是整段文本（去掉首尾空格后）
REPLACE = {
    # 08 可度量：41 份 OK 报告中有 38 份双指标达标
    "37 / 41": "38 / 41",
    # 12 证据链：契约 20+10=30；API 冒烟 106/65/97=268；E2E 28+22+15+19=84；verify_release 7 步
    "外部 18 条 + 内部 9 条，共 27 条接口契约，字段级校验":
        "外部 20 条 + 内部 10 条，共 30 条接口契约，字段级校验",
    "105 项": "268 项",
    "覆盖双租户、注册 / 邀请码 / 升降级、错误码与边界":
        "ui_api 106 · admin 65 · 注册邀请 97，覆盖双租户与错误边界",
    "26 + 19 + 21": "28 + 22 + 15 + 19",
    "105": "268",
    "66": "84",
    "11": "7",
    # 13 工程化：mysql 单实例双库；Nacos 2.3.2；K8s 无版本锁定；13 容器对齐
    "mysql-doc:3306": "mysql:3306（双库）",
    "mysql-search:3306": "model-primary:8095",
    "admin-web": "model-backup:8096",
    "Keycloak 26 · Nacos 3.2.3": "Keycloak 26 · Nacos 2.3.2",
    "K8s 1.34 部署清单": "K8s 清单：10 Deployment / 14 Pod",
}


def replace_paragraph(p, new_text):
    """保留首 run 的样式，把整段文字替换成 new_text。"""
    runs = p.runs
    if not runs:
        return False
    runs[0].text = new_text
    for r in runs[1:]:
        r.text = ""
    return True


def main():
    prs = Presentation(str(PPTX))
    hits = {}
    for idx, slide in enumerate(prs.slides, 1):
        for shape in slide.shapes:
            if not shape.has_text_frame:
                continue
            for p in shape.text_frame.paragraphs:
                key = p.text.strip()
                if key in REPLACE and REPLACE[key] != p.text:
                    if replace_paragraph(p, REPLACE[key]):
                        hits.setdefault(idx, []).append(REPLACE[key])
    # A2 页例子行上移，贴住正文（与左侧两张指标卡视觉对齐）
    s18 = prs.slides[17]
    for shape in s18.shapes:
        if not shape.has_text_frame:
            continue
        t = shape.text_frame.text.strip()
        if t.startswith("例：答案说"):
            shape.top = Inches(2.46)
        elif t.startswith("例：正确片段"):
            shape.top = Inches(4.14)
    prs.save(str(PPTX))
    print("patched:", PPTX)
    for k, v in hits.items():
        print(f"  slide {k:02d}: " + "; ".join(v))


if __name__ == "__main__":
    main()
