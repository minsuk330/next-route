"""app-like top5 (snap + 본검색 + 고유<5 이면 지하철/버스 추가) 비교. usage: corp_sw.py cases.json when label:port:radius [label:port:radius ...]"""
import json, sys, urllib.request, re, math, os
ALL = ["AIRPLANE","BUS","CABLE_CAR","CARPOOL","COACH","FERRY","FUNICULAR","GONDOLA","MONORAIL","RAIL","SNOW_AND_ICE","SUBWAY","TAXI","TRAM","TROLLEYBUS"]
SUB = ["SUBWAY","MONORAIL","TRAM","RAIL","FUNICULAR"]; BUS = ["BUS","COACH","TROLLEYBUS"]
def modes(lst): return {"transit": {"transit": [({"mode": m, "cost": {"reluctance": float(os.environ.get("BUSREL", "1.3"))}} if m == "BUS" else {"mode": m}) for m in lst]}}
Q = '''query($o:PlanLabeledLocationInput!,$d:PlanLabeledLocationInput!,$w:OffsetDateTime!,$m:PlanModesInput,$sw:Duration){planConnection(origin:$o,destination:$d,dateTime:{earliestDeparture:$w},first:20,searchWindow:$sw,modes:$m){
 routingErrors{code} edges{node{start end duration walkTime generalizedCost legs{mode duration distance route{shortName} from{name} to{name}}}}}}'''
W = "2026-10-14T14:45:00+09:00"
def post(port, q, v):
    r = urllib.request.Request(f"http://localhost:{port}/otp/routers/default/index/graphql", json.dumps({"query": q, "variables": v}).encode(), {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=90))
def loc(x): return {"location": {"stopLocation": {"stopLocationId": x}}} if isinstance(x, str) else {"location": {"coordinate": {"latitude": x[0], "longitude": x[1]}}}
PAT = re.compile(r"^([^\s()]+?)(?:\([^)]*\))?역?(?:\s*\(([^)]*)\))?(?:\s+(?:(\S*(?:호선|선|라인|철도)|GTX-?[A-Z])|KTX|SRT|ITX))?$")
LIP = re.compile(r"\S*(?:호선|선|라인)|GTX-?[A-Z]")
def base(n):
    n = re.sub(r"\(.*?\)", "", n or "").strip(); return n[:-1] if len(n) > 1 and n.endswith("역") else n
def dist(a, b, c, d):
    x = math.radians(d - b) * math.cos(math.radians((a + c) / 2)); y = math.radians(c - a); return 6371000 * math.hypot(x, y)
PLS = {}
def platforms(port):
    if port not in PLS:
        ST = post(port, "{ stations { gtfsId name stops { gtfsId name lat lon vehicleMode } } }", {})["data"]["stations"]
        out = []
        for s in ST:
            al = s["gtfsId"].split(":", 1)[-1].startswith("VSA_")
            for p in s["stops"]:
                ok = p["gtfsId"].split(":", 1)[-1].startswith("VSA_P_") if al else p["vehicleMode"] in SUB
                if ok: out.append((s["gtfsId"], base(s["name"]), {base(s["name"]), base(p["name"])}, p["lat"], p["lon"], al))
        PLS[port] = out
    return PLS[port]
def snap(port, pt, name, radius):
    m = PAT.match((name or "").strip())
    if not m: return None
    key = m.group(1); has_line = m.group(3) is not None or (m.group(2) is not None and LIP.fullmatch(m.group(2).strip()) is not None)
    def nearest(want_all):
        best = None; bd = radius
        for sid, stn, names, la, lo, al in platforms(port):
            if al == want_all and key in names:
                dd = dist(pt[0], pt[1], la, lo)
                if dd <= bd: best, bd = (sid, stn), dd
        return best
    return (None if has_line else nearest(True)) or nearest(False)
def fp(n): return tuple((l["route"] or {}).get("shortName") for l in n["legs"] if l["mode"] != "WALK")
def search(port, o, d):
    def one(m):
        r = post(port, Q, {"o": loc(o), "d": loc(d), "w": W, "m": m, "sw": "PT30M"})
        if "errors" in r: return None, r["errors"][0].get("message", "")[:80]
        pc = r["data"]["planConnection"]
        if not pc["edges"] and any(e["code"] == "NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW" for e in pc["routingErrors"]):
            r = post(port, Q.replace(",searchWindow:$sw", "").replace(",$sw:Duration", ""), {"o": loc(o), "d": loc(d), "w": W, "m": m}); pc = r["data"]["planConnection"]
        return [e["node"] for e in pc["edges"]], ",".join(e["code"] for e in pc["routingErrors"])
    def distinct(E):
        reps = {}
        for i, n in enumerate(E):
            k = fp(n)
            if k not in reps or n["end"] < reps[k][1]["end"]: reps[k] = (i, n)
        return [n for i, n in sorted(reps.values(), key=lambda t: (t[1]["generalizedCost"], t[0]))]
    E, err = one(modes(ALL))
    if E is None: return [], "ERR " + err
    if len(distinct(E)) < 5:
        E2, _ = one(modes(SUB)); E += E2 or []
        if len(distinct(E)) < 5:
            E3, _ = one(modes(BUS)); E += E3 or []
    return distinct(E)[:5], err
def fmt(n):
    nm = ">".join((x or "").replace("서울", "") for x in fp(n)) or "도보"
    return f"{nm} {n['duration']//60}({n['walkTime']//60})"
