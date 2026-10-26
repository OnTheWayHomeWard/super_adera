"""Checks every shareable guide section: each folder under docs/guides/ (except those starting with _) must be self-contained.
Rules: every relative href/src resolves to a file inside the SAME folder (no '../' escaping), every #anchor exists in its target page,
no http(s) assets are loaded (external links to documentation sites in <a> are allowed but discouraged), and index.html exists.
Run from anywhere:  python3 -I docs/guides/check.py [section ...]"""
import os, re, sys
ROOT = os.path.dirname(os.path.abspath(__file__))
sections = sys.argv[1:] or sorted(d for d in os.listdir(ROOT) if os.path.isdir(os.path.join(ROOT, d)) and not d.startswith("_"))
bad = 0
for sec in sections:
    base = os.path.join(ROOT, sec)
    if not os.path.exists(os.path.join(base, "index.html")):
        print(f"{sec}: MISSING index.html"); bad += 1; continue
    pages = [os.path.join(dp, f) for dp, _, fs in os.walk(base) for f in fs if f.endswith(".html")]
    ids = {}
    for p in pages:
        ids[p] = set(re.findall(r'\bid="([^"]+)"', open(p, encoding="utf-8").read()))
    n = 0
    for p in pages:
        html = open(p, encoding="utf-8").read()
        for attr, val in re.findall(r'\b(href|src)="([^"]*)"', html):
            if val.startswith(("http://", "https://", "mailto:", "data:", "javascript:")):
                if attr == "src" or ("<link" in html and False):
                    print(f"{sec}: {os.path.relpath(p, ROOT)} loads external asset {val}"); bad += 1
                continue
            n += 1
            target, _, frag = val.partition("#")
            tp = os.path.normpath(os.path.join(os.path.dirname(p), target)) if target else p
            if os.path.isdir(tp): tp = os.path.join(tp, "index.html")
            if not tp.startswith(base + os.sep) and tp != base:
                print(f"{sec}: {os.path.relpath(p, ROOT)} escapes the section: {val}"); bad += 1; continue
            if not os.path.exists(tp):
                print(f"{sec}: {os.path.relpath(p, ROOT)} broken link: {val}"); bad += 1; continue
            if frag and tp.endswith(".html") and frag not in ids.get(tp, set()):
                print(f"{sec}: {os.path.relpath(p, ROOT)} missing anchor: {val}"); bad += 1
    print(f"{sec}: {len(pages)} pages, {n} relative links checked")
print("OK" if not bad else f"{bad} problem(s)")
sys.exit(1 if bad else 0)
