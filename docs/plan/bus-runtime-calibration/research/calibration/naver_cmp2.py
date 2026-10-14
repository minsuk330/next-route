"""Force one route (legacy plan whiteListed) from app-like snapped stations; compare total with Naver."""
import sys, json
import naverlib as L
CASES = [
    ("잠실역 8호선", (37.5147736, 127.104666), "강남역 신분당선", (37.4964196, 127.0283493),
     {"2000": (32, ["1:BR_TAGO_GGB232000122", "1:BR_TAGO_GGB222000075"]), "360": (36, ["1:BR_TAGO_SEB100100057"]),
      "341": (42, ["1:BR_TAGO_SEB100100056"]), "3412": (48, ["1:BR_TAGO_SEB100100224"])}),
    ("강남역", (37.4979, 127.0276), "홍대입구역", (37.5572, 126.9245), {"740": (57, ["1:BR_TAGO_SEB100100537"])}),
]
Q = '''query($o:PlanLabeledLocationInput!,$d:PlanLabeledLocationInput!,$r:[String!]){planConnection(origin:$o,destination:$d,dateTime:{earliestDeparture:"2026-10-14T14:45:00+09:00"},first:10,searchWindow:"PT60M",
 modes:{transit:{transit:[{mode:BUS}]}},preferences:{transit:{filters:[{include:[{routes:$r}]}]}}){edges{node{duration walkTime legs{mode duration route{shortName gtfsId}}}}}}'''
rows = []
for tag_port in sys.argv[1:]:
    tag, port = tag_port.split(":"); port = int(port)
    for on, o, dn, d, nav in CASES:
        so, sd = L.snap(port, o, on, 300), L.snap(port, d, dn, 300)
        f = so[0] if so else o; t = sd[0] if sd else d
        for k, (nv, ids) in nav.items():
            best = None
            for rid in ids:
                r = L.post(port, Q, {"o": L.loc(f), "d": L.loc(t), "r": [rid]})
                if "errors" in r: print("ERR", r["errors"][0]["message"][:120]); continue
                for it in (e["node"] for e in r["data"]["planConnection"]["edges"]):
                    tr = [l for l in it["legs"] if l["mode"] != "WALK"]
                    if len(tr) == 1:
                        v = (it["duration"]/60, tr[0]["duration"]/60, it["walkTime"]/60)
                        if best is None or v[0] < best[0]: best = v
            print(f"{tag:5s} {on}->{dn} [{f}->{t}] {k:5s} naver {nv:3d}  ours " + (f"{best[0]:5.1f} (ride {best[1]:4.1f} walk {best[2]:4.1f}) ratio {best[0]/nv:.2f}" if best else "none"))
            rows.append(dict(tag=tag, route=k, naver=nv, ours=best[0] if best else None))
json.dump(rows, open("naver_cmp2.json", "w"), ensure_ascii=False)
