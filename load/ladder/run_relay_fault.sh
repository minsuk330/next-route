#!/bin/zsh
# 발송(relay) 장애 주입 한 회차(#464). worker 두 대(A=PORT_A 8091, B=PORT_B 8092)를 띄우고 아웃박스에 일정 속도로 직접 적재하면서
# A 에 장애를 넣는다. 회수(heartbeat)와 증서(fencing)의 효과를 끔/켬으로 비교하려는 하네스다.
# usage: run_relay_fault.sh <회차 이름>
#   env: FAULT=KILL|STOP          KILL = kill -9 뒤 RESTART_AFTER_S 초에 재기동, STOP = kill -STOP 뒤 STOP_S 초에 kill -CONT
#        FAULT_AT=120             적재 시작 기준 장애 시각(초, 쉼표로 여러 번)
#        RATE=20 DURATION=420     적재 속도(건/초)와 시간(초)
#        CLAIM_TIMEOUT_S=45       회수 기준. 180 이면 MIN_TTL_S 를 600 으로 올려 부팅 검사를 통과시킨다(아래)
#        UNFENCED=0|1             1 = 증서 끔(journey.notification.relay.loadtest-unfenced)
#        PUSH_MIN_MS=2000 PUSH_MAX_MS=5000 PUSH_ERRORS=  가짜 발송처 지연(균등)과 실패(코드:비율)
#        TTL_MIX=180:0.5,600:0.5  PULL_WORKERS=128  DB_POOL_MAX_SIZE=20  SESSIONS=2000
# 전제: run_relay_capacity.sh 와 같다(st-pg·st-redis, env, bootJar).
set -u
NAME=$1
HERE=${0:A:h}; REPO=${REPO:-$PWD}; OUT=${OUT:-$HERE/out}; mkdir -p $OUT
PG=${PG_CONTAINER:-st-pg}; PGU=${POSTGRES_USER:-myuser}; PGD=${POSTGRES_DB:?별도 DB 를 POSTGRES_DB 로 준다}
PA=${PORT_A:-8091}; PB=${PORT_B:-8092}
LABEL="fault-$NAME"
psqlq() { docker exec -i $PG psql -U $PGU -d $PGD -At -F $'\t' -c "$1"; }
MARK="destination_summary->>'ladder' = 'LOAD-RELAY'"
FAULT=${FAULT:-KILL}; FAULT_AT=${FAULT_AT:-120}; RATE=${RATE:-20}; DURATION=${DURATION:-420}
CLAIM_TIMEOUT_S=${CLAIM_TIMEOUT_S:-45}; UNFENCED=${UNFENCED:-0}
cd $REPO

# 0~1. 별도 DB(같은 st-pg 를 쓰는 다른 실험과 섞이지 않게). 알림 행은 회차마다 비우고, 여정은 없을 때만 한 번 심는다
#      — Gradle 을 회차마다 돌리면 CPU 가 순간적으로 튀어 같은 기계의 다른 측정을 흔든다.
[[ $PGD == mydatabase ]] && { echo "공용 DB(mydatabase)에서는 돌리지 않는다"; exit 2; }
psqlq "delete from journey_notification" >/dev/null
HAVE=$(psqlq "select count(*) from journey_session where $MARK and status='ACTIVE'")
if [[ $HAVE != ${SESSIONS:-2000} ]]; then
  RELAY_SEED=true RELAY_CLEAN=true ./gradlew -q cleanTest test --tests '*RelayCapacitySeedIT' >/dev/null 2>&1
  RELAY_SEED=true RELAY_SESSIONS=${SESSIONS:-2000} RELAY_OUT=$OUT/seed-$LABEL.tsv \
    ./gradlew -q cleanTest test --tests '*RelayCapacitySeedIT' >$OUT/seedlog-$LABEL.log 2>&1
  HAVE=$(psqlq "select count(*) from journey_session where $MARK and status='ACTIVE'")
  [[ $HAVE == ${SESSIONS:-2000} ]] || { echo "심은 여정 $HAVE ≠ ${SESSIONS:-2000}, 중단"; exit 4; }
fi
psqlq "update journey_session set alert_claim_key = null where $MARK" >/dev/null 2>&1

# 2. worker 두 대. 회수 기준 180초는 "회수 기준 + 첫 재시도 간격 < 유효시간 바닥" 부팅 검사에 걸리므로 바닥을 600초로
#    올린다. 이 하네스는 적재기가 expires_at 을 직접 쓰므로 바닥 설정은 검사 말고는 쓰이지 않는다.
TTLARG=(); [[ $CLAIM_TIMEOUT_S -gt 60 ]] && TTLARG=(--journey.notification.min-ttl-seconds=${MIN_TTL_S:-600})
ERRARG=(); [[ -n ${PUSH_ERRORS:-} ]] && ERRARG=(--loadtest.push.errors=$PUSH_ERRORS)
rm -f $OUT/push-$LABEL-A.tsv $OUT/push-$LABEL-B.tsv $OUT/app-$LABEL-A.log $OUT/app-$LABEL-B.log
start_worker() { # $1 = A|B, $2 = port
  DB_POOL_MAX_SIZE=${DB_POOL_MAX_SIZE:-20} LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx1g -jar build/libs/*-SNAPSHOT.jar \
    --spring.profiles.active=dev,worker,loadtest --server.port=$2 \
    --logging.level.watoo.grd.nextroute=WARN --logging.level.watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService=INFO \
    --management.endpoints.web.exposure.include=prometheus,health \
    --collector.static-data.enabled=false --kma.weather.enabled=false \
    --realtime.subway.enabled=false --realtime.subway.position.enabled=false \
    --journey.navigation.evaluator.enabled=false --journey.navigation.reeval.enabled=false \
    --journey.navigation.expiry.enabled=false --journey.subway.ring.enabled=false \
    --journey.reroute.alert.enabled=false --journey.reroute.board.enabled=false \
    --journey.notification.fatigue.enabled=false \
    --journey.notification.relay.enabled=true --journey.notification.relay.mode=PULL \
    --journey.notification.relay.pull-workers=${PULL_WORKERS:-128} \
    --journey.notification.claim-timeout-seconds=$CLAIM_TIMEOUT_S \
    --journey.notification.relay.loadtest-unfenced=$([[ $UNFENCED == 1 ]] && echo true || echo false) \
    $TTLARG \
    --journey.notification.sender=loadtest \
    --loadtest.push.latency-min-ms=${PUSH_MIN_MS:-2000} --loadtest.push.latency-max-ms=${PUSH_MAX_MS:-5000} $ERRARG \
    --loadtest.push.log-path=$OUT/push-$LABEL-$1.tsv >>$OUT/app-$LABEL-$1.log 2>&1 &
  echo $!
}
wait_up() { for i in {1..90}; do curl -sf localhost:$1/actuator/health >/dev/null && return 0; sleep 2; done; return 1; }
A=$(start_worker A $PA); B=$(start_worker B $PB)
wait_up $PA && wait_up $PB || { echo "worker 안 뜸 — $OUT/app-$LABEL-*.log"; kill $A $B 2>/dev/null; exit 5; }

# 3. 표본기: 대기열(5초), 지표(5초, 두 대), CPU(5초)
STOP=$OUT/stop-$LABEL; rm -f $STOP
( while [[ ! -e $STOP ]]; do
    psqlq "select extract(epoch from now())::bigint,
             count(*) filter (where n.status='PENDING' and n.scheduled_at <= now()),
             count(*) filter (where n.status='PROCESSING'), count(*) filter (where n.status='SENT'),
             count(*) filter (where n.status='CANCELED'), count(*) filter (where n.status in ('DEAD','FAILED'))
           from journey_notification n join journey_session s on s.id = n.journey_id where s.$MARK" 2>/dev/null
    sleep 5
  done >$OUT/queue-$LABEL.tsv ) & Q=$!
PROM_RE='^(hikaricp_connections_(pending|active|acquire_seconds_(count|sum|max))|process_cpu_usage|journey_notification_(heartbeat_duration_seconds_(count|sum|max)|sent_total|reclaimed_total|stale_write_total|loadtest_unfenced_late_write_total|relay_inflight)|loadtest_push_(duplicate_id_total|received_total))'
( while [[ ! -e $STOP ]]; do
    t=$(date +%s)
    for w in A:$PA B:$PB; do
      curl -s -m 2 localhost:${w#*:}/actuator/prometheus 2>/dev/null | grep -E "$PROM_RE" | awk -v t=$t -v w=${w%%:*} '{print t"\t"w"\t"$0}'
    done
    sleep 5
  done >$OUT/prom-$LABEL.tsv ) & P=$!
( while [[ ! -e $STOP ]]; do
    t=$(date +%s)
    docker stats --no-stream --format "{{.CPUPerc}}" $PG | tr -d '%' | awk -v t=$t '{print t"\tpg\t"$1/100}'
    sleep 5
  done >$OUT/cpu-$LABEL.tsv ) & C=$!
psqlq "select pg_stat_statements_reset(0, (select oid from pg_database where datname = current_database()), 0)" >/dev/null

# 4. 장애 주입기(배경): 적재 시작 뒤 FAULT_AT 초마다 A 에 장애. 시각과 그 순간 A 가 들고 있던 행 수를 남긴다.
EV=$OUT/events-$LABEL.tsv; : >$EV
T0=$(date +%s)
( for at in ${(s:,:)FAULT_AT}; do
    while (( $(date +%s) < T0 + at )); do sleep 0.2; done
    inflight=$(curl -s -m 2 localhost:$PA/actuator/prometheus | awk '/^journey_notification_relay_inflight/{print $2; exit}')
    processing=$(psqlq "select count(*) from journey_notification where status='PROCESSING'")
    if [[ $FAULT == KILL ]]; then
      cur=$(cat $OUT/pidA-$LABEL 2>/dev/null || echo $A)
      echo "$(date +%s)\tkill\t${inflight:-?}\t$processing" >>$EV
      kill -9 $cur
      sleep ${RESTART_AFTER_S:-8}
      new=$(start_worker A $PA); echo $new >$OUT/pidA-$LABEL
      echo "$(date +%s)\trestart\t$new\t" >>$EV
      wait_up $PA
      echo "$(date +%s)\tup\t\t" >>$EV
    else
      echo "$(date +%s)\tstop\t${inflight:-?}\t$processing" >>$EV
      kill -STOP $A
      sleep ${STOP_S:-60}
      kill -CONT $A
      echo "$(date +%s)\tcont\t\t" >>$EV
    fi
  done ) & F=$!

# 5. 적재(전경) — 일정 속도 한 단계. 끝나면 대기열이 빌 때까지 기다린다.
GEN_OUT=$OUT/gen-$LABEL.tsv; : >$GEN_OUT
python3 -I $HERE/relay_load_gen.py --steps "$RATE:$DURATION" --ttl-mix ${TTL_MIX:-180:0.5,600:0.5} \
  --pg-container $PG --pg-user $PGU --pg-db $PGD --out $GEN_OUT 2>$OUT/gen-$LABEL.log
GEN_RC=$?
wait $F 2>/dev/null
for i in {1..$(( ${DRAIN_MAX_S:-720} / 5 ))}; do
  left=$(psqlq "select count(*) from journey_notification n join journey_session s on s.id = n.journey_id
                 where s.$MARK and n.status in ('PENDING','PROCESSING')")
  [[ $left == 0 ]] && break; sleep 5
done
echo "t0=$T0 drained=$(date +%s) left=${left:-?}" >$OUT/drain-$LABEL.txt
psqlq "select p.calls, round(p.total_exec_time::numeric, 1), round(p.mean_exec_time::numeric, 3),
         left(regexp_replace(p.query, '\s+', ' ', 'g'), 200)
       from pg_stat_statements p where p.dbid = (select oid from pg_database where datname = current_database())
        and p.query not like '%pg_stat_statements%' order by p.total_exec_time desc limit 30" >$OUT/pgss-$LABEL.tsv
AN=$(cat $OUT/pidA-$LABEL 2>/dev/null || echo $A)
curl -s localhost:$PA/actuator/prometheus >$OUT/prom-end-$LABEL-A.txt
curl -s localhost:$PB/actuator/prometheus >$OUT/prom-end-$LABEL-B.txt
touch $STOP; wait $Q $P $C 2>/dev/null

# 6. 알림 행 원본
psqlq "select n.id, extract(epoch from (n.created_at at time zone 'Asia/Seoul')), extract(epoch from n.scheduled_at),
         coalesce(extract(epoch from n.sent_at)::text, ''), extract(epoch from n.expires_at), n.status, n.attempt_count,
         n.requeue_count, coalesce(n.last_failure_reason, ''), coalesce(extract(epoch from n.last_requeued_at)::text, '')
       from journey_notification n join journey_session s on s.id = n.journey_id where s.$MARK order by n.id" \
  >$OUT/rows-$LABEL.tsv

# 7. 내리기(이 회차가 띄운 pid 만) + 지우기
for p in $AN $B; do kill -CONT $p 2>/dev/null; kill $p 2>/dev/null; done
for k in {1..30}; do kill -0 $AN 2>/dev/null || kill -0 $B 2>/dev/null || break; sleep 1; done
for p in $AN $B; do kill -0 $p 2>/dev/null && kill -9 $p; done
psqlq "delete from journey_notification" >/dev/null
echo "leftover notifications=$(psqlq "select count(*) from journey_notification")"  >$OUT/leftover-$LABEL.txt

# 8. 표
python3 -I $HERE/relay_fault.py $OUT $LABEL | tee $OUT/summary-$LABEL.txt
python3 -I $HERE/relay_fault_trace.py $OUT $LABEL | tee -a $OUT/summary-$LABEL.txt
(( GEN_RC == 0 )) || echo "🔴 생성기 실패(rc=$GEN_RC) — $OUT/gen-$LABEL.log" | tee -a $OUT/summary-$LABEL.txt
cat $OUT/leftover-$LABEL.txt
