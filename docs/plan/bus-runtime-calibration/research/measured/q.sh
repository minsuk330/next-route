#!/bin/bash
# usage: q.sh file.sql [psql extra args]   -- runs read-only SQL on VPS prod Postgres (stdin)
f="$1"; shift
{ echo "SET statement_timeout='60s'; SET default_transaction_read_only=on;"; cat "$f"; } | \
ssh vps "cd /root/apps/nextroute && docker compose exec -T postgres sh -c 'psql -U \$POSTGRES_USER -d \$POSTGRES_DB -X -q $*'"
