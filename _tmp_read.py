import json, glob, os
p = sorted(glob.glob(r"eval/reports/*.json"), key=os.path.getmtime)[-1]
d = json.load(open(p, encoding="utf-8"))
print("LATEST:", p)
print(json.dumps(d, ensure_ascii=False, indent=1)[:3000])
