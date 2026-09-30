#!/usr/bin/env python3
"""Fill routes.txt route_color from config (national-otp-shapes Phase 3.5, #247).

Colors live in the graph so OTP returns route.color and the app no longer decides
colors itself. Rules, first match wins:

  route_type 1 (subway)  subway[route_short_name]; a line without a color fails the build
  route_type 3 (bus)     Seoul bus BR_TAGO_SEB<9 digits>[_U|_D] -> snapshot route_type ->
                         seoulBusRouteType; then BR_ACC3_* -> defaults.intercityBus;
                         else defaults.bus
  route_type 2 (rail)    defaults.rail
  route_type 4 (ferry)   defaults.ferry

The Seoul rule is the app's OtpSeoulIdentityResolver pattern plus the _U/_D suffix
used by airport buses (6xxx). Rewrites routes.txt in place and prints a summary.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import re
import sys
from pathlib import Path

SEOUL_BUS = re.compile(r"^BR_TAGO_SEB(\d{9})(?:_[UD])?$")
INTERCITY_PREFIX = "BR_ACC3_"


def pick(route: dict, colors: dict, seoul_types: dict) -> tuple[str, str]:
    """Return (color, reason) for one routes.txt row."""
    rtype, rid, name = route["route_type"], route["route_id"], route["route_short_name"]
    defaults = colors["defaults"]
    if rtype == "1":
        color = colors["subway"].get(name)
        if color is None:
            raise SystemExit(f"subway line without color: {name!r} ({rid}) — add it to route-colors.json")
        return color, "subway"
    if rtype == "3":
        m = SEOUL_BUS.match(rid)
        if m:
            seoul_type = seoul_types.get(m.group(1))
            color = colors["seoulBusRouteType"].get(str(seoul_type)) if seoul_type is not None else None
            if color:
                return color, f"seoul bus type {seoul_type}"
            return defaults["bus"], "seoul bus (no type color)" if seoul_type is not None else "seoul bus (not in DB)"
        if rid.startswith(INTERCITY_PREFIX):
            return defaults["intercityBus"], "intercity bus"
        return defaults["bus"], "bus default"
    if rtype == "2":
        return defaults["rail"], "rail"
    if rtype == "4":
        return defaults["ferry"], "ferry"
    raise SystemExit(f"unhandled route_type {rtype} ({rid})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gtfs", type=Path, help="GTFS directory whose routes.txt is rewritten")
    parser.add_argument("--colors", type=Path, required=True, help="route-colors.json")
    parser.add_argument("--seoul-bus-types", type=Path, required=True, help="seoul-bus-route-types.json")
    args = parser.parse_args()

    colors = json.loads(args.colors.read_text(encoding="utf-8"))
    seoul_types = json.loads(args.seoul_bus_types.read_text(encoding="utf-8"))["routeTypes"]
    routes_path = args.gtfs / "routes.txt"
    with routes_path.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fields = list(reader.fieldnames or [])
        rows = list(reader)
    if "route_color" not in fields:
        fields.append("route_color")

    summary = collections.Counter()
    for row in rows:
        color, reason = pick(row, colors, seoul_types)
        row["route_color"] = color.upper()
        summary[reason] += 1

    tmp = routes_path.with_suffix(".txt.tmp")
    with tmp.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    tmp.replace(routes_path)

    print(f"route_color written: {len(rows)} routes")
    for reason, n in summary.most_common():
        print(f"  {reason}: {n}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
