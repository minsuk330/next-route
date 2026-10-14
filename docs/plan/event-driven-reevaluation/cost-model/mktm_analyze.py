"""mkTm 실측 분석: 공급자 갱신 주기(노선 단위 mkTm 이 바뀌는 간격), 관측 지연(받은 시각 - mkTm),
정류장 단위 '판정이 쓰는 값'(첫 차량·상태·도착초)이 바뀌는 간격."""
import json, sys, statistics as st
from collections import defaultdict
from datetime import datetime, timezone, timedelta
KST = timezone(timedelta(hours=9))
def ts(s):
    return datetime.strptime(s[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST).timestamp()
by_route = defaultdict(list)
for l in open(sys.argv[1]):
    d = json.loads(l)
    if "items" in d and d["items"]:
        by_route[d["route"]].append(d)
def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs)-1, int(p/100*len(xs)))] if xs else None
for r, snaps in by_route.items():
    mk_changes, lags, prev_mk = [], [], None
    first_fp_change = defaultdict(list); prev_fp = {}; last_change_t = {}
    for d in snaps:
        mks = sorted({it["mkTm"] for it in d["items"] if it["mkTm"]})
        mk = max(ts(m) for m in mks) if mks else None
        if mk:
            lags.append(d["t"] - mk)
            if prev_mk is not None and mk != prev_mk:
                mk_changes.append(mk - prev_mk)
            prev_mk = mk
        for it in d["items"]:
            k = (it["stId"], it["seq"])
            fp = (it["v1"], it["msg1"].split("[")[0] if it["msg1"] else "", int(it["s1"] or 0) // 30)
            if k in prev_fp and prev_fp[k] != fp:
                if k in last_change_t:
                    first_fp_change[k].append(d["t"] - last_change_t[k])
                last_change_t[k] = d["t"]
            elif k not in prev_fp:
                last_change_t[k] = d["t"]
            prev_fp[k] = fp
    gaps = [g for v in first_fp_change.values() for g in v]
    print(f"route {r}: snaps={len(snaps)} stops={len(prev_fp)} distinct mkTm 간격 n={len(mk_changes)} "
          f"p50={pct(mk_changes,50)} p90={pct(mk_changes,90)} | 관측지연 p50={pct(lags,50):.1f}s p90={pct(lags,90):.1f}s | "
          f"정류장 지문 변화 간격 n={len(gaps)} p50={pct(gaps,50) and round(pct(gaps,50))}s p10={pct(gaps,10) and round(pct(gaps,10))}s")
