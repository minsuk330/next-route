#!/usr/bin/env python3
"""Connect every metro platform to its exits, and transfer platforms to each other, with pathways.

Without pathways OTP links a platform stop to the single nearest street.  Where that street lies on
the far side of the tracks, a trip that starts at an exit on this side walks around the railway or to
another station altogether (2026-11-17, production: 주안역 2번 출구 → 시민공원역 10.6 min on foot,
가산디지털단지역 2번 출구 → its own platform 19.1 min; 304 of 1,234 stations had such an exit).  The
same happens between platforms of one station that sit apart: 복정 수인분당선 ↔ 8호선 walked 10.3 min
where the feed says 60 s.  Name searches were fine (they start at the virtual station of
add_station_entrances.py); coordinate searches — current location, an address — were not.

For every metro platform P that add_station_entrances.py gave a station ``VST_<P>`` and exits
``VEX_<osm>_<P>``, this adds

* an entrance ``VEN_<osm>_<P>`` (``location_type=2``, child of ``VST_<P>``) at each exit, and a
  pathway entrance ↔ P.  Time = straight distance at 1.0 m/s + 60 s, at least 90 s — this
  reproduces what Kakao shows as "N번 출구까지 도보" (50 m → 2 min, 116 m → 3 min, 320 m → 6 min);
* a pathway P ↔ Q for every platform pair of ``transfers.txt`` whose two platforms both got
  entrances, with the feed's ``min_transfer_time`` (at least 60 s).  A platform that has pathways is
  no longer linked to the street by OTP, so without these a transfer would leave by one exit and
  come back in by another;
* a 1 s pathway between P and its all-lines copy ``VSA_P_<P>``, so a search from "서울역" still
  starts on the platforms as before instead of walking in from an exit.

``VEX_``/``VSA_X_`` exit stops stay as they are.  Platforms without exits (national rail, stations
OSM has no exits for) get nothing and keep their street link.  Everything else in the zip is copied
byte for byte.

OTP caveat: island pruning re-links stops but not entrances, so an entrance linked to a small street
island ends up unreachable (구로, 상록수 … in the first experiment).  The graph must be built with
the OTP build that re-links entrances after pruning (``otp-patches/``); build_otp_graph.sh refuses
``--station-pathways`` with the stock jar.

Stdlib only, like the rest of the release pipeline.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import sys
import zipfile
from pathlib import Path

csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

PARENT_PREFIX = "VST_"
EXIT_PREFIX = "VEX_"
ENTRANCE_PREFIX = "VEN_"
ALL_LINES_PLATFORM_PREFIX = "VSA_P_"
EXIT_WALK_MS = 1.0          # m/s along the straight line platform ↔ exit
EXIT_FIXED_S = 60           # stairs, gates
EXIT_MIN_S = 90
TRANSFER_MIN_S = 60
PATHWAY_HEADER = ["pathway_id", "from_stop_id", "to_stop_id", "pathway_mode", "is_bidirectional",
                  "traversal_time", "length"]


def exit_seconds(distance_m: float) -> int:
    return max(EXIT_MIN_S, round(distance_m / EXIT_WALK_MS + EXIT_FIXED_S))


def run(in_zip: Path, out_zip: Path, report_path: Path | None = None) -> dict:
    zin = zipfile.ZipFile(in_zip)
    names = {i.filename for i in zin.infolist()}
    if "pathways.txt" in names:
        raise SystemExit("feed already has pathways.txt")
    rd = csv.reader(io.TextIOWrapper(zin.open("stops.txt"), encoding="utf-8-sig", newline=""))
    hdr = next(rd)
    H = {c: i for i, c in enumerate(hdr)}
    rows = list(rd)
    by_id = {r[H["stop_id"]]: r for r in rows}

    def dist(a, b) -> float:
        la, lo = float(a[H["stop_lat"]]), float(a[H["stop_lon"]])
        lb, lob = float(b[H["stop_lat"]]), float(b[H["stop_lon"]])
        return math.hypot((la - lb) * 110540, (lo - lob) * 111320 * math.cos(math.radians(la)))

    exits: dict[str, list[list[str]]] = {}
    for r in rows:
        sid, parent = r[H["stop_id"]], r[H["parent_station"]]
        if sid.startswith(EXIT_PREFIX) and parent.startswith(PARENT_PREFIX):
            exits.setdefault(parent[len(PARENT_PREFIX):], []).append(r)

    new_stops: list[list[str]] = []
    pathways: list[list[str]] = []
    done: set[str] = set()
    exit_times: list[int] = []
    for p in sorted(exits):
        if p not in by_id:
            continue
        for r in exits[p]:
            ent = ENTRANCE_PREFIX + r[H["stop_id"]][len(EXIT_PREFIX):]
            e = list(r)
            e[H["stop_id"]], e[H["location_type"]] = ent, "2"
            new_stops.append(e)
            d = dist(r, by_id[p])
            t = exit_seconds(d)
            exit_times.append(t)
            pathways.append([f"PW_{ent}", ent, p, "1", "1", str(t), f"{d:.1f}"])
        done.add(p)

    pairs: dict[tuple[str, str], int] = {}
    if "transfers.txt" in names:
        for r in csv.DictReader(io.TextIOWrapper(zin.open("transfers.txt"), encoding="utf-8-sig", newline="")):
            a, b = r.get("from_stop_id", ""), r.get("to_stop_id", "")
            if a and b and a != b:
                key = (a, b) if a < b else (b, a)
                pairs[key] = max(TRANSFER_MIN_S, int(r.get("min_transfer_time") or 0), pairs.get(key, 0))
    transfers = skipped = 0
    for (a, b), t in sorted(pairs.items()):
        if a in done and b in done:
            pathways.append([f"PW_X_{a}_{b}", a, b, "1", "1", str(t), f"{dist(by_id[a], by_id[b]):.1f}"])
            transfers += 1
        elif a in done or b in done:
            skipped += 1        # the other platform has no exits: it stays on the street, the walk goes through an exit

    copies = 0
    for r in rows:
        sid = r[H["stop_id"]]
        if sid.startswith(ALL_LINES_PLATFORM_PREFIX) and sid[len(ALL_LINES_PLATFORM_PREFIX):] in done:
            pathways.append([f"PW_C_{sid}", sid, sid[len(ALL_LINES_PLATFORM_PREFIX):], "1", "1", "1", "0.0"])
            copies += 1

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for info in zin.infolist():
            if info.filename == "stops.txt":
                buf = io.StringIO()
                w = csv.writer(buf, lineterminator="\n")
                w.writerow(hdr)
                w.writerows(rows)
                w.writerows(new_stops)
                zout.writestr("stops.txt", buf.getvalue())
            else:
                with zin.open(info.filename) as src, zout.open(info.filename, "w", force_zip64=True) as dst:
                    while chunk := src.read(1 << 20):
                        dst.write(chunk)
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(PATHWAY_HEADER)
        w.writerows(pathways)
        zout.writestr("pathways.txt", buf.getvalue())
    tmp.replace(out_zip)

    exit_times.sort()
    summary = {
        "input": str(in_zip), "output": str(out_zip),
        "platformsWithEntrances": len(done), "entrances": len(new_stops),
        "transferPathways": transfers, "transferPairsWithOneSideOnStreet": skipped,
        "allLinesCopyPathways": copies, "pathways": len(pathways),
        "exitSeconds": ({"min": exit_times[0], "p50": exit_times[len(exit_times) // 2], "max": exit_times[-1]}
                        if exit_times else None),
    }
    if report_path is not None:
        report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    return summary


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--in", dest="in_zip", type=Path, required=True)
    p.add_argument("--out", dest="out_zip", type=Path, required=True)
    p.add_argument("--report", type=Path)
    a = p.parse_args()
    print(json.dumps(run(a.in_zip, a.out_zip, a.report), ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
