#!/usr/bin/env bash
# 把 docs/g07_signoff.md 渲染成一页 A4 PDF（中文字体走 PingFang SC）。
# 依赖：python3 的 markdown 包、Google Chrome。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/docs/g07_signoff.md"
OUT="${1:-$ROOT/docs/exports/G07_分发合规签字表.pdf}"
FONT_SIZE="${FONT_SIZE:-9.4}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

mkdir -p "$(dirname "$OUT")"

python3 - "$SRC" "$FONT_SIZE" <<'PY'
import sys, pathlib, markdown
src, fs = pathlib.Path(sys.argv[1]), float(sys.argv[2])
body = markdown.markdown(src.read_text(), extensions=["tables", "sane_lists"])
html = f"""<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<style>
@page {{ size: A4; margin: 11mm 12mm 10mm 12mm; }}
body {{ font-family: "PingFang SC",sans-serif; font-size: {fs}pt; line-height: 1.40; color:#202B28; margin:0; }}
h1 {{ font-size: 15.5pt; margin: 0 0 3pt; }}
h2 {{ font-size: 10.6pt; margin: 8pt 0 3pt; padding-bottom: 2pt; border-bottom:1px solid #E3E7DF; page-break-after: avoid; }}
blockquote {{ margin:5pt 0; padding:5pt 9pt; background:#F7F5EF; border-left:3px solid #BC482F; color:#40514A; font-size: {fs-0.7}pt; }}
blockquote p {{ margin:1pt 0; }}
table {{ border-collapse: collapse; width:100%; margin:3pt 0 5pt; font-size:{fs-0.6}pt; page-break-inside: avoid; }}
th,td {{ border:1px solid #D9DED6; padding:2.4pt 5pt; text-align:left; vertical-align:top; }}
th {{ background:#F3F1EA; font-weight:600; }}
code {{ font-family:"SF Mono",Menlo,monospace; font-size:{fs-0.5}pt; background:#F3F1EA; padding:0 2pt; }}
ul,ol {{ padding-left:15pt; margin:3pt 0; }} li {{ margin:0.8pt 0; }} p {{ margin:3pt 0; }}
</style></head><body>{body}</body></html>"""
pathlib.Path("/tmp/g07_signoff.html").write_text(html)
print("/tmp/g07_signoff.html")
PY

"$CHROME" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$OUT" /tmp/g07_signoff.html 2>/dev/null

python3 - "$OUT" <<'PY'
import sys, pathlib
d = pathlib.Path(sys.argv[1]).read_bytes()
pages = d.count(b"/Type /Page") - d.count(b"/Type /Pages")
print(f"{sys.argv[1]}: {pages} 页, {len(d)//1024} KB")
PY
