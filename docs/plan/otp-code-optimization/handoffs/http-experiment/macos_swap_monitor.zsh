#!/bin/zsh

# Monitor macOS VM swap counters for one child process and append a durable log.
# A SWAP_EVENT line is emitted as soon as a counter increases.

set -u

if (( $# < 2 )); then
  print -u2 "usage: $0 <pid> <output> [interval-seconds]"
  exit 2
fi

pid="$1"
output="$2"
interval="${3:-1}"

mkdir -p "${output:h}"

timestamp() {
  date -u '+%Y-%m-%dT%H:%M:%S%z'
}

read_counters() {
  local vm
  vm="$(vm_stat 2>&1)" || {
    print "$(timestamp) SWAP_MONITOR_ERROR vm_stat_failed=${vm}" >> "$output"
    return 1
  }
  local in out
  in="$(print -r -- "$vm" | awk '/Swapins:/ { gsub("\\.", "", $2); print $2; exit }')"
  out="$(print -r -- "$vm" | awk '/Swapouts:/ { gsub("\\.", "", $2); print $2; exit }')"
  if [[ -z "$in" || -z "$out" ]]; then
    print "$(timestamp) SWAP_MONITOR_ERROR swap_counters_missing" >> "$output"
    return 1
  fi
  print "$in $out"
}

baseline="$(read_counters)" || exit 3
prev_in="${baseline%% *}"
prev_out="${baseline##* }"
print "$(timestamp) START pid=${pid} swapins=${prev_in} swapouts=${prev_out} interval=${interval}s" >> "$output"

while kill -0 "$pid" 2>/dev/null; do
  sleep "$interval"
  current="$(read_counters)" || continue
  current_in="${current%% *}"
  current_out="${current##* }"
  delta_in=$(( current_in - prev_in ))
  delta_out=$(( current_out - prev_out ))
  print "$(timestamp) SAMPLE pid=${pid} swapins=${current_in} swapouts=${current_out} deltaIn=${delta_in} deltaOut=${delta_out}" >> "$output"
  if (( delta_in > 0 || delta_out > 0 )); then
    print "$(timestamp) SWAP_EVENT pid=${pid} swapinsDelta=${delta_in} swapoutsDelta=${delta_out} totals=${current_in},${current_out}" >> "$output"
  fi
  prev_in="$current_in"
  prev_out="$current_out"
done

final="$(read_counters 2>/dev/null || true)"
print "$(timestamp) END pid=${pid} final=${final:-unavailable}" >> "$output"
