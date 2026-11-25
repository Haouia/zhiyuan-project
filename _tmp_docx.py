from docx import Document
d = Document(r"docs/演讲手册-企业级RAG知识引擎.docx")
print("paragraphs:", len(d.paragraphs), "tables:", len(d.tables), "inline_shapes:", len(d.inline_shapes))
for i,p in enumerate(d.paragraphs[:120]):
    t = p.text.strip()
    if t: print(i, "|", p.style.name, "|", t[:160])
