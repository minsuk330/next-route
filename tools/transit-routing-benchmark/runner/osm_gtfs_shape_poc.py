#!/usr/bin/env python3
"""Extract OSM route geometry and compare it with a GTFS stop pattern.

This is a benchmark/diagnostic utility, not a production GTFS converter.  It
answers the first question needed before building a shape enrichment pipeline:
does a given OSM route relation contain continuous geometry and an ordered stop
sequence that can be matched to the canonical GTFS feed?

The script requires pyosmium (PyPI package name: ``osmium``).
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import osmium


EARTH_RADIUS_METERS = 6_371_008.8


@dataclass(frozen=True)
class Member:
    member_type: str
    ref: int
    role: str


@dataclass
class Relation:
    relation_id: int
    tags: dict[str, str]
    members: list[Member]


@dataclass(frozen=True)
class Stop:
    stop_id: str
    name: str
    lat: float
    lon: float


class RelationCollector(osmium.SimpleHandler):
    def __init__(self, route_ref: str) -> None:
        super().__init__()
        self.route_ref = route_ref
        self.relations: dict[int, Relation] = {}

    def relation(self, relation) -> None:
        if relation.tags.get("ref") != self.route_ref:
            return
        if relation.tags.get("route") != "bus" and relation.tags.get("route_master") != "bus":
            return
        self.relations[relation.id] = Relation(
            relation_id=relation.id,
            tags=dict(relation.tags),
            members=[Member(member.type, member.ref, member.role) for member in relation.members],
        )


class GeometryCollector(osmium.SimpleHandler):
    def __init__(self, way_ids: set[int], platform_ids: set[int]) -> None:
        super().__init__()
        self.way_ids = way_ids
        self.platform_ids = platform_ids
        self.ways: dict[int, list[tuple[float, float]]] = {}
        self.platforms: dict[int, tuple[float, float, str]] = {}

    def node(self, node) -> None:
        if node.id not in self.platform_ids or not node.location.valid():
            return
        self.platforms[node.id] = (node.location.lat, node.location.lon, node.tags.get("name", ""))

    def way(self, way) -> None:
        if way.id not in self.way_ids:
            return
        coordinates = [(node.lat, node.lon) for node in way.nodes if node.location.valid()]
        if len(coordinates) >= 2:
            self.ways[way.id] = coordinates


def haversine(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_METERS * math.asin(math.sqrt(value))


def line_length(coordinates: list[tuple[float, float]]) -> float:
    return sum(haversine(a, b) for a, b in zip(coordinates, coordinates[1:]))


def orient_ways(ways: list[list[tuple[float, float]]]) -> tuple[list[int], float]:
    """Choose each way orientation using minimum total endpoint gap."""
    if not ways:
        return [], 0.0

    costs = [0.0, 0.0]
    parents: list[list[int]] = []
    for index in range(1, len(ways)):
        current_costs = [math.inf, math.inf]
        current_parents = [-1, -1]
        previous = ways[index - 1]
        current = ways[index]
        for current_orientation in (0, 1):
            current_start = current[0] if current_orientation == 0 else current[-1]
            for previous_orientation in (0, 1):
                previous_end = previous[-1] if previous_orientation == 0 else previous[0]
                cost = costs[previous_orientation] + haversine(previous_end, current_start)
                if cost < current_costs[current_orientation]:
                    current_costs[current_orientation] = cost
                    current_parents[current_orientation] = previous_orientation
        costs = current_costs
        parents.append(current_parents)

    orientation = 0 if costs[0] <= costs[1] else 1
    selected = [orientation]
    for parent in reversed(parents):
        orientation = parent[orientation]
        selected.append(orientation)
    selected.reverse()
    return selected, min(costs)


def assemble_relation_geometry(
    relation: Relation, way_geometries: dict[int, list[tuple[float, float]]]
) -> tuple[list[tuple[float, float]], list[list[tuple[float, float]]], list[float], list[int]]:
    member_way_ids = [member.ref for member in relation.members if member.member_type == "w"]
    missing = [way_id for way_id in member_way_ids if way_id not in way_geometries]
    available = [way_geometries[way_id] for way_id in member_way_ids if way_id in way_geometries]
    orientations, _ = orient_ways(available)

    result: list[tuple[float, float]] = []
    continuous_parts: list[list[tuple[float, float]]] = []
    gaps: list[float] = []
    for coordinates, orientation in zip(available, orientations):
        part = coordinates if orientation == 0 else list(reversed(coordinates))
        if result:
            gap = haversine(result[-1], part[0])
            gaps.append(gap)
            if gap < 0.5:
                part = part[1:]
            elif gap > 30:
                continuous_parts.append([])
        if not continuous_parts:
            continuous_parts.append([])
        continuous_parts[-1].extend(part)
        result.extend(part)
    return result, continuous_parts, gaps, missing


def stitch_parts(
    parts: list[list[tuple[float, float]]],
    start_hint: tuple[float, float] | None,
    end_hint: tuple[float, float] | None,
) -> tuple[list[list[tuple[float, float]]], list[float]]:
    """Reorder misplaced continuous blocks while preserving each block.

    Some OSM relations have all the required ways but contain a few continuous
    blocks in the wrong member order.  For a small number of blocks, solve the
    ordering exactly and use the first/last platform as weak endpoint hints.
    """
    if len(parts) <= 1:
        return parts, []
    if len(parts) > 16:
        return parts, [haversine(a[-1], b[0]) for a, b in zip(parts, parts[1:])]

    count = len(parts)
    states: dict[tuple[int, int, int], tuple[float, tuple[int, int, int] | None]] = {}
    for index, part in enumerate(parts):
        for orientation in (0, 1):
            start = part[0] if orientation == 0 else part[-1]
            initial = haversine(start_hint, start) if start_hint else 0.0
            states[(1 << index, index, orientation)] = (initial, None)

    for mask_size in range(1, count):
        next_states = dict(states)
        for state, (cost, _) in states.items():
            mask, last_index, last_orientation = state
            if bin(mask).count("1") != mask_size:
                continue
            last_part = parts[last_index]
            last_end = last_part[-1] if last_orientation == 0 else last_part[0]
            for next_index, next_part in enumerate(parts):
                if mask & (1 << next_index):
                    continue
                for next_orientation in (0, 1):
                    next_start = next_part[0] if next_orientation == 0 else next_part[-1]
                    next_state = (mask | (1 << next_index), next_index, next_orientation)
                    next_cost = cost + haversine(last_end, next_start)
                    if next_state not in next_states or next_cost < next_states[next_state][0]:
                        next_states[next_state] = (next_cost, state)
        states = next_states

    full_mask = (1 << count) - 1
    final_state = None
    final_cost = math.inf
    for state, (cost, _) in states.items():
        mask, last_index, last_orientation = state
        if mask != full_mask:
            continue
        last_part = parts[last_index]
        last_end = last_part[-1] if last_orientation == 0 else last_part[0]
        total = cost + (haversine(last_end, end_hint) if end_hint else 0.0)
        if total < final_cost:
            final_cost = total
            final_state = state

    if final_state is None:
        return parts, [haversine(a[-1], b[0]) for a, b in zip(parts, parts[1:])]

    order: list[tuple[int, int]] = []
    state: tuple[int, int, int] | None = final_state
    while state is not None:
        _, index, orientation = state
        order.append((index, orientation))
        state = states[state][1]
    order.reverse()

    stitched = [parts[index] if orientation == 0 else list(reversed(parts[index])) for index, orientation in order]
    gaps = [haversine(previous[-1], current[0]) for previous, current in zip(stitched, stitched[1:])]
    return stitched, gaps


def read_gtfs_patterns(gtfs_zip: Path, route_ref: str) -> tuple[list[list[Stop]], dict[str, object]]:
    with zipfile.ZipFile(gtfs_zip) as archive:
        with archive.open("routes.txt") as stream:
            routes = list(csv.DictReader(line.decode("utf-8-sig") for line in stream))
        route_ids = {row["route_id"] for row in routes if row.get("route_short_name") == route_ref}
        if not route_ids:
            raise ValueError(f"GTFS route_short_name={route_ref!r} not found")

        with archive.open("trips.txt") as stream:
            trips = list(csv.DictReader(line.decode("utf-8-sig") for line in stream))
        trip_ids = {row["trip_id"] for row in trips if row["route_id"] in route_ids}

        stop_sequences: dict[str, list[tuple[int, str]]] = defaultdict(list)
        with archive.open("stop_times.txt") as stream:
            for row in csv.DictReader(line.decode("utf-8-sig") for line in stream):
                if row["trip_id"] in trip_ids:
                    stop_sequences[row["trip_id"]].append((int(row["stop_sequence"]), row["stop_id"]))

        with archive.open("stops.txt") as stream:
            stops = {
                row["stop_id"]: Stop(
                    stop_id=row["stop_id"],
                    name=row.get("stop_name", ""),
                    lat=float(row["stop_lat"]),
                    lon=float(row["stop_lon"]),
                )
                for row in csv.DictReader(line.decode("utf-8-sig") for line in stream)
            }

    pattern_counts: Counter[tuple[str, ...]] = Counter()
    for sequence in stop_sequences.values():
        ordered = tuple(stop_id for _, stop_id in sorted(sequence))
        pattern_counts[ordered] += 1

    patterns = [[stops[stop_id] for stop_id in pattern] for pattern in pattern_counts]
    metadata = {
        "route_ids": sorted(route_ids),
        "trip_count": len(trip_ids),
        "pattern_count": len(patterns),
        "patterns": [
            {
                "index": index,
                "trip_count": pattern_counts[tuple(stop.stop_id for stop in pattern)],
                "stop_count": len(pattern),
                "from": pattern[0].name if pattern else None,
                "to": pattern[-1].name if pattern else None,
            }
            for index, pattern in enumerate(patterns)
        ],
    }
    return patterns, metadata


def ordered_stop_matches(
    points: list[tuple[int, tuple[float, float, str]]], stops: list[Stop]
) -> list[dict[str, object]]:
    """Map platform order to strictly increasing GTFS stop indices.

    Independent nearest-neighbour matching fails on round trips because the
    outbound and inbound stop can be only a few metres apart.  This dynamic
    program keeps the route order as a hard constraint.
    """
    if len(points) > len(stops):
        return []

    distances = [
        [haversine((platform[0], platform[1]), (stop.lat, stop.lon)) for stop in stops]
        for _, platform in points
    ]
    previous_costs = distances[0][:]
    parents: list[list[int]] = []
    for platform_index in range(1, len(points)):
        prefix_cost = math.inf
        prefix_index = -1
        current_costs = [math.inf] * len(stops)
        current_parents = [-1] * len(stops)
        for stop_index in range(len(stops)):
            candidate_index = stop_index - 1
            if candidate_index >= 0 and previous_costs[candidate_index] < prefix_cost:
                prefix_cost = previous_costs[candidate_index]
                prefix_index = candidate_index
            if prefix_index >= 0:
                current_costs[stop_index] = prefix_cost + distances[platform_index][stop_index]
                current_parents[stop_index] = prefix_index
        previous_costs = current_costs
        parents.append(current_parents)

    stop_index = min(range(len(stops)), key=previous_costs.__getitem__)
    indices = [stop_index]
    for parent in reversed(parents):
        stop_index = parent[stop_index]
        if stop_index < 0:
            return []
        indices.append(stop_index)
    indices.reverse()

    return [
        {
            "platform_id": platform_id,
            "platform_name": platform[2],
            "gtfs_index": gtfs_index,
            "gtfs_stop_id": stops[gtfs_index].stop_id,
            "gtfs_stop_name": stops[gtfs_index].name,
            "distance_meters": round(distances[platform_index][gtfs_index], 1),
        }
        for platform_index, ((platform_id, platform), gtfs_index) in enumerate(zip(points, indices))
    ]


def score_platforms(
    platform_ids: Iterable[int],
    platforms: dict[int, tuple[float, float, str]],
    patterns: list[list[Stop]],
    threshold_meters: float,
) -> dict[str, object]:
    available = [(platform_id, platforms[platform_id]) for platform_id in platform_ids if platform_id in platforms]
    if not available or not patterns:
        return {"available_platform_count": len(available), "best_pattern": None}

    candidates: list[dict[str, object]] = []
    for pattern_index, pattern in enumerate(patterns):
        matches = ordered_stop_matches(available, pattern)
        accepted = [match for match in matches if match["distance_meters"] <= threshold_meters]
        indices = [int(match["gtfs_index"]) for match in accepted]
        candidates.append(
            {
                "pattern_index": pattern_index,
                "within_threshold": len(accepted),
                "coverage": round(len(accepted) / len(available), 4),
                "unique_gtfs_stops": len(set(indices)),
                "monotonic_violations": 0,
                "gtfs_span": [min(indices), max(indices)] if indices else None,
                "mean_distance_meters": round(
                    sum(float(match["distance_meters"]) for match in accepted) / len(accepted), 1
                )
                if accepted
                else None,
                "matches": matches,
            }
        )
    candidates.sort(
        key=lambda candidate: (
            -int(candidate["within_threshold"]),
            int(candidate["monotonic_violations"]),
        )
    )
    return {"available_platform_count": len(available), "best_pattern": candidates[0]}


def feature_collection(
    selected: list[Relation],
    relation_geometries: dict[int, list[list[tuple[float, float]]]],
    platforms: dict[int, tuple[float, float, str]],
) -> dict[str, object]:
    features: list[dict[str, object]] = []
    for relation in selected:
        parts = relation_geometries[relation.relation_id]
        if parts:
            features.append(
                {
                    "type": "Feature",
                    "properties": {
                        "kind": "route",
                        "relation_id": relation.relation_id,
                        "name": relation.tags.get("name"),
                        "from": relation.tags.get("from"),
                        "to": relation.tags.get("to"),
                    },
                    "geometry": {
                        "type": "MultiLineString",
                        "coordinates": [
                            [[lon, lat] for lat, lon in part]
                            for part in parts
                        ],
                    },
                }
            )
        for member in relation.members:
            if member.member_type != "n" or "platform" not in member.role or member.ref not in platforms:
                continue
            lat, lon, name = platforms[member.ref]
            features.append(
                {
                    "type": "Feature",
                    "properties": {
                        "kind": "platform",
                        "relation_id": relation.relation_id,
                        "osm_node_id": member.ref,
                        "name": name,
                    },
                    "geometry": {"type": "Point", "coordinates": [lon, lat]},
                }
            )
    return {"type": "FeatureCollection", "features": features}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf", type=Path)
    parser.add_argument("gtfs", type=Path)
    parser.add_argument("--route-ref", required=True)
    parser.add_argument("--route-master-id", type=int)
    parser.add_argument("--name-contains", help="fallback city/operator discriminator")
    parser.add_argument("--threshold-meters", type=float, default=30.0)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    collector = RelationCollector(args.route_ref)
    collector.apply_file(str(args.pbf))

    masters = [relation for relation in collector.relations.values() if relation.tags.get("route_master") == "bus"]
    if args.route_master_id is not None:
        masters = [relation for relation in masters if relation.relation_id == args.route_master_id]
    elif args.name_contains:
        masters = [
            relation
            for relation in masters
            if args.name_contains in " ".join(relation.tags.values())
        ]
    if len(masters) != 1:
        ids = [relation.relation_id for relation in masters]
        raise ValueError(f"Expected exactly one route master; candidates={ids}")

    master = masters[0]
    child_ids = {member.ref for member in master.members if member.member_type == "r"}
    selected = [collector.relations[relation_id] for relation_id in child_ids if relation_id in collector.relations]
    if not selected:
        raise ValueError(f"No child route relations found for master {master.relation_id}")

    way_ids = {
        member.ref
        for relation in selected
        for member in relation.members
        if member.member_type == "w"
    }
    platform_ids = {
        member.ref
        for relation in selected
        for member in relation.members
        if member.member_type == "n" and "platform" in member.role
    }
    geometry_collector = GeometryCollector(way_ids, platform_ids)
    geometry_collector.apply_file(str(args.pbf), locations=True, idx="flex_mem")

    patterns, gtfs_metadata = read_gtfs_patterns(args.gtfs, args.route_ref)
    relation_geometries: dict[int, list[list[tuple[float, float]]]] = {}
    relation_reports = []
    for relation in sorted(selected, key=lambda value: value.relation_id):
        geometry, source_parts, source_gaps, missing_ways = assemble_relation_geometry(
            relation, geometry_collector.ways
        )
        relation_platform_ids = [
            member.ref
            for member in relation.members
            if member.member_type == "n" and "platform" in member.role
        ]
        platform_coordinates = [
            geometry_collector.platforms[platform_id][:2]
            for platform_id in relation_platform_ids
            if platform_id in geometry_collector.platforms
        ]
        continuous_parts, stitched_gaps = stitch_parts(
            source_parts,
            platform_coordinates[0] if platform_coordinates else None,
            platform_coordinates[-1] if platform_coordinates else None,
        )
        relation_geometries[relation.relation_id] = continuous_parts
        relation_reports.append(
            {
                "relation_id": relation.relation_id,
                "name": relation.tags.get("name"),
                "from": relation.tags.get("from"),
                "to": relation.tags.get("to"),
                "member_way_count": sum(member.member_type == "w" for member in relation.members),
                "platform_count": len(relation_platform_ids),
                "missing_way_count": len(missing_ways),
                "geometry_point_count": len(geometry),
                "geometry_way_length_meters": round(
                    sum(
                        line_length(geometry_collector.ways[member.ref])
                        for member in relation.members
                        if member.member_type == "w" and member.ref in geometry_collector.ways
                    ),
                    1,
                ),
                "source_block_count": len(source_parts),
                "source_order_gap_total_meters": round(sum(source_gaps), 1),
                "source_order_maximum_gap_meters": round(max(source_gaps), 1) if source_gaps else 0.0,
                "stitched_block_count": len(continuous_parts),
                "stitched_gap_total_meters": round(sum(stitched_gaps), 1),
                "gap_count_over_30m": sum(gap > 30 for gap in stitched_gaps),
                "maximum_gap_meters": round(max(stitched_gaps), 1) if stitched_gaps else 0.0,
                "platform_match": score_platforms(
                    relation_platform_ids,
                    geometry_collector.platforms,
                    patterns,
                    args.threshold_meters,
                ),
            }
        )

    report = {
        "route_ref": args.route_ref,
        "osm": {
            "route_master_id": master.relation_id,
            "route_master_name": master.tags.get("name"),
            "child_relation_count": len(selected),
            "relations": relation_reports,
        },
        "gtfs": gtfs_metadata,
        "interpretation": {
            "threshold_meters": args.threshold_meters,
            "note": "A high stop match does not prove full route coverage; inspect geometry gaps and relation stop counts.",
        },
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.output_dir / f"route-{args.route_ref}-osm-gtfs-report.json"
    geojson_path = args.output_dir / f"route-{args.route_ref}-osm.geojson"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    geojson_path.write_text(
        json.dumps(
            feature_collection(selected, relation_geometries, geometry_collector.platforms),
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )

    print(f"report={report_path}")
    print(f"geojson={geojson_path}")
    for relation in relation_reports:
        best = relation["platform_match"].get("best_pattern")
        print(
            f"relation={relation['relation_id']} platforms={relation['platform_count']} "
            f"ways={relation['member_way_count']} way_length_m={relation['geometry_way_length_meters']} "
            f"gaps_over_30m={relation['gap_count_over_30m']} "
            f"matched={best['within_threshold'] if best else 0}/{relation['platform_count']}"
        )


if __name__ == "__main__":
    main()
