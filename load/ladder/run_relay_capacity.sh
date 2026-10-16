#!/bin/zsh
# 발송(relay) 용량 실험 한 회차(#461). 아웃박스에 알림 행을 계단식 속도로 직접 넣고, worker JVM 하나의 relay 만 잰다.
# usage: run_relay_capacity.sh <회차 이름> "<건/초:초,...>"
#   env: PUSH_MEAN_MS=60 (lognormal) | PUSH_MIN_MS=2000 PUSH_MAX_MS=5000 (균등)
#        PULL_WORKERS=128  DB_POOL_MAX_SIZE=20  SESSIONS=2000  WARMUP=10:60  TTL_MIX=180:0.2,600:0.8
#        STOP_ON_KNEE=1 (기본: 대기열이 늘어난 단계에서 멈춤, 0 = 끝까지)
#        DRAIN_MAX_S=660 (적재 끝난 뒤 대기열이 빌 때까지 기다리는 상한 — 최대 TTL 600초 + 여유)
#        PUSH_LOG=1 이면 가짜 발송처 접수 TSV 를 남긴다(기본 끔: 줄마다 파일을 여닫는 synchronized 라 수백 건/초에서
#        하네스 자체가 병목이 된다. 중복은 loadtest_push_duplicate_id 지표로 센다).
# 전제: 로컬 st-pg(pg_stat_statements)·st-redis, env(POSTGRES_*, REDIS_*), 저장소 루트에서 bootJar 빌드됨.
set -u
NAME=$1; STEPS=$2
HERE=${0:A:h}; REPO=${REPO:-$PWD}; OUT=${OUT:-$HERE/out}; mkdir -p $OUT
PG=${PG_CONTAINER:-st-pg}; PGU=${POSTGRES_USER:-myuser}; PGD=${POSTGRES_DB:-mydatabase}
LABEL="relay-$NAME"
psqlq() { docker exec -i $PG psql -U $PGU -d $PGD -At -F $'\t' -c "$1"; }
MARK="destination_summary->>'ladder' = 'LOAD-RELAY'"
PORT=${PORT:-8081}
cd $REPO

# 0. 지난 회차 지우기(사다리 표지 셋 다). 남은 진행 중 여정이 있으면 다른 부하가 섞인다.
for t in '*LadderSeedIT:LADDER_SEED' '*SubwayLadderSeedIT:LADDER_SUBWAY_SEED' '*RelayCapacitySeedIT:RELAY_SEED'; do
  env ${t#*:}=true LADDER_CLEAN=true RELAY_CLEAN=true ./gradlew -q cleanTest test --tests "${t%%:*}" >/dev/null 2>&1
done
LEFT=$(psqlq "select count(*) from journey_session where status='ACTIVE'")
[[ $LEFT == 0 ]] || { echo "시작 전 진행 중 여정 $LEFT 건 — 지우기 실패, 중단"; exit 3; }

# 1. 여정 심기
RELAY_SEED=true RELAY_SESSIONS=${SESSIONS:-2000} RELAY_OUT=$OUT/seed-$LABEL.tsv \
  ./gradlew -q cleanTest test --tests '*RelayCapacitySeedIT' >$OUT/seedlog-$LABEL.log 2>&1
SEEDED=$(cut -f3 $OUT/seed-$LABEL.tsv 2>/dev/null)
[[ $SEEDED == ${SESSIONS:-2000} ]] || { echo "심은 여정 ${SEEDED:-0} ≠ ${SESSIONS:-2000}, 중단"; exit 4; }

# 2. worker JVM 하나. relay 와 가짜 발송처만 일하게 평가기·재평가·수집기·만료·링·재탐색·피로 정책을 끈다
#    (피로 정책이 켜지면 발송 기록에 울릴 권리 잠금이 붙는다 — 이 실험은 정책 off 의 relay 를 잰다).
if [[ -n ${PUSH_MIN_MS:-} ]]; then
  LAT=(--loadtest.push.latency-min-ms=$PUSH_MIN_MS --loadtest.push.latency-max-ms=${PUSH_MAX_MS:?})
else
  LAT=(--loadtest.push.mean-ms=${PUSH_MEAN_MS:-60})
fi
PUSHLOG=(); [[ ${PUSH_LOG:-0} == 1 ]] && { rm -f $OUT/push-$LABEL.tsv; PUSHLOG=(--loadtest.push.log-path=$OUT/push-$LABEL.tsv); }
DB_POOL_MAX_SIZE=${DB_POOL_MAX_SIZE:-20} LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx2g -jar build/libs/*-SNAPSHOT.jar \
  --spring.profiles.active=dev,worker,loadtest --server.port=$PORT \
  --logging.level.watoo.grd.nextroute=WARN \
  --management.endpoints.web.exposure.include=prometheus,health \
  --collector.static-data.enabled=false --kma.weather.enabled=false \
  --realtime.subway.enabled=false --realtime.subway.position.enabled=false \
  --journey.navigation.evaluator.enabled=false --journey.navigation.reeval.enabled=false \
  --journey.navigation.expiry.enabled=false --journey.subway.ring.enabled=false \
  --journey.reroute.alert.enabled=false --journey.reroute.board.enabled=false \
  --journey.notification.fatigue.enabled=false \
  --journey.notification.relay.enabled=true --journey.notification.relay.mode=PULL \
  --journey.notification.relay.pull-workers=${PULL_WORKERS:-128} \
  --journey.notification.sender=loadtest $LAT $PUSHLOG >$OUT/app-$LABEL.log 2>&1 & APP=$!
for i in {1..90}; do curl -sf localhost:$PORT/actuator/health >/dev/null && break; sleep 2; done
curl -sf localhost:$PORT/actuator/health >/dev/null || { echo "worker 안 뜸 — $OUT/app-$LABEL.log"; kill $APP; exit 5; }

# 3. 표본기: 대기열(5초), CPU(5초), 지표(10초), 단계마다 pg_stat_statements 덤프·초기화
STOP=$OUT/stop-$LABEL; rm -f $STOP
( while [[ ! -e $STOP ]]; do
    psqlq "select extract(epoch from now())::bigint,
             count(*) filter (where n.status='PENDING' and n.scheduled_at <= now()),
             count(*) filter (where n.status='PROCESSING'), count(*) filter (where n.status='SENT'),
             count(*) filter (where n.status='CANCELED'), count(*) filter (where n.status in ('DEAD','FAILED'))
           from journey_notification n join journey_session s on s.id = n.journey_id where s.$MARK" 2>/dev/null
    sleep 5
  done >$OUT/queue-$LABEL.tsv ) & Q=$!
( while [[ ! -e $STOP ]]; do
    t=$(date +%s)
    ps -o %cpu= -p $APP | awk -v t=$t '{print t"\tjvm\t"$1/100}'
    docker stats --no-stream --format "{{.CPUPerc}}" $PG | tr -d '%' | awk -v t=$t '{print t"\tpg\t"$1/100}'
    sleep 5
  done >$OUT/cpu-$LABEL.tsv ) & C=$!
PROM_RE='^(hikaricp_connections_(pending|active|acquire_seconds_(count|sum|max))|jvm_threads_live_threads|jvm_gc_pause_seconds_(count|sum|max)|process_cpu_usage|journey_notification_(heartbeat_duration_seconds_(count|sum|max)|sent_total|claimed_total|relay_inflight|reclaimed_total|lost_total|relay_claim_size_(count|sum))|loadtest_push_(duplicate_id_total|received_total))'
( while [[ ! -e $STOP ]]; do
    curl -s -m 3 localhost:$PORT/actuator/prometheus | grep -E "$PROM_RE" | awk -v t=$(date +%s) '{print t"\t"$0}'
    sleep 10
  done >$OUT/prom-$LABEL.tsv ) & P=$!
GEN_OUT=$OUT/gen-$LABEL.tsv; : >$GEN_OUT; : >$OUT/pgss-$LABEL.tsv
pgss_dump() { # $1 = 단계 번호
  psqlq "select $1, p.calls, round(p.total_exec_time::numeric, 1), round(p.mean_exec_time::numeric, 3),
           left(regexp_replace(p.query, '\s+', ' ', 'g'), 200)
         from pg_stat_statements p where p.dbid = (select oid from pg_database where datname = current_database())
          and p.query not like '%pg_stat_statements%' order by p.total_exec_time desc limit 20" >>$OUT/pgss-$LABEL.tsv
  psqlq "select pg_stat_statements_reset()" >/dev/null
}
psqlq "select pg_stat_statements_reset()" >/dev/null
( seen=0; while [[ ! -e $STOP ]]; do
    n=$(grep -c '^#step' $GEN_OUT 2>/dev/null); n=${n:-0}
    if (( n > seen )); then (( seen > 0 )) && pgss_dump $(awk -F'\t' '/^#step/{s=$2} END{print s-1}' $GEN_OUT); seen=$n; fi
    sleep 1
  done ) & S=$!

# 4. 적재(전경). 끝나면 대기열이 빌 때까지(PENDING·PROCESSING 0) 기다린다 — 꼬리 행의 발송·만료를 다 본다.
WARMUP=${WARMUP-"10:60"}; WARM=(); [[ -n $WARMUP ]] && WARM=(--warmup $WARMUP)
KNEE=(); [[ ${STOP_ON_KNEE:-1} == 1 ]] && KNEE=(--stop-on-knee)
python3 -I $HERE/relay_load_gen.py --steps "$STEPS" $WARM $KNEE --ttl-mix ${TTL_MIX:-180:0.2,600:0.8} \
  --pg-container $PG --pg-user $PGU --pg-db $PGD --out $GEN_OUT 2>$OUT/gen-$LABEL.log
GEN_RC=$?
T_GEN_END=$(date +%s)
pgss_dump $(awk -F'\t' '/^#step/{s=$2} END{print s}' $GEN_OUT)
for i in {1..$(( ${DRAIN_MAX_S:-660} / 5 ))}; do
  left=$(psqlq "select count(*) from journey_notification n join journey_session s on s.id = n.journey_id
                 where s.$MARK and n.status in ('PENDING','PROCESSING')")
  [[ $left == 0 ]] && break; sleep 5
done
T_DRAIN=$(date +%s)
echo "gen_end=$T_GEN_END drained=$T_DRAIN left=${left:-?}" >$OUT/drain-$LABEL.txt
curl -s localhost:$PORT/actuator/prometheus >$OUT/prom-end-$LABEL.txt
touch $STOP; wait $Q $C $P $S 2>/dev/null

# 5. 알림 행 원본(지우기 전에). 시각은 전부 epoch — created_at 은 KST 벽시계(timestamp)라 AT TIME ZONE 으로 되돌린다.
psqlq "select n.id, extract(epoch from (n.created_at at time zone 'Asia/Seoul')), extract(epoch from n.scheduled_at),
         coalesce(extract(epoch from n.sent_at)::text, ''), extract(epoch from n.expires_at), n.status, n.attempt_count,
         coalesce(n.last_failure_reason, '')
       from journey_notification n join journey_session s on s.id = n.journey_id where s.$MARK order by n.id" \
  >$OUT/rows-$LABEL.tsv
ROWS=$(psqlq "select count(*), pg_size_pretty(pg_total_relation_size('journey_notification')) from journey_notification")
echo "journey_notification rows/size(전체 표) = $ROWS" >>$OUT/drain-$LABEL.txt

# 6. 내리기 — 이 회차가 띄운 pid 만, 30초 넘으면 -9
kill $APP 2>/dev/null
for k in {1..30}; do kill -0 $APP 2>/dev/null || break; sleep 1; done
kill -0 $APP 2>/dev/null && kill -9 $APP

# 7. 지우기 + 남은 것 0 확인
RELAY_SEED=true RELAY_CLEAN=true ./gradlew -q cleanTest test --tests '*RelayCapacitySeedIT' >/dev/null 2>&1
echo "leftover active_journeys=$(psqlq "select count(*) from journey_session where status='ACTIVE'") \
relay_journeys=$(psqlq "select count(*) from journey_session where $MARK") \
relay_tokens=$(psqlq "select count(*) from user_push_token where token like 'loadtest-relay-%'")" >$OUT/leftover-$LABEL.txt

# 8. 단계별 표
python3 -I $HERE/relay_capacity.py $OUT $LABEL | tee $OUT/summary-$LABEL.txt
(( GEN_RC == 0 )) || { echo "🔴 생성기 실패(rc=$GEN_RC) — $OUT/gen-$LABEL.log" | tee -a $OUT/summary-$LABEL.txt; }
cat $OUT/leftover-$LABEL.txt
