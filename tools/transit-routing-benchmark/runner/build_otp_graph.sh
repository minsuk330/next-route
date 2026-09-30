#!/usr/bin/env bash
# Build an OTP graph with pfaedle transit shapes for a given profile.
#   --profile seoul     수도권 bbox feed (P3/P4). Graph built in a container via
#                       compose.seoul-otp.yml. Same behavior as the old
#                       build_seoul_graph.sh (which now just calls this).
#   --profile national  전국(제주 제외) feed (national-otp-shapes). Graph built with
#                       the host JVM: the no-shape national build peaked at 11.6GiB
#                       heap, more than the Docker Desktop VM (7.75GB) can give.
#
# Pipeline:
#   1. GTFS  — reuse the profile's input feed (seoul: or regenerate from KTDB raw
#              via prepare_gtfs.py --from-ktdb).
#   2. pfaedle — map-match trips of --mots against the nationwide OSM, adding
#                shapes.txt. Image is pinned by digest (NOT :latest); ENTRYPOINT is
#                `pfaedle`, so pass ARGS ONLY (a stray `pfaedle` word is read as a
#                second feed → "Multiple feeds only allowed in filter mode").
#   3. gate  — compute_shape_quality.py prints the per-trip metric distribution and,
#              when --thresholds is given, writes coverage-index.json.
#   4. package — zip the shaped feed and (unless --report-only) rebuild the OTP graph.
#
# --report-only stops after step 3 (first run: get the distribution, then pick
# thresholds with the data — PLAN "임계값은 전량 실행 분포 보고 확정").
set -euo pipefail

# --- pins (change deliberately; recorded in release-manifest.json) ------------
PFAEDLE_DIGEST="sha256:af4fdc647699ea1ffe5150fd1f9d264c465c2876710ea749e8cb284f13abd20d"
PFAEDLE_IMAGE="ghcr.io/ad-freiburg/pfaedle@${PFAEDLE_DIGEST}"
OSM_ROADS="data/input/south-korea-260903.osm.pbf"   # pfaedle input (both profiles)

# --- options ------------------------------------------------------------------
PROFILE=""
REPORT_ONLY=0
FROM_KTDB=""
THRESHOLDS=""
GRAPH_VERSION=""
MOTS="bus"      # pfaedle -m (mode-of-transport to map-match).
MODES="bus"     # compute_shape_quality.py --modes (gate/report scope). Independent
                # of MOTS — e.g. report on bus+subway while only re-matching subway.
INPUT_OVERRIDE=""   # --input: feed dir or zip to match instead of the profile feed.
                    # Chain passes: pfaedle keeps other modes' shapes, so feed the
                    # bus-shaped output (dir) into the subway pass.
PFAEDLE_CONFIG=""   # --pfaedle-config: full pfaedle.cfg replacing the image default
                    # (e.g. config/otp-national/pfaedle-subway-rail.cfg).
SKIP_MATCH=0        # --skip-match: reuse the existing shape-build/gtfs-out (steps 1-2
                    # skipped), e.g. to build the graph from already reviewed passes.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --input) INPUT_OVERRIDE="$2"; shift 2 ;;
    --pfaedle-config) PFAEDLE_CONFIG="$2"; shift 2 ;;
    --skip-match) SKIP_MATCH=1; shift ;;
    --report-only) REPORT_ONLY=1; shift ;;
    --from-ktdb) FROM_KTDB="$2"; shift 2 ;;
    --thresholds) THRESHOLDS="$2"; shift 2 ;;
    --graph-version) GRAPH_VERSION="$2"; shift 2 ;;
    --mots) MOTS="$2"; shift 2 ;;
    --modes) MODES="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # tools/transit-routing-benchmark
cd "$HERE"

# --- profile ------------------------------------------------------------------
case "$PROFILE" in
  seoul)
    # graph.obj 는 OTP serialization id 로 잠긴다. 배포 대상 OTP 와 다른 빌드로 만들면 로드 거부.
    # 배포용은 항상 VPS 에 떠 있는 이미지를 넘길 것: OTP_IMAGE=nextroute-otp:<tag> ...
    export OTP_IMAGE="${OTP_IMAGE:-opentripplanner/opentripplanner:2.9.0}"
    CAL_CENTER="20260907"
    CAL_MARGIN="62"
    BBOX="126.5,37.2,127.4,38.0"
    INPUT_ZIP="data/input/seoul-bbox-202503.gtfs.zip"
    SHAPED_ZIP="data/input/seoul-bbox-202503.gtfs.zip"   # replaced in-place on full build
    DEPLOY_DIR="data/otp-seoul"
    ;;
  national)
    [[ -z "$FROM_KTDB" ]] || { echo "--from-ktdb is seoul-only (national feed is fixed input, PLAN Phase 1)" >&2; exit 2; }
    INPUT_ZIP="data/input/national-nojeju-202503.gtfs.zip"
    SHAPED_ZIP="data/input/national-nojeju-202503-shaped.gtfs.zip"   # input stays untouched
    DEPLOY_DIR="data/otp-national"
    # Must be the jar baked into the deployed image (label site.refit.otp.jar-sha256).
    OTP_JAR="${OTP_JAR:-data/input/otp-h-only-9dbfb229.jar}"
    OTP_JAR_SHA256="${OTP_JAR_SHA256:-56aebe272e0f204f4af05ce945fc23514dbb354a750ea42fbd46945f7e1378f7}"
    OTP_SERIALIZATION_ID="278"
    OTP_JAVA="${OTP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"   # image runtime = Temurin 25
    OTP_BUILD_XMX="${OTP_BUILD_XMX:-12g}"
    # Street graph for OTP: Geofabrik 260929, Jeju cut off (south of 33.7N) with
    #   osmium extract -b 124.0,33.7,132.0,39.0 --strategy complete_ways
    # 260903 (the pfaedle input) is no longer on Geofabrik; shapes already live in GTFS,
    # so the street OSM date does not have to match the matching OSM.
    OTP_OSM="${OTP_OSM:-data/input/south-korea-260929-nojeju.osm.pbf}"
    ;;
  *) echo "--profile seoul|national is required" >&2; exit 2 ;;
esac
BUILD="$DEPLOY_DIR/shape-build"
GTFS_IN="$BUILD/gtfs-in"
GTFS_OUT="$BUILD/gtfs-out"

# Fail before the long steps, not after them.
if [[ "$REPORT_ONLY" == "0" ]]; then
  [[ -n "$GRAPH_VERSION" ]] || { echo "full build requires --graph-version" >&2; exit 2; }
fi
if [[ "$PROFILE" == "national" && "$REPORT_ONLY" == "0" ]]; then
  test -s "$OTP_JAR" || { echo "OTP jar not found: $OTP_JAR" >&2; exit 2; }
  actual_sha="$(shasum -a 256 "$OTP_JAR" | awk '{print $1}')"
  [[ "$actual_sha" == "$OTP_JAR_SHA256" ]] || { echo "OTP jar sha mismatch: $actual_sha != $OTP_JAR_SHA256" >&2; exit 2; }
  ser_id="$(unzip -p "$OTP_JAR" otp-project-info.properties | sed -n 's/^otp.serialization.version.id=//p')"
  [[ "$ser_id" == "$OTP_SERIALIZATION_ID" ]] || { echo "OTP serialization id $ser_id != $OTP_SERIALIZATION_ID" >&2; exit 2; }
  "$OTP_JAVA" -version >/dev/null 2>&1 || { echo "java not runnable: $OTP_JAVA" >&2; exit 2; }
  # OTP silently builds a street-less graph when the OSM source is missing (2026-09-30).
  [[ -f "$OTP_OSM" && $(stat -f %z "$OTP_OSM") -gt 100000000 ]] || { echo "OTP OSM missing or too small: $OTP_OSM" >&2; exit 2; }
fi
if [[ "$SKIP_MATCH" == "0" ]]; then
  [[ -f "$OSM_ROADS" && $(stat -f %z "$OSM_ROADS") -gt 50000000 ]] || { echo "pfaedle OSM missing or too small: $OSM_ROADS" >&2; exit 2; }
fi

echo "== profile: $PROFILE =="
if [[ "$SKIP_MATCH" == "1" ]]; then
  test -s "$GTFS_OUT/shapes.txt" || { echo "--skip-match: no $GTFS_OUT/shapes.txt" >&2; exit 2; }
  echo "== steps 1-2 skipped: reusing $GTFS_OUT (shapes.txt lines: $(wc -l < "$GTFS_OUT/shapes.txt")) =="
else
echo "== step 1: GTFS =="
if [[ -n "$FROM_KTDB" ]]; then
  echo "regenerating normalized feed from KTDB raw: $FROM_KTDB"
  rm -rf "$BUILD/gtfs-normalized"
  python3 runner/prepare_gtfs.py "$FROM_KTDB" "$BUILD/gtfs-normalized" \
    --bbox "$BBOX" --calendar-center "$CAL_CENTER" --calendar-margin-days "$CAL_MARGIN"
  rm -rf "$GTFS_IN"; cp -r "$BUILD/gtfs-normalized" "$GTFS_IN"
elif [[ -n "$INPUT_OVERRIDE" && -d "$INPUT_OVERRIDE" ]]; then
  [[ "$(cd "$INPUT_OVERRIDE" && pwd)" != "$(mkdir -p "$GTFS_IN" "$GTFS_OUT" && cd "$GTFS_IN" && pwd)" ]] || { echo "--input must not be $GTFS_IN" >&2; exit 2; }
  [[ "$(cd "$INPUT_OVERRIDE" && pwd)" != "$(cd "$GTFS_OUT" && pwd)" ]] || { echo "--input must not be $GTFS_OUT (step 2 wipes it); move it first" >&2; exit 2; }
  echo "using feed dir: $INPUT_OVERRIDE"
  rm -rf "$GTFS_IN"; cp -R "$INPUT_OVERRIDE" "$GTFS_IN"
else
  INPUT_ZIP="${INPUT_OVERRIDE:-$INPUT_ZIP}"
  echo "reusing feed: $INPUT_ZIP"
  rm -rf "$GTFS_IN"; mkdir -p "$GTFS_IN"
  unzip -o -q "$INPUT_ZIP" -d "$GTFS_IN"
fi

echo "== step 2: pfaedle ($MOTS) =="
rm -rf "$GTFS_OUT"; mkdir -p "$GTFS_OUT"
PFAEDLE_ARGS=(-m "$MOTS" -x /data/roads.osm.pbf -i /data/gtfs-in -o /data/gtfs-out)
DOCKER_CFG=()
if [[ -n "$PFAEDLE_CONFIG" ]]; then
  test -s "$PFAEDLE_CONFIG" || { echo "pfaedle config not found: $PFAEDLE_CONFIG" >&2; exit 2; }
  # Mount over the image default so the stock config is not also read.
  DOCKER_CFG=(-v "$(cd "$(dirname "$PFAEDLE_CONFIG")" && pwd)/$(basename "$PFAEDLE_CONFIG"):/usr/local/etc/pfaedle/pfaedle.cfg:ro")
  echo "pfaedle config: $PFAEDLE_CONFIG (sha256 $(shasum -a 256 "$PFAEDLE_CONFIG" | awk '{print $1}'))"
fi
docker run --rm \
  -v "$HERE/$BUILD:/data" \
  -v "$HERE/$OSM_ROADS:/data/roads.osm.pbf:ro" \
  ${DOCKER_CFG[@]+"${DOCKER_CFG[@]}"} \
  -w /data \
  "$PFAEDLE_IMAGE" \
  "${PFAEDLE_ARGS[@]}"
test -s "$GTFS_OUT/shapes.txt" || { echo "pfaedle produced no shapes.txt" >&2; exit 1; }
echo "shapes.txt lines: $(wc -l < "$GTFS_OUT/shapes.txt")"
fi

echo "== step 3: gate (modes=$MODES) =="
GATE_ARGS=("$GTFS_OUT" --modes "$MODES" --metrics-out "$BUILD/coverage-metrics.csv")
if [[ -n "$THRESHOLDS" ]]; then
  [[ -n "$GRAPH_VERSION" ]] || { echo "--thresholds requires --graph-version" >&2; exit 2; }
  GATE_ARGS+=(--thresholds "$THRESHOLDS" --index-out "$DEPLOY_DIR/coverage-index.json" \
              --graph-version "$GRAPH_VERSION")
fi
python3 runner/compute_shape_quality.py "${GATE_ARGS[@]}"

if [[ "$REPORT_ONLY" == "1" ]]; then
  echo "== report-only: stop after gate =="
  exit 0
fi

echo "== step 4: package + graph build =="
if [[ "$PROFILE" == "national" ]]; then
  # route_color lives in the graph (#247); idempotent, rewrites routes.txt in place.
  python3 runner/apply_route_colors.py "$GTFS_OUT" \
    --colors config/otp-national/route-colors.json \
    --seoul-bus-types config/otp-national/seoul-bus-route-types.json
fi
if [[ "$PROFILE" == "seoul" ]]; then
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$SHAPED_ZIP" . )   # unchanged P3/P4 behavior
else
  rm -f "$HERE/$SHAPED_ZIP.tmp"
  ( cd "$GTFS_OUT" && zip -q -r -X "$HERE/$SHAPED_ZIP.tmp" . )
  mv "$HERE/$SHAPED_ZIP.tmp" "$HERE/$SHAPED_ZIP"
fi
echo "shaped feed → $SHAPED_ZIP"

if [[ "$PROFILE" == "seoul" ]]; then
  docker compose -f compose.seoul-otp.yml --profile build run --rm otp-build
else
  # Host-JVM build in a scratch dir holding only this build's inputs, so OTP
  # cannot pick up a stray feed/PBF from elsewhere.
  GRAPH_BUILD="$DEPLOY_DIR/graph-build"
  rm -rf "$GRAPH_BUILD"; mkdir -p "$GRAPH_BUILD"
  ln -s "$HERE/$OTP_OSM" "$GRAPH_BUILD/national.osm.pbf"
  ln -s "$HERE/$SHAPED_ZIP" "$GRAPH_BUILD/national.gtfs.zip"
  cp config/otp-national/otp-config.json "$GRAPH_BUILD/otp-config.json"
  # build-config.json sources point at the container path; rewrite to this dir.
  sed "s#file:///var/opentripplanner/#file://$HERE/$GRAPH_BUILD/#g" \
    config/otp-national/build-config.json > "$GRAPH_BUILD/build-config.json"
  "$OTP_JAVA" "-Xmx$OTP_BUILD_XMX" -XX:+UseStringDeduplication \
    -jar "$OTP_JAR" --build --save "$GRAPH_BUILD"
  mv "$GRAPH_BUILD/graph.obj" "$DEPLOY_DIR/graph.obj"
fi
test -s "$DEPLOY_DIR/graph.obj" || { echo "graph build produced no graph.obj" >&2; exit 1; }
echo "graph.obj rebuilt → $DEPLOY_DIR/graph.obj. Update release-manifest.json (digest/sha/graphVersion) before deploy."

echo "== step 5: release bundle (원자성) =="
echo "$DEPLOY_DIR/release-manifest.json 의 graph.sha256/gtfsShapedSha256/coverageIndexSha256/graphVersion/"
echo "otp jar·image 식별값을 이번 산출물로 갱신했는지 확인 후:"
echo "  python3 runner/verify_release_bundle.py --manifest $DEPLOY_DIR/release-manifest.json --write   # bundleHash 기록"
echo "  python3 runner/verify_release_bundle.py --manifest $DEPLOY_DIR/release-manifest.json           # 배포 전 재검증(부분 스왑 차단)"
