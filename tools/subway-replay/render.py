"""재생 CSV(들)를 게이지 시계열 HTML 로 그린다. 사용: render.py out.html "제목" label1=a.csv [label2=b.csv ...]"""
import csv, html, sys
from datetime import datetime

W, H, PAD_L, PAD_R, ROW_H = 1100, 230, 56, 16, 18
COLORS = {'NORMAL': '#2563eb', 'STALE': '#9ca3af'}


def load(path):
    rows = list(csv.DictReader(open(path)))
    for r in rows:
        r['_t'] = datetime.fromisoformat(r['t'].replace('Z', '+00:00'))
    return rows


def panel(label, rows, t0, t1):
    span = (t1 - t0).total_seconds() or 1
    x = lambda t: PAD_L + (W - PAD_L - PAD_R) * (t - t0).total_seconds() / span
    y = lambda v: 20 + (H - 60) * (1 - v)
    out = [f'<h2>{html.escape(label)}</h2><svg viewBox="0 0 {W} {H + 3 * ROW_H}" width="100%">']
    for v in (0, 0.25, 0.5, 0.75, 0.9, 1.0):
        out.append(f'<line x1="{PAD_L}" x2="{W - PAD_R}" y1="{y(v):.1f}" y2="{y(v):.1f}" class="grid"/>'
                   f'<text x="{PAD_L - 6}" y="{y(v) + 4:.1f}" class="ax" text-anchor="end">{v:g}</text>')
    # 다음 역이 바뀌는 곳 = 세로선 + 역 이름
    prev = None
    for r in rows:
        if r['nextStation'] != prev and r['nextStation']:
            xx = x(r['_t'])
            out.append(f'<line x1="{xx:.1f}" x2="{xx:.1f}" y1="14" y2="{H - 40}" class="seg"/>'
                       f'<text x="{xx + 3:.1f}" y="12" class="lbl">→{html.escape(r["nextStation"])}</text>')
        prev = r['nextStation']
    # 게이지 선: quality 별 색, 점 단위 polyline 조각
    seg, color = [], None
    def flush():
        if len(seg) > 1:
            out.append(f'<polyline points="{" ".join(seg)}" fill="none" stroke="{color}" stroke-width="2"/>')
    for r in rows:
        if r['shown'] in ('', '-1'):
            flush(); seg, color = [], None; continue
        c = COLORS.get(r['quality'], '#f59e0b')
        if c != color:
            flush(); seg = seg[-1:] if seg else []; color = c
        seg.append(f'{x(r["_t"]):.1f},{y(float(r["shown"])):.1f}')
    flush()
    # 띠: holdReason, push, 실제 사건(우리 열차 도착행 코드 변화)
    base = H - 30
    for r in rows:
        xx = x(r['_t'])
        if r['holdReason']:
            out.append(f'<rect x="{xx:.1f}" y="{base}" width="1.2" height="10" class="hold-{r["holdReason"]}"/>')
        if r['pushes'] not in ('', '0'):
            out.append(f'<circle cx="{xx:.1f}" cy="{base + ROW_H + 5}" r="3" class="push"><title>push {r["pushes"]} {r["t"][11:19]}</title></circle>')
    prev = None
    for r in rows:
        ev = r['ourArrival']
        if ev and ev != prev:
            xx = x(r['_t'])
            short = ' '.join(p.split('@')[0] for p in ev.split(' '))
            out.append(f'<line x1="{xx:.1f}" x2="{xx:.1f}" y1="{base + 2 * ROW_H}" y2="{base + 2 * ROW_H + 10}" class="ev"/>'
                       f'<text x="{xx + 2:.1f}" y="{base + 2 * ROW_H + 22}" class="evt" transform="rotate(30 {xx + 2:.1f} {base + 2 * ROW_H + 22})">{html.escape(short)}</text>')
        prev = ev
    for i in range(0, int(span) + 1, 60):
        t = t0.timestamp() + i
        xx = PAD_L + (W - PAD_L - PAD_R) * i / span
        out.append(f'<text x="{xx:.1f}" y="{H - 34}" class="ax" text-anchor="middle">{datetime.fromtimestamp(t).strftime("%H:%M")}</text>')
    pushes = sum(int(r['pushes'] or 0) for r in rows)
    stale = sum(1 for r in rows if r['quality'] == 'STALE')
    late = sum(1 for r in rows if r['holdReason'] == 'LATE')
    stop = sum(1 for r in rows if r['holdReason'] == 'PREV_STATION_STOPPED')
    out.append('</svg>')
    out.append(f'<p class="stat">push {pushes}건 · STALE {stale}초 · LATE {late}초 · 정차표시 {stop}초 · 총 {len(rows)}초</p>')
    return '\n'.join(out)


def main(out_path, title, specs):
    loaded = [(s.split('=', 1)[0], load(s.split('=', 1)[1])) for s in specs]
    t0 = min(r[0]['_t'] for _, r in loaded)
    t1 = max(r[-1]['_t'] for _, r in loaded)
    body = '\n'.join(panel(label, rows, t0, t1) for label, rows in loaded)
    page = f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ring Replay</title><style>
:root{{--bg:#fff;--fg:#111827;--mut:#6b7280;--grid:#e5e7eb}}
@media (prefers-color-scheme:dark){{:root:not([data-theme=light]){{--bg:#0b0f17;--fg:#e5e7eb;--mut:#9ca3af;--grid:#1f2937}}}}
:root[data-theme=dark]{{--bg:#0b0f17;--fg:#e5e7eb;--mut:#9ca3af;--grid:#1f2937}}
body{{background:var(--bg);color:var(--fg);font:14px -apple-system,system-ui,sans-serif;margin:0 auto;max-width:1160px;padding:16px}}
.grid{{stroke:var(--grid)}} .ax,.lbl,.evt{{fill:var(--mut);font-size:10px}} .seg{{stroke:var(--mut);stroke-dasharray:3 3}}
.ev{{stroke:#10b981}} .push{{fill:#ef4444}} .hold-LATE{{fill:#f59e0b}} .hold-PREV_STATION_STOPPED{{fill:#8b5cf6}} .stat{{color:var(--mut)}}
</style></head><body><h1>{html.escape(title)}</h1>
<p class="stat">파랑=NORMAL(보간) · 회색=STALE · 보라 띠=전역 정차 표시 · 주황 띠=LATE · 빨간 점=push · 초록=우리 열차 도착행 코드 변화</p>
{body}</body></html>'''
    open(out_path, 'w').write(page)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
