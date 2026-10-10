#!/usr/bin/env bash
# OSM 지하철 출입구(railway=subway_entrance) → src/main/resources/subway/entrances.csv (#266 출구 번호)
#
# 사용: tools/subway-entrances/build_entrances.sh <south-korea-*.osm.pbf>
# 필요: osmium(brew install osmium-tool), python3
# 출처: © OpenStreetMap contributors, ODbL — 앱에 표기 필요
set -euo pipefail
PBF="${1:?osm.pbf 경로}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
osmium tags-filter -O -o "$WORK/entr.osm.pbf" "$PBF" n/railway=subway_entrance
osmium export -O --add-unique-id=type_id -f geojsonseq -o "$WORK/entr.geojsonseq" "$WORK/entr.osm.pbf"
python3 -I "$(dirname "$0")/to_csv.py" "$WORK/entr.geojsonseq" "$PBF" > "$ROOT/src/main/resources/subway/entrances.csv"
echo "wrote $(($(wc -l < "$ROOT/src/main/resources/subway/entrances.csv") - 2)) entrances"
