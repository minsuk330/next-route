#!/bin/zsh

# Start one OTP variant and attach the macOS swap monitor. The caller owns the
# experiment directory and must use the same graph/configuration for A and H.

set -u

if (( $# < 5 )); then
  print -u2 "usage: $0 <jar> <base-dir> <port> <log-dir> <swap-monitor> [process-monitor]"
  exit 2
fi

jar="$1"
base_dir="$2"
port="$3"
log_dir="$4"
swap_monitor="$5"
process_monitor="${6:-}"
java_bin="/opt/homebrew/opt/openjdk@26/bin/java"

mkdir -p "$log_dir"
server_log="$log_dir/server.log"
gc_log="$log_dir/gc.log"
pid_file="$log_dir/server.pid"
status_file="$log_dir/server.status"

print "start=$(date -u '+%Y-%m-%dT%H:%M:%S%z')" > "$status_file"
print "jar=$jar" >> "$status_file"
print "baseDir=$base_dir" >> "$status_file"
print "port=$port" >> "$status_file"
print "jvmFlags=-Xms8g -Xmx8g -XX:+UseG1GC -Xlog:gc*,safepoint:file=${gc_log}:time,uptime,level,tags" >> "$status_file"

"$java_bin" -Xms8g -Xmx8g -XX:+UseG1GC \
  "-Xlog:gc*,safepoint:file=${gc_log}:time,uptime,level,tags" \
  -jar "$jar" --load --bindAddress 127.0.0.1 --port "$port" "$base_dir" \
  > "$server_log" 2>&1 &
server_pid=$!
print "$server_pid" > "$pid_file"

"$swap_monitor" "$server_pid" "$log_dir/swap.log" 1 > "$log_dir/swap-monitor.stdout" 2>&1 &
monitor_pid=$!

process_monitor_pid=""
if [[ -n "$process_monitor" ]]; then
  "$process_monitor" "$server_pid" "$log_dir/process.csv" 0.1 > "$log_dir/process-monitor.stdout" 2>&1 &
  process_monitor_pid=$!
fi

cleanup() {
  kill "$server_pid" 2>/dev/null || true
  kill "$monitor_pid" 2>/dev/null || true
  [[ -z "$process_monitor_pid" ]] || kill "$process_monitor_pid" 2>/dev/null || true
}
trap cleanup INT TERM

wait "$server_pid"
server_rc=$?
kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true
if [[ -n "$process_monitor_pid" ]]; then
  kill "$process_monitor_pid" 2>/dev/null || true
  wait "$process_monitor_pid" 2>/dev/null || true
fi
print "exit=$server_rc" >> "$status_file"
exit "$server_rc"
