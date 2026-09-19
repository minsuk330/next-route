#!/bin/zsh

# Sample the Java process RSS and CPU on macOS. Requires the same user (or
# approved system inspection permission) as the target process.

set -u

if (( $# < 2 )); then
  print -u2 "usage: $0 <pid> <output> [interval-seconds]"
  exit 2
fi

pid="$1"
output="$2"
interval="${3:-0.1}"
mkdir -p "${output:h}"
print "timestamp,pid,rssKiB,pmem,cpuPercent,elapsed,command" > "$output"

while kill -0 "$pid" 2>/dev/null; do
  line="$(ps -p "$pid" -o rss=,pmem=,pcpu=,etime=,command= 2>/dev/null || true)"
  if [[ -n "$line" ]]; then
    # ps right-aligns columns with variable whitespace. Parse by fields so
    # blank padding cannot shift RSS/CPU into the wrong CSV columns.
    parsed="$(print -r -- "$line" | awk '{
      rss=$1; pmem=$2; cpu=$3; elapsed=$4;
      command=$5;
      for (i=6; i<=NF; i++) command=command " " $i;
      gsub(",", ";", command);
      print rss "," pmem "," cpu "," elapsed "," command;
    }')"
    if [[ -n "$parsed" ]]; then
      print "$(date -u '+%Y-%m-%dT%H:%M:%S%z'),$pid,$parsed" >> "$output"
    else
      print "$(date -u '+%Y-%m-%dT%H:%M:%S%z'),$pid,,,,,process_not_visible" >> "$output"
    fi
  else
    print "$(date -u '+%Y-%m-%dT%H:%M:%S%z'),$pid,,,,,process_not_visible" >> "$output"
  fi
  sleep "$interval"
done
print "$(date -u '+%Y-%m-%dT%H:%M:%S%z'),$pid,,,,,END" >> "$output"
