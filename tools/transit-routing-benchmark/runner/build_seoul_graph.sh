#!/usr/bin/env bash
# Build the 수도권 OTP graph with pfaedle transit shapes (P3, national-otp).
#
# Pipeline (one command, reproducible, digest-pinned):
#   1. GTFS  — reuse the P1 normalized bbox feed (data/input/seoul-bbox-202503.gtfs.zip)
#              or regenerate it from KTDB raw via prepare_gtfs.py (--from-ktdb).
#   2. pfaedle — map-match bus trips against the nationwide OSM road graph, adding
#                shapes.txt. Image is pinned by digest (NOT :latest); ENTRYPOINT is
#                `pfaedle`, so pass ARGS ONLY (a stray `pfaedle` word is read as a
#                second feed → "Multiple feeds only allowed in filter mode").
#   3. gate  — compute_shape_quality.py prints the per-trip metric distribution and,
#              when --thresholds is given, writes coverage-index.json.
#   4. package — zip the shaped feed and (unless --report-only) rebuild the OTP graph
#                via compose.seoul-otp.yml, stamping a new graphVersion + manifest.
#
# --report-only stops after step 3 (the first run: get the distribution, then pick
# thresholds with the data — PLAN "임계값은 전량 실행 분포 보고 확정").
set -euo pipefail

# --- pins (change deliberately; recorded in release-manifest.json) ------------
PFAEDLE_DIGEST="sha256:af4fdc647699ea1ffe5150fd1f9d264c465c2876710ea749e8cb284f13abd20d"
PFAEDLE_IMAGE="ghcr.io/ad-freiburg/pfaedle@${PFAEDLE_DIGEST}"
# graph.obj 는 OTP serialization id 로 잠긴다. 배포 대상 OTP 와 다른 빌드로 만들면 로드 거부.
# 배포용은 항상 VPS 에 떠 있는 이미지를 넘길 것: OTP_IMAGE=nextroute-otp:<tag> ./build_seoul_graph.sh ...
export OTP_IMAGE="${OTP_IMAGE:-opentripplanner/opentripplanner:2.9.0}"
CAL_CENTER="20260907"
CAL_MARGIN="62"
BBOX="126.5,37.2,127.4,38.0"

# --- paths --------------------------------------------------------------------
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # tools/transit-routing-benchmark
cd "$HERE"
NORMALIZED_ZIP="data/input/seoul-bbox-202503.gtfs.zip"
OSM_ROADS="data/input/south-korea-260903.osm.pbf"
BUILD="data/otp-seoul/shape-build"
GTFS_IN="$BUILD/gtfs-in"
GTFS_OUT="$BUILD/gtfs-out"
SHAPED_ZIP="data/input/seoul-bbox-202503.gtfs.zip"     # replaced in-place on full build

# --- options ------------------------------------------------------------------
REPORT_ONLY=0
FROM_KTDB=""
THRESHOLDS=""
GRAPH_VERSION=""
MOTS="bus"      # pfaedle -m (mode-of-transport to map-match). P4 §A1: subway next.
MODES="bus"     # compute_shape_quality.py --modes (gate/report scope). Independent
                # of MOTS — e.g. report on bus+subway while only re-matching subway.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --report-only) REPORT_ONLY=1; shift ;;
    --from-ktdb) FROM_KTDB="$2"; shift 2 ;;
    --thresholds) THRESHOLDS="$2"; shift 2 ;;
    --graph-version) GRAPH_VERSION="$2"; shift 2 ;;
    --mots) MOTS="$2"; shift 2 ;;
    --modes) MODES="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

echo "== step 1: GTFS =="
if [[ -n "$FROM_KTDB" ]]; then
  echo "regenerating normalized feed from KTDB raw: $FROM_KTDB"
  rm -rf "$BUILD/gtfs-normalized"
  python3 runner/prepare_gtfs.py "$FROM_KTDB" "$BUILD/gtfs-normalized" \
    --bbox "$BBOX" --calendar-center "$CAL_CENTER" --calendar-margin-days "$CAL_MARGIN"
  rm -rf "$GTFS_IN"; cp -r "$BUILD/gtfs-normalized" "$GTFS_IN"
else
  echo "reusing normalized feed: $NORMALIZED_ZIP"
  rm -rf "$GTFS_IN"; mkdir -p "$GTFS_IN"
  unzip -o -q "$NORMALIZED_ZIP" -d "$GTFS_IN"
fi

echo "== step 2: pfaedle ($MOTS) =="
rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
docker run --rm \
  -v "$HERE/$BUILD:/data" \
  -v "$HERE/$OSM_ROADS:/data/roads.osm.pbf:ro" \
  -w /data \
  "$PFAEDLE_IMAGE" \
  -m "$MOTS" -x /data/roads.osm.pbf -i /data/gtfs-in -o /data/gtfs-out
test -s "$GTFS_OUT/shapes.txt" || { echo "pfaedle produced no shapes.txt" >&2; exit 1; }
echo "shapes.txt lines: $(wc -l < "$GTFS_OUT/shapes.txt")"

echo "== step 3: gate (modes=$MODES) =="
GATE_ARGS=("$GTFS_OUT" --modes "$MODES" --metrics-out "$BUILD/coverage-metrics.csv")
if [[ -n "$THRESHOLDS" ]]; then
  [[ -n "$GRAPH_VERSION" ]] || { echo "--thresholds requires --graph-version" >&2; exit 2; }
  GATE_ARGS+=(--thresholds "$THRESHOLDS" --index-out "data/otp-seoul/coverage-index.json" \
              --graph-version "$GRAPH_VERSION")
fi
python3 runner/compute_shape_quality.py "${GATE_ARGS[@]}"

if [[ "$REPORT_ONLY" == "1" ]]; then
  echo "== report-only: stop after gate =="
  exit 0
fi

echo "== step 4: package + graph build =="
[[ -n "$GRAPH_VERSION" ]] || { echo "full build requires --graph-version" >&2; exit 2; }
( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$SHAPED_ZIP" . )
echo "shaped feed → $SHAPED_ZIP"
docker compose -f compose.seoul-otp.yml --profile build run --rm otp-build
test -s data/otp-seoul/graph.obj || { echo "otp-build produced no graph.obj" >&2; exit 1; }
echo "graph.obj rebuilt. Update release-manifest.json (digest/sha/graphVersion) before deploy."

echo "== step 5: release bundle (원자성) =="
echo "release-manifest.json 의 graph.sha256/gtfsShapedSha256/coverageIndexSha256/graphVersion/"
echo "otp.imageDigest 를 이번 산출물로 갱신했는지 확인 후:"
echo "  python3 runner/verify_release_bundle.py --write   # bundleHash 기록"
echo "  python3 runner/verify_release_bundle.py           # 배포 전 재검증(부분 스왑 차단)"
