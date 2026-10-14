#!/bin/bash
# usage: 06_run.sh route_id d0 d1 A B
D=$(dirname "$0")
T=$(mktemp)
{ echo "\\set rid '$1'"; echo "\\set d0 '$2'"; echo "\\set d1 '$3'"; echo "\\set A $4"; echo "\\set B $5"; cat "$D/06_raw_position_check.sql"; } > "$T"
"$D/q.sh" "$T"; rm -f "$T"
