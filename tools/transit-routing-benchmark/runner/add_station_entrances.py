#!/usr/bin/env python3
"""Give every metro platform a station whose children are the platform and the station's exits.

The KTDB feed models every line at a station as its own platform stop (``RS_ACC1_S-1-0216`` 잠실(2호선),
``RS_ACC1_S-1-2815`` 잠실(8호선)) with no parent station or exits.  A trip from a station place therefore
starts from one platform point and walks 400-800 m to bus stops next to other exits (2026-10-12: 잠실역 8호선
→ 강남역 360 direct bus walked 672 m + 825 m; Naver walks 130 m + 97 m from the nearest exit).

OTP routes "from a station" (``stopLocation`` = a parent station) by starting at every child stop at zero
cost.  So this script adds, for each metro platform P of a station that has exits:

* a ``location_type=1`` parent ``VST_<P>`` (one per platform, not per station: a shared parent would put
  the traveller on every line's platform at once — 잠실 8호선 → 강남 by 2호선 came out with 0 walking);
* ``P.parent_station = VST_<P>``;
* one plain stop (``location_type=0``, no trips) per exit of the station, ``VEX_<osm_id>_<P>``, child of
  ``VST_<P>`` — OTP links it to the street, so the walk to a bus stop starts at the nearest exit.

No ``pathways.txt``: pathways make OTP route *through* stations (exit → platform → exit), a walking shortcut
across tracks that does not exist without passing fare gates (seen locally at 서울역·영등포), and they also
unlink platforms from the street for every coordinate search.  Platforms, transfers.txt and coordinate
searches stay exactly as before; only searches that start or end at ``VST_*`` change.

Exits come from the OSM CSV (``src/main/resources/subway/entrances.csv``).  An exit belongs to the station
group whose nearest platform is closest, within ``--exit-radius-m``.  Labels are messy ("동대문역사문화공원
1번출구", "삼성(무역센터)역 3번출구", or empty), so a label only overrides the distance choice when it names
another station in range.  Platforms are grouped by base name within ``--group-radius-m`` plus pairs in
``transfers.txt``.  National rail platforms (``RS_TAGO_N…``) are left untouched (``--metro-prefix``).

Every other file in the zip is copied byte for byte (shapes.txt, trips.txt, stop_times.txt ...).
Stdlib only, like the rest of the release pipeline.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import re
import sys
import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

PARENT_PREFIX = "VST_"
EXIT_PREFIX = "VEX_"


@dataclass
class Platform:
    stop_id: str
    name: str
    lat: float
    lon: float


@dataclass
class Exit:
    osm_id: str
    lat: float
    lon: float
    ref: str
    label: str


@dataclass
class Group:
    key: str
    name: str
    platforms: list[Platform]
    exits: list[Exit] = field(default_factory=list)

    def base_names(self) -> set[str]:
        return {base_name(p.name) for p in self.platforms}


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Equirectangular distance — accurate to well under a metre at station scale."""
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return 6_371_000 * math.hypot(x, y)


_PAREN = re.compile(r"\(.*?\)")


def base_name(name: str) -> str:
    """'잠실(2호선)' -> '잠실', '서울(지하)' -> '서울', '서울역' -> '서울'."""
    n = _PAREN.sub("", name or "").strip()
    return n[:-1] if len(n) > 1 and n.endswith("역") else n


def label_names_station(label: str, name: str) -> bool:
    """True when an exit label refers to the station base name ('삼성(무역센터)역 3번출구' -> 삼성)."""
    if not label or not name:
        return False
    head = _PAREN.sub("", label).split()[0] if label.split() else ""
    head = head[:-1] if head.endswith("역") else head
    return head == name


def group_platforms(platforms: list[Platform], transfer_pairs: list[tuple[str, str]],
                    group_radius_m: float, transfer_radius_m: float) -> list[Group]:
    parent = {p.stop_id: p.stop_id for p in platforms}
    by_id = {p.stop_id: p for p in platforms}

    def find(x: str) -> str:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    by_name: dict[str, list[Platform]] = defaultdict(list)
    for p in platforms:
        by_name[base_name(p.name)].append(p)
    for same in by_name.values():
        for i, a in enumerate(same):
            for b in same[i + 1:]:
                if distance_m(a.lat, a.lon, b.lat, b.lon) <= group_radius_m:
                    union(a.stop_id, b.stop_id)
    for a, b in transfer_pairs:
        if a in by_id and b in by_id:
            pa, pb = by_id[a], by_id[b]
            if distance_m(pa.lat, pa.lon, pb.lat, pb.lon) <= transfer_radius_m:
                union(a, b)

    members: dict[str, list[Platform]] = defaultdict(list)
    for p in platforms:
        members[find(p.stop_id)].append(p)
    groups = []
    for root, ps in sorted(members.items()):
        ps.sort(key=lambda p: p.stop_id)
        names = sorted((base_name(p.name) for p in ps), key=lambda n: (-sum(base_name(q.name) == n for q in ps), n))
        groups.append(Group(key=root, name=names[0], platforms=ps))
    return groups


def assign_exits(groups: list[Group], exits: list[Exit], exit_radius_m: float) -> list[Exit]:
    """Attach each exit to a group. Returns the exits no group accepted."""
    unmatched = []
    for e in exits:
        in_range = []
        for g in groups:
            d = min(distance_m(e.lat, e.lon, p.lat, p.lon) for p in g.platforms)
            if d <= exit_radius_m:
                in_range.append((d, g))
        if not in_range:
            unmatched.append(e)
            continue
        in_range.sort(key=lambda t: (t[0], t[1].key))
        chosen = in_range[0][1]
        named = [g for _, g in in_range if any(label_names_station(e.label, n) for n in g.base_names())]
        if named:
            chosen = named[0]
        chosen.exits.append(e)
    return unmatched


def group_span_m(g: Group) -> float:
    return max((distance_m(a.lat, a.lon, b.lat, b.lon) for i, a in enumerate(g.platforms)
                for b in g.platforms[i + 1:]), default=0.0)


def near_unmerged_pairs(groups: list[Group], radius_m: float) -> list[tuple[str, str, int]]:
    """Platforms of different groups closer than ``radius_m`` — candidates for a name alias."""
    out = []
    plats = [(g, p) for g in groups for p in g.platforms]
    for i, (ga, a) in enumerate(plats):
        for gb, b in plats[i + 1:]:
            if ga is not gb:
                d = distance_m(a.lat, a.lon, b.lat, b.lon)
                if d <= radius_m:
                    out.append((a.name, b.name, round(d)))
    return sorted(out)


def exit_name(e: Exit, g: Group) -> str:
    if e.label:
        return e.label
    return f"{g.name}역 {e.ref}번 출구" if e.ref else f"{g.name}역 출구"


def station_rows(groups: list[Group], metro_prefix: str) -> tuple[dict[str, str], list[dict[str, str]]]:
    """(platform stop_id -> parent id, new stop rows) for metro platforms of stations with exits."""
    parent_of: dict[str, str] = {}
    rows: list[dict[str, str]] = []
    for g in groups:
        if not g.exits:
            continue
        exits = sorted(g.exits, key=lambda e: e.osm_id)
        for p in g.platforms:
            if not p.stop_id.startswith(metro_prefix):
                continue
            pid = PARENT_PREFIX + p.stop_id
            parent_of[p.stop_id] = pid
            rows.append({"stop_id": pid, "stop_name": g.name, "stop_lat": f"{p.lat:.7f}",
                         "stop_lon": f"{p.lon:.7f}", "location_type": "1", "parent_station": ""})
            for e in exits:
                rows.append({"stop_id": f"{EXIT_PREFIX}{e.osm_id}_{p.stop_id}", "stop_code": e.ref,
                             "stop_name": exit_name(e, g), "stop_lat": f"{e.lat:.7f}",
                             "stop_lon": f"{e.lon:.7f}", "location_type": "0", "parent_station": pid})
    return parent_of, rows


def read_csv(zf: zipfile.ZipFile, name: str) -> tuple[list[str], list[dict[str, str]]]:
    with zf.open(name) as fh:
        reader = csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8-sig", newline=""))
        rows = list(reader)
        return list(reader.fieldnames or []), rows


def read_exits(path: Path) -> list[Exit]:
    with path.open(encoding="utf-8") as fh:
        lines = [line for line in fh if not line.startswith("#")]
    return [Exit(r["osm_id"], float(r["lat"]), float(r["lon"]), r.get("ref", ""), r.get("label", ""))
            for r in csv.DictReader(lines)]


def write_zip(in_zip: Path, out_zip: Path, stops_csv: str) -> None:
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(in_zip) as src, zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename == "stops.txt":
                dst.writestr("stops.txt", stops_csv)
            else:
                with src.open(info) as fh, dst.open(info, "w") as out:
                    while chunk := fh.read(1 << 20):
                        out.write(chunk)


def run(in_zip: Path, exits_csv: Path, out_zip: Path, report_path: Path | None, *, rail_prefix: str = "RS_",
        metro_prefix: str = "RS_ACC1_", group_radius_m: float = 500, transfer_radius_m: float = 1000,
        exit_radius_m: float = 300, max_group_span_m: float = 600, dry_run: bool = False) -> dict:
    with zipfile.ZipFile(in_zip) as zf:
        stop_fields, stops = read_csv(zf, "stops.txt")
        transfers = read_csv(zf, "transfers.txt")[1] if "transfers.txt" in zf.namelist() else []
    if any(s.get("parent_station") for s in stops):
        raise SystemExit("input already has parent stations — refusing to merge")

    platforms = [Platform(s["stop_id"], s["stop_name"], float(s["stop_lat"]), float(s["stop_lon"]))
                 for s in stops if s["stop_id"].startswith(rail_prefix) and s.get("location_type", "0") in ("", "0")]
    pairs = [(t["from_stop_id"], t["to_stop_id"]) for t in transfers]
    groups = group_platforms(platforms, pairs, group_radius_m, transfer_radius_m)
    too_wide = [(g.name, round(group_span_m(g))) for g in groups if group_span_m(g) > max_group_span_m]
    if too_wide:
        raise SystemExit(f"station groups wider than {max_group_span_m} m: {too_wide}")
    exits = read_exits(exits_csv)
    unmatched = assign_exits(groups, exits, exit_radius_m)
    parent_of, new_rows = station_rows(groups, metro_prefix)

    for col in ("stop_code", "location_type", "parent_station"):
        if col not in stop_fields:
            stop_fields.append(col)
    out_rows = [dict(s, parent_station=parent_of[s["stop_id"]]) if s["stop_id"] in parent_of else s for s in stops]
    out_rows += new_rows
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=stop_fields, extrasaction="ignore", lineterminator="\n")
    w.writeheader()
    w.writerows(out_rows)
    if not dry_run:
        write_zip(in_zip, out_zip, buf.getvalue())

    with_exits = [g for g in groups if g.exits]
    report = {
        "groups": len(groups),
        "groupsWithExits": len(with_exits),
        "groupsWithoutExits": sorted(g.name for g in groups if not g.exits),
        "multiPlatformGroups": sum(1 for g in groups if len(g.platforms) > 1),
        "exitsTotal": len(exits),
        "exitsAssigned": sum(len(g.exits) for g in groups),
        "exitsUnmatched": len(unmatched),
        "virtualStations": len(parent_of),
        "exitStops": sum(1 for r in new_rows if r["location_type"] == "0"),
        # metro platforms of stations with exits that did not get a station (must be empty)
        "metroPlatformsWithoutStation": sorted(p.stop_id for g in with_exits for p in g.platforms
                                               if p.stop_id.startswith(metro_prefix) and p.stop_id not in parent_of),
        "maxGroupSpanM": round(max((group_span_m(g) for g in groups), default=0)),
        "nearUnmergedPairs": near_unmerged_pairs(groups, 250),
        "params": {"railPrefix": rail_prefix, "metroPrefix": metro_prefix, "groupRadiusM": group_radius_m,
                   "transferRadiusM": transfer_radius_m, "exitRadiusM": exit_radius_m,
                   "maxGroupSpanM": max_group_span_m},
    }
    if report_path:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--entrances", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path)
    ap.add_argument("--rail-prefix", default="RS_")
    ap.add_argument("--metro-prefix", default="RS_ACC1_", help="only these platforms get a station with exits")
    ap.add_argument("--group-radius-m", type=float, default=500)
    ap.add_argument("--transfer-radius-m", type=float, default=1000)
    ap.add_argument("--exit-radius-m", type=float, default=300)
    ap.add_argument("--max-group-span-m", type=float, default=600, help="fail when a station group is wider")
    ap.add_argument("--dry-run", action="store_true", help="report only, do not write --out")
    a = ap.parse_args()
    report = run(a.in_zip, a.entrances, a.out, a.report, rail_prefix=a.rail_prefix, metro_prefix=a.metro_prefix,
                 group_radius_m=a.group_radius_m, transfer_radius_m=a.transfer_radius_m,
                 exit_radius_m=a.exit_radius_m, max_group_span_m=a.max_group_span_m, dry_run=a.dry_run)
    print(json.dumps({k: v for k, v in report.items() if k not in ("groupsWithoutExits", "nearUnmergedPairs")},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
