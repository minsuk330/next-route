#!/bin/zsh

# Run one Baden official-corpus-derived open-loop HTTP load against a native OTP JVM.
# This is experiment tooling only; it is not part of OTP production code.

set -u

if (( $# < 5 )); then
  print -u2 "usage: $0 <role> <jar> <run-dir> <duration-seconds> <arrival-rate> [workers]"
  exit 2
fi

role="$1"
jar="$2"
run_dir="$3"
duration_s="$4"
arrival_rate="$5"
workers="${6:-32}"
port="${OTP_BADEN_PORT:-18080}"
graph="/private/tmp/otp-baden-memory-FRsabG/graph"
corpus="/private/tmp/otp-baden-memory-FRsabG/official/A-rerun/travelSearch.csv"
endpoint="http://127.0.0.1:${port}/otp/gtfs/v1"
java_bin="/opt/homebrew/opt/openjdk@26/bin/java"
script_dir="${0:A:h}"

mkdir -p "$run_dir"
server_log="$run_dir/server.log"
gc_log="$run_dir/gc.log"
request_log="$run_dir/requests.jsonl"
sampler_log="$run_dir/sampler.log"
process_csv="$run_dir/process.csv"
status_file="$run_dir/status.txt"
nmt_before="$run_dir/nmt-before-measurement.txt"
nmt_after="$run_dir/nmt-after-measurement.txt"
pressure_log="$run_dir/pressure-events.log"

# A run directory must never reuse readiness text or diagnostics from an older
# attempt: stale "Grizzly server running" text could mask a port-bind failure.
: > "$server_log"
: > "$gc_log"
: > "$request_log"
: > "$sampler_log"
: > "$process_csv"
: > "$pressure_log"

{
  print "role=$role"
  print "jar=$jar"
  print "jarSha256=$(shasum -a 256 "$jar" | awk '{print $1}')"
  print "graph=$graph"
  print "graphSha256=$(shasum -a 256 "$graph/graph.obj" | awk '{print $1}')"
  print "corpus=$corpus"
  print "corpusSha256=$(shasum -a 256 "$corpus" | awk '{print $1}')"
  print "jvm=$java_bin"
  print "jvmFlags=-Xms512m -Xmx10g -XX:+UseG1GC -XX:+NativeMemoryTracking=summary"
  print "cwd=$(pwd)"
  print "runner=$script_dir/$(basename "$0")"
  print "command=$java_bin -Xms512m -Xmx10g -XX:+UseG1GC -XX:+NativeMemoryTracking=summary -Xlog:gc*,safepoint:file=${gc_log}:time,uptime,level,tags -jar $jar --load --bindAddress 127.0.0.1 --port $port $graph"
  print "durationSeconds=$duration_s"
  print "arrivalRate=$arrival_rate"
  print "workers=$workers"
  print "startedAt=$(date -u '+%Y-%m-%dT%H:%M:%S%z')"
} > "$status_file"

vm_stat > "$run_dir/host-before-vmstat.txt"
memory_pressure -Q > "$run_dir/host-before-memory-pressure.txt" 2>&1 || true
sysctl -n vm.swapusage > "$run_dir/host-before-swapusage.txt" 2>&1 || true

# Never start a second runner against the shared fixed port.  A previous
# duplicate attempt mixed two JVM identities into one run directory; fail
# before launching anything so the continuation can stop and the run is
# unambiguously invalid instead of producing mixed request data.
port_pids="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
if [[ -n "$port_pids" ]]; then
  print "preflight=port_in_use port=$port pids=$port_pids" >> "$status_file"
  print "ready=0" >> "$status_file"
  print "generatorExit=port_in_use" >> "$status_file"
  print "finishedAt=$(date -u '+%Y-%m-%dT%H:%M:%S%z')" >> "$status_file"
  exit 4
fi

cleanup() {
  if [[ -n "${sampler_pid:-}" ]]; then
    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
  fi
  if [[ -n "${pressure_pid:-}" ]]; then
    kill "$pressure_pid" 2>/dev/null || true
    wait "$pressure_pid" 2>/dev/null || true
  fi
  if [[ -n "${server_pid:-}" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup INT TERM EXIT

"$java_bin" -Xms512m -Xmx10g -XX:+UseG1GC -XX:NativeMemoryTracking=summary \
  "-Xlog:gc*,safepoint:file=${gc_log}:time,uptime,level,tags" \
  -jar "$jar" --load --bindAddress 127.0.0.1 --port "$port" "$graph" \
  > "$server_log" 2>&1 &
server_pid=$!
print "serverPid=$server_pid" >> "$status_file"

ready=0
for _ in {1..600}; do
  if [[ -f "$server_log" ]] && rg -q "Grizzly server running" "$server_log"; then
    ready=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    break
  fi
  sleep 1
done
print "ready=$ready" >> "$status_file"
if (( ready == 0 )); then
  print "generatorExit=server_not_ready" >> "$status_file"
  exit 3
fi

# NMT snapshots are diagnostics outside the request measurement interval.  The
# first is taken after graph/server readiness and before the first scheduled
# request; the second is taken after the generator has completed and before the
# sampler/server are stopped.  A jcmd pause is therefore never part of a
# request's service-latency sample.
if [[ -x "/opt/homebrew/opt/openjdk@26/bin/jcmd" ]]; then
  "/opt/homebrew/opt/openjdk@26/bin/jcmd" "$server_pid" VM.native_memory summary scale=MB \
    > "$nmt_before" 2>&1 || print "nmtBeforeExit=$?" >> "$status_file"
else
  print "nmtBefore=unavailable" > "$nmt_before"
fi
print "nmtBefore=$nmt_before" >> "$status_file"

python3 "$script_dir/sample_native_otp.py" --pid "$server_pid" --output "$process_csv" --interval-s 1 \
  > "$sampler_log" 2>&1 &
sampler_pid=$!
python3 "$script_dir/monitor_host_pressure.py" --run-dir "$run_dir" --interval-s 1 \
  > "$run_dir/pressure-monitor.log" 2>&1 &
pressure_pid=$!
print "pressureLog=$pressure_log" >> "$status_file"

python3 "$script_dir/otp_baden_http_corpus.py" \
  --endpoint "$endpoint" \
  --corpus "$corpus" \
  --output "$request_log" \
  --arrival-rate "$arrival_rate" \
  --duration-s "$duration_s" \
  --workers "$workers" \
  --seed 20260916 \
  --include-response \
  > "$run_dir/generator.log" 2>&1
generator_rc=$?
print "generatorExit=$generator_rc" >> "$status_file"

if [[ -x "/opt/homebrew/opt/openjdk@26/bin/jcmd" ]]; then
  "/opt/homebrew/opt/openjdk@26/bin/jcmd" "$server_pid" VM.native_memory summary scale=MB \
    > "$nmt_after" 2>&1 || print "nmtAfterExit=$?" >> "$status_file"
else
  print "nmtAfter=unavailable" > "$nmt_after"
fi
print "nmtAfter=$nmt_after" >> "$status_file"

kill "$sampler_pid" 2>/dev/null || true
wait "$sampler_pid" 2>/dev/null || true
sampler_pid=""
kill "$pressure_pid" 2>/dev/null || true
wait "$pressure_pid" 2>/dev/null || true
pressure_pid=""
kill "$server_pid" 2>/dev/null || true
wait "$server_pid" 2>/dev/null || true
server_rc=$?
print "serverExit=$server_rc" >> "$status_file"

vm_stat > "$run_dir/host-after-vmstat.txt"
memory_pressure -Q > "$run_dir/host-after-memory-pressure.txt" 2>&1 || true
sysctl -n vm.swapusage > "$run_dir/host-after-swapusage.txt" 2>&1 || true
print "finishedAt=$(date -u '+%Y-%m-%dT%H:%M:%S%z')" >> "$status_file"

if (( generator_rc != 0 )); then
  exit "$generator_rc"
fi
exit 0
