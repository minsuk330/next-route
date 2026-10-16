#!/bin/zsh
# 지하철 이벤트 사다리 한 칸(#427). run_ladder.sh 에서 MODE=subway 로 부른다.
# usage: MODE=subway run_ladder.sh <journeys> on [duration=10m] [read_rps=20]
# 이벤트 경로만 잰다(폴링 대비 전후는 #423 몫) — 두 번째 인자는 on 만 받는다.
# 전제: 로컬 Postgres(st-pg, pg_stat_statements 적재)·Redis, env/*.env, docker(k6), 저장소 루트에서 bootJar 빌드됨.
set -u
N=$1; EVENT=$2; DURATION=${3:-10m}; READ_RPS=${4:-20}
[[ $EVENT == on ]] || { echo "지하철 사다리는 이벤트 on 만 잰다(폴링 대비는 #423)"; exit 2; }
HERE=${0:A:h}; REPO=${REPO:-$PWD}; OUT=${OUT:-$HERE/out}; mkdir -p $OUT
# DB·Redis 주소는 env(POSTGRES_HOST/PORT/USER/PASSWORD/DB, REDIS_HOST/PORT)로 받는다 — 앱(application.yaml)도 같은 env 를 읽는다.
# 하네스의 psql 은 기본이 로컬 컨테이너(docker exec st-pg). PG_CONTAINER= (빈 값)이면 주소로 붙는다(원격 DB, #458 VM 단계).
PG=${PG_CONTAINER-st-pg}; PGU=${POSTGRES_USER:-myuser}; PGD=${POSTGRES_DB:-mydatabase}
SOURCE=${SUBWAY_SOURCE:-real}   # real = 서울 실시간 도착 API(프록시), model = 가상 열차(subway_model.py)
LABEL="subway-${SOURCE}-n${N}-${RUN_TAG:-r1}"
if [[ -n $PG ]]; then
  psqlq() { docker exec -i $PG psql -U $PGU -d $PGD -At -F $'\t' -c "$1"; }
else
  psqlq() { PGPASSWORD=${POSTGRES_PASSWORD:-} psql -h ${POSTGRES_HOST:-localhost} -p ${POSTGRES_PORT:-5432} -U $PGU -d $PGD -At -F $'\t' -c "$1"; }
fi
rcli() { redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} "$@"; }
# worker 대수(#458, SPLIT=1 만). worker i 는 포트 8080+i, 지표 이름 worker(1번)·worker2·worker3…
WORKERS=${WORKERS:-1}
(( WORKERS >= 1 )) || { echo "WORKERS 는 1 이상"; exit 2; }
[[ $WORKERS -gt 1 && ${SPLIT:-0} != 1 ]] && { echo "WORKERS>1 은 SPLIT=1 만"; exit 2; }
# 수집 리더 잠금(journey.navigation.reeval.leader-lease). 끄면 모든 worker 가 "늘 리더"라 2대면 둘 다 수집한다 — 2대 이상이면 기본 켬.
LEASE=${LEADER_LEASE:-$([[ $WORKERS -gt 1 ]] && echo true || echo false)}
LEASE_KEY=lease:realtime-watch
wport() { echo $((8080 + $1)); }
wname() { [[ $1 == 1 ]] && echo worker || echo worker$1; }
cd $REPO

# 0. 지난 회차 지우기(버스·지하철 둘 다) — 남은 여정이 있으면 칸 이름보다 큰 부하를 잰다(버스 사다리 함정).
#    Gradle 은 환경 변수를 입력으로 안 봐서 cleanTest 없이는 조용히 건너뛴다.
LADDER_SEED=true LADDER_CLEAN=true ./gradlew -q cleanTest test --tests '*LadderSeedIT' >/dev/null 2>&1
LADDER_SUBWAY_SEED=true LADDER_CLEAN=true ./gradlew -q cleanTest test --tests '*SubwayLadderSeedIT' >/dev/null 2>&1
LEFT=$(psqlq "select count(*) from journey_session where status='ACTIVE'")
[[ $LEFT == 0 ]] || { echo "시작 전 진행 중 여정 $LEFT 건 — 지우기 실패, 중단"; exit 3; }

# 1. 가짜 공급자(지하철: real 이면 서울 API 로 넘기는 프록시, model 이면 가상 열차 + 변화 기록)
SUBWAY_SOURCE=$SOURCE SUBWAY_CHANGE_LOG=$OUT/changes-$LABEL.tsv python3 -I $HERE/fake_providers.py 18999 >$OUT/fake-$LABEL.log 2>&1 & FAKE=$!

# 2. 여정 심기. real 이면 지금 실제 ALL 한 장에서 열차가 있는 (노선, 역, 방향) 키를 뽑아 그 위에 심는다.
KEYS=""
if [[ $SOURCE == real ]]; then
  KEYS=$OUT/keys-$LABEL.tsv
  python3 -I $HERE/real_keys.py "$SEOUL_SUBWAY_ARRIVAL" $KEYS || { echo "실제 키 뽑기 실패"; kill $FAKE; exit 5; }
fi
LADDER_SUBWAY_KEYS=$KEYS LADDER_SUBWAY_SEED=true LADDER_JOURNEYS=$N LADDER_OUT=$OUT/seed-$LABEL.tsv \
  ./gradlew -q cleanTest test --tests '*SubwayLadderSeedIT' >$OUT/seedlog-$LABEL.log 2>&1
SEEDED=$(wc -l <$OUT/seed-$LABEL.tsv | tr -d ' ')
[[ $SEEDED == $N ]] || { echo "심은 여정 $SEEDED ≠ $N, 중단"; kill $FAKE; exit 4; }
python3 -I $HERE/tokens.py $OUT/seed-$LABEL.tsv $OUT/tokens.json

# 3. 앱(기본 = api+worker 한 JVM, SPLIT=1 = api·worker 두 JVM. loadtest 프로필 = 가짜 발송처). 지하철 수집 운영 시간(04:30~01:00)은 00:00~00:00 으로 풀어
#    늘 수집한다. 재탐색 능동 발행·보드는 외부 검색(OTP)을 부르므로 끈다(운영은 켜져 있음 — 한계에 적음).
#    로캘은 운영 컨테이너(LC_ALL=en_US.UTF-8)와 맞춘다. 역 이름 한글이 요청 줄에서 '?' 가 되는 것은 로캘이 아니라
#    Apache HttpClient 5 탓이다(fake_providers.py 주석, 노트 #427 함정).
COMMON=(
  --logging.level.watoo.grd.nextroute=WARN
  --management.endpoints.web.exposure.include=prometheus,health
  --seoul.api.bus-base-url=http://127.0.0.1:18999/api/rest
  --seoul.api.subway-base-url=http://127.0.0.1:18999/subway
  --ml.predictor.base-url=http://127.0.0.1:18999 --ml.predictor.enabled=true
  --realtime.subway.enabled=true --realtime.subway.persist-raw-arrivals=false
  --realtime.subway.active-hours.start=00:00 --realtime.subway.active-hours.end=00:00
  --realtime.subway.position.enabled=false
  --journey.navigation.expiry.enabled=false
  --journey.subway.ring.enabled=false
  --journey.reroute.alert.enabled=false --journey.reroute.board.enabled=false
  --journey.navigation.evaluator.enabled=true
  --journey.navigation.reeval.enabled=true --journey.navigation.reeval.shadow=false
  --journey.navigation.reeval.subway-trigger=true
  --journey.notification.fatigue.enabled=true
)
# 발송처·relay 는 운영에서도 worker 블록에만 있다(compose worker env_file 의 fcm.env). 여기선 FCM 대신 가짜 발송처.
# 접수 기록(push-*.tsv)은 worker 를 죽였다 살려도 이어 쓴다 — 재기동을 넘는 중복을 센다(#446).
# PUSH_ARGS = 가짜 발송처 장애 주입(#446 실험 3, README). 전면 장애는 OUTAGE_* 로 하네스가 제어 파일에 시각을 적는다.
# 접수 기록은 worker 마다 따로(push-$LABEL-w<i>.tsv) — 집계(notification_slo.py)가 합쳐 인스턴스 사이 중복까지 센다(#458).
OUTAGE_CTL=$OUT/outage-$LABEL.ctl; rm -f $OUTAGE_CTL $OUT/push-$LABEL.tsv $OUT/push-$LABEL-w*.tsv
WORKER_ONLY=(--journey.notification.relay.enabled=true --journey.notification.sender=loadtest
  --loadtest.push.outage.control-file=$OUTAGE_CTL
  --journey.navigation.reeval.leader-lease.enabled=$LEASE --logging.level.watoo.grd.nextroute.common.lock=INFO
  ${=PUSH_ARGS:-})
# 지표를 긁을 JVM 들: "이름:포트". 한 JVM 이면 하나, SPLIT=1 이면 api·worker 둘.
JVMS=(app:8080)
if [[ ${SPLIT:-0} == 1 ]]; then
  # 운영 모양(compose: api = prod,api / worker = prod,worker — 컨테이너가 나뉘어 풀·힙·CPU 를 따로 쓴다).
  # api JVM = k6 대상 8080, worker 프로필 없음(→ !worker 빈인 지도 타일·워밍업이 뜬다, 운영과 같음), 발송처 미설정(noop).
  # worker JVM = 8081, 평가기·수집기·재평가·relay. 각자 Hikari 풀 20(기본값). 힙은 각 1g — 한 JVM 2g 를 반씩(합 동일).
  LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx1g -jar build/libs/*-SNAPSHOT.jar \
    --spring.profiles.active=dev,api --server.port=8080 $COMMON >$OUT/app-$LABEL.log 2>&1 & APP=$!
  # worker 띄우기를 함수로 — 프로세스 장애 실험(CHAOS)이 같은 인자로 다시 띄운다.
  # $1 = worker 번호, $2 = 로그 꼬리, 나머지 = 덧붙일 인자. pids 파일에 "번호 pid" 를 덧붙인다(같은 번호는 마지막 줄이 지금 것).
  start_worker() {
    local i=$1 tail=$2; shift 2
    LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx1g -jar build/libs/*-SNAPSHOT.jar \
      --spring.profiles.active=dev,worker,loadtest --server.port=$(wport $i) $COMMON $WORKER_ONLY \
      --loadtest.push.log-path=$OUT/push-$LABEL-w$i.tsv "$@" >$OUT/app-$LABEL-$(wname $i)$tail.log 2>&1 &
    echo "$i $!" >>$OUT/pids-$LABEL
  }
  : >$OUT/pids-$LABEL
  JVMS=(api:8080)
  for i in {1..$WORKERS}; do start_worker $i ""; JVMS+=($(wname $i):$(wport $i)); done
else
  LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx2g -jar build/libs/*-SNAPSHOT.jar \
    --spring.profiles.active=dev,worker,loadtest $COMMON $WORKER_ONLY \
    --loadtest.push.log-path=$OUT/push-$LABEL-w1.tsv >$OUT/app-$LABEL.log 2>&1 & APP=$!
fi
wpid() { awk -v i=$1 '$1==i{p=$2} END{print p}' $OUT/pids-$LABEL; }
# 지금 리더인 worker 번호(journey_reeval_leader 게이지 = 1). 잠금 값은 인스턴스마다 무작위 UUID 라 Redis 값만으로는
# 누구인지 모른다 — 게이지로 찾고, 표본기가 Redis 키(값 앞 8자·PTTL)를 같이 적어 "키 하나·주인 하나"를 따로 남긴다.
leader_idx() {
  local i v
  for i in {1..$WORKERS}; do
    v=$(curl -s -m 2 localhost:$(wport $i)/actuator/prometheus 2>/dev/null | awk '/^journey_reeval_leader /{print int($2)}')
    [[ $v == 1 ]] && { echo $i; return; }
  done
}
for j in $JVMS; do
  for i in {1..90}; do curl -sf localhost:${j#*:}/actuator/health >/dev/null && break; sleep 2; done
done
# 지표 스냅숏: 한 JVM 이면 예전 이름 그대로(prom-start-$LABEL.txt), 분리면 api 가 그 이름, worker 는 -worker 꼬리.
promsnap() { # $1 = prom-start | prom
  for j in $JVMS; do
    local suffix=""; [[ ${j%%:*} == worker* ]] && suffix="-${j%%:*}"   # worker → -worker, worker2 → -worker2
    curl -s localhost:${j#*:}/actuator/prometheus >$OUT/$1-$LABEL$suffix.txt
  done
}

# 4. 표본기(1초마다 평가 기록 + 10초마다 주기 되돌림) → k6(전이 끔)
rm -f $OUT/stop-$LABEL
python3 -I $HERE/subway_sampler.py $OUT/samples-$LABEL.tsv $OUT/stop-$LABEL $OUT/seed-$LABEL.tsv >$OUT/sampler-$LABEL.log 2>&1 & SAMPLER=$!
WARMUP_S=${WARMUP_S:-180}
docker run --rm -v $HERE:/scripts -v $OUT:/work -e BASE_URL=http://host.docker.internal:8080 \
  -e TOKENS=/work/tokens.json -e DURATION=$DURATION -e READ_RPS=$READ_RPS -e MOVE=false \
  grafana/k6:2.1.0 run --summary-export=/work/k6-$LABEL.json /scripts/ladder.js >$OUT/k6-$LABEL.log 2>&1 &
K6=$!
sleep $WARMUP_S
promsnap prom-start
rm -f $OUT/stop-cpu-$LABEL
# CPU 표본(15초마다, 측정 창 동안): JVM 별 process/system_cpu_usage + Postgres 컨테이너 CPU(docker stats %, 코어 수로 환산).
# 스냅숏 두 장의 게이지는 기동 직후·부하 끝 값이라 부하 중 CPU 를 못 본다(예전 요약의 process_cpu_usage 0.0).
( while [[ ! -e $OUT/stop-cpu-$LABEL ]]; do
    for j in $JVMS; do
      curl -s localhost:${j#*:}/actuator/prometheus | awk -v t=$(date +%s) -v n=${j%%:*} \
        '/^process_cpu_usage /{p=$2} /^system_cpu_usage /{s=$2} END{print t"\t"n"\t"p"\t"s}'
    done
    [[ -n $PG ]] && docker stats --no-stream --format "{{.CPUPerc}}" $PG | tr -d '%' | awk -v t=$(date +%s) '{print t"\tpg\t"$1/100"\t"}'
    sleep 15
  done >$OUT/cpu-$LABEL.tsv ) & CPUS=$!
psqlq "select pg_stat_statements_reset()" >/dev/null
T_FROM=$(date +%s)
EVENTS=$OUT/events-$LABEL.tsv; : >$EVENTS
ev() { printf '%s\t%s\n' "$(date +%s)" "$1" >>$EVENTS; }
# 수집 리더 표본(#458, 1초마다): worker 별 리더 게이지·수집 성공 누적(subway_realtime_collect_duration count) + Redis 잠금 키.
# leader_check.py 가 "리더 2대 이상인 순간"·"두 대가 같이 수집한 주기"·"리더 kill → 다음 수집 성공" 을 여기서 센다.
rm -f $OUT/stop-leader-$LABEL
if [[ ${SPLIT:-0} == 1 ]]; then
  ( while [[ ! -e $OUT/stop-leader-$LABEL ]]; do
      t=$(date +%s)
      for i in {1..$WORKERS}; do
        curl -s -m 1 localhost:$(wport $i)/actuator/prometheus 2>/dev/null | awk -v t=$t -v n=$(wname $i) '
          /^journey_reeval_leader /{l=int($2)} /^subway_realtime_collect_duration_seconds_count\{.*collector="arrival"/{c+=$2}
          END{print t"\t"n"\t"(l==""?"down":l)"\t"(c==""?0:c)}'
      done
      printf '%s\tredis\t%s\t%s\n' $t "$(rcli get $LEASE_KEY 2>/dev/null | cut -c1-8)" "$(rcli pttl $LEASE_KEY 2>/dev/null)"
      sleep 1
    done >$OUT/leader-$LABEL.tsv ) & LEADERS=$!
fi
# 대기열 표본(#446): 5초마다 사다리 여정 알림의 보낼 때 된 PENDING·PENDING·PROCESSING·누적 SENT.
rm -f $OUT/stop-queue-$LABEL
( while [[ ! -e $OUT/stop-queue-$LABEL ]]; do
    psqlq "select extract(epoch from now())::bigint,
             count(*) filter (where n.status='PENDING' and n.scheduled_at <= now()),
             count(*) filter (where n.status='PENDING'), count(*) filter (where n.status='PROCESSING'),
             count(*) filter (where n.status='SENT')
           from journey_notification n join journey_session s on s.id = n.journey_id
          where s.destination_summary->>'ladder' = 'LOAD-ST'" 2>/dev/null
    sleep 5
  done >$OUT/queue-$LABEL.tsv ) & QUEUE=$!
# 알림 폭주(#446 실험 2): 측정 시작 STORM_AT_S 초 뒤 STORM_LINES 노선의 배차를 좁혀 일제히 빠듯으로 뒤집는다.
if [[ -n ${STORM_LINES:-} ]]; then
  ( sleep ${STORM_AT_S:-120}
    curl -s "localhost:18999/storm/start?lines=$STORM_LINES&seconds=${STORM_SECONDS:-240}" >/dev/null; ev storm-start
    sleep ${STORM_SECONDS:-240}; ev storm-end ) &
fi
# 발송처 전면 장애(#446 실험 3c): 폭주 시작 OUTAGE_AFTER_STORM_S 초 뒤 OUTAGE_SECONDS 초 동안 모든 발송 실패.
if [[ -n ${OUTAGE_SECONDS:-} ]]; then
  ( sleep $(( ${STORM_AT_S:-120} + ${OUTAGE_AFTER_STORM_S:-15} ))
    echo "$(date +%s) $OUTAGE_SECONDS ${OUTAGE_CODE:-UNAVAILABLE}" >$OUTAGE_CTL; ev outage-start
    sleep $OUTAGE_SECONDS; ev outage-end ) &
fi
# 프로세스 장애(#446 실험 4, SPLIT=1 만): 폭주 시작 CHAOS_AFTER_STORM_S 초 뒤 worker 를 kill -9, RESTART_AFTER_S 초 뒤 다시 띄움.
#  kill-worker  = 같은 인자로 재기동
#  redis-down   = 닫힌 Redis 포트(1)로 재기동해 REDIS_DOWN_S 초 돌린 뒤 정상 종료하고 정상 인자로 재기동
#                 (공유 st-redis 를 재시작하지 않으려고 "이 worker 만 Redis 를 못 보는" 장애로 흉내 낸다)
#  kill-leader  = (#458) 지금 수집 리더인 worker(게이지로 찾음)를 죽이고 같은 인자로 재기동. CHAOS_TARGET 무시
#  대상(#458): CHAOS_TARGET = 번호(기본 1) | leader | nonleader. 사건 이름에 대상이 붙는다(worker-kill9:w2:leader).
if [[ -n ${CHAOS:-} && ${SPLIT:-0} == 1 ]]; then
  ( sleep $(( ${STORM_AT_S:-120} + ${CHAOS_AFTER_STORM_S:-25} ))
    target=${CHAOS_TARGET:-1}; [[ $CHAOS == kill-leader ]] && target=leader
    lead=$(leader_idx)
    case $target in
      leader) T=$lead ;;
      nonleader) T=""; for i in {1..$WORKERS}; do [[ $i != $lead ]] && { T=$i; break; }; done ;;
      *) T=$target ;;
    esac
    [[ -z $T ]] && { ev "chaos-skip:no-target($target,leader=${lead:-none})"; exit 0; }
    role=nonleader; [[ $T == $lead ]] && role=leader
    kill -9 $(wpid $T); ev "worker-kill9:w$T:$role"
    sleep ${RESTART_AFTER_S:-8}
    if [[ $CHAOS == redis-down ]]; then
      start_worker $T "-noredis" --spring.data.redis.port=1; ev "worker-start-noredis:w$T"
      sleep ${REDIS_DOWN_S:-90}
      P=$(wpid $T); kill $P; while kill -0 $P 2>/dev/null; do sleep 1; done; ev "worker-stop-noredis:w$T"
      start_worker $T "-r"; ev "worker-start:w$T"
    else
      start_worker $T "-r"; ev "worker-start:w$T"
    fi ) &
  CHAOS_PID=$!
fi
wait $K6
T_END=$(date +%s)

# 5. 지표 수집(지우기 전에 DB 를 뜬다)
promsnap prom
touch $OUT/stop-cpu-$LABEL; wait $CPUS
curl -s localhost:18999/counts >$OUT/fake-$LABEL.json
touch $OUT/stop-$LABEL; wait $SAMPLER
psqlq "
with s as (select sum(total_exec_time) t from pg_stat_statements where dbid = (select oid from pg_database where datname = current_database())
             and query not like '%ladder-sampler%' and query not like '%ladder-recycle%' and query not like '%pg_stat_statements%')
select round((100 * p.total_exec_time / s.t)::numeric, 1), p.calls, round(p.total_exec_time::numeric), round(p.mean_exec_time::numeric, 2),
       left(regexp_replace(p.query, '\s+', ' ', 'g'), 220)
  from pg_stat_statements p, s
 where p.dbid = (select oid from pg_database where datname = current_database())
 order by p.total_exec_time desc limit 20" >$OUT/pgss-$LABEL.tsv
psqlq "
select n.checkpoint, coalesce(n.payload->>'delivery', 'none'), n.status, count(*),
       round(percentile_cont(0.5) within group (order by extract(epoch from n.sent_at - n.scheduled_at))::numeric, 3),
       round(percentile_cont(0.95) within group (order by extract(epoch from n.sent_at - n.scheduled_at))::numeric, 3),
       round(percentile_cont(0.95) within group (order by extract(epoch from n.scheduled_at - (n.created_at at time zone 'Asia/Seoul')))::numeric, 3),
       round(percentile_cont(0.95) within group (order by extract(epoch from n.sent_at - (n.created_at at time zone 'Asia/Seoul')))::numeric, 3),
       string_agg(distinct coalesce(n.last_failure_reason, ''), ',')
  from journey_notification n
 where n.journey_id in (select id from journey_session where destination_summary->>'ladder' = 'LOAD-ST')
   and (n.created_at at time zone 'Asia/Seoul') >= to_timestamp($T_FROM)
 group by 1, 2, 3 order by 1, 2, 3" >$OUT/notif-$LABEL.tsv
psqlq "
select count(*) from (select journey_id, route_revision from journey_notification
 where journey_id in (select id from journey_session where destination_summary->>'ladder' = 'LOAD-ST')
   and status = 'SENT' and checkpoint <> 'TRANSFER_TARGET_APPROACHING' and coalesce(payload->>'delivery', 'ALERTING') = 'ALERTING'
 group by 1, 2 having count(*) > 1) x" >$OUT/alerting-over1-$LABEL.txt
# 알림 행 원본(#446) — 지우기 전에 뜬다. notification_slo.py 가 SLO 네 줄을 여기서 계산한다.
touch $OUT/stop-queue-$LABEL; wait $QUEUE 2>/dev/null
psqlq "
select n.id, n.journey_id, n.route_revision, n.checkpoint, n.status, coalesce(n.payload->>'delivery', 'none'),
       coalesce(n.payload->>'seenTier', ''), coalesce(n.payload->>'seenSource', ''),
       coalesce(extract(epoch from (n.payload->>'firstSeenAt')::timestamptz)::text, ''),
       coalesce(n.payload->>'holdMs', ''),
       extract(epoch from (n.created_at at time zone 'Asia/Seoul')), extract(epoch from n.scheduled_at),
       coalesce(extract(epoch from n.sent_at)::text, ''), extract(epoch from n.expires_at), n.attempt_count,
       coalesce(n.last_failure_reason, '')
  from journey_notification n join journey_session s on s.id = n.journey_id
 where s.destination_summary->>'ladder' = 'LOAD-ST' order by n.id" >$OUT/notifrows-$LABEL.tsv
[[ -n ${CHAOS_PID:-} ]] && wait $CHAOS_PID 2>/dev/null
touch $OUT/stop-leader-$LABEL; [[ -n ${LEADERS:-} ]] && wait $LEADERS 2>/dev/null
kill $APP $FAKE 2>/dev/null; wait $APP 2>/dev/null
# 재기동한 worker 까지 남김없이(pids 파일 둘째 칸), 그래도 남은 worker 포트는 끝낸다.
[[ -s $OUT/pids-$LABEL ]] && kill $(awk '{print $2}' $OUT/pids-$LABEL) 2>/dev/null
sleep 2
if [[ ${SPLIT:-0} == 1 ]]; then for i in {1..$WORKERS}; do pkill -f "server.port=$(wport $i)" 2>/dev/null; done; fi
# 정상 종료가 길다(relay 워커 128 + 발송 중 행 정리) — 다음 칸이 같은 포트로 뜨기 전에 다 내려갔는지 기다리고, 30초 넘으면 -9.
#  대상은 이 칸이 띄운 pid 만(같은 기계의 다른 세션 JVM 을 건드리지 않게).
MINE=($APP); [[ -s $OUT/pids-$LABEL ]] && MINE+=($(awk '{print $2}' $OUT/pids-$LABEL))
for k in {1..30}; do
  alive=(); for p in $MINE; do kill -0 $p 2>/dev/null && alive+=($p); done
  (( ${#alive} == 0 )) && break; sleep 1
done
(( ${#alive} > 0 )) && kill -9 $alive 2>/dev/null

# 6. 감지 지연(측정 창 = 워밍업 끝 ~ 끝 130초 전 — 여유 SLO 2분 안에 끝나지 못하는 꼬리를 미판정으로 세지 않게)
python3 -I $HERE/subway_detection.py $OUT/changes-$LABEL.tsv $OUT/samples-$LABEL.tsv $OUT/seed-$LABEL.tsv \
  $T_FROM $((T_END - 130)) >$OUT/detection-$LABEL.txt

# 7. 지우기 + 남은 여정 0 확인(같은 DB 를 쓰는 IT 의 선점 시험이 남의 여정을 잡지 않게)
LADDER_SUBWAY_SEED=true LADDER_CLEAN=true ./gradlew -q cleanTest test --tests '*SubwayLadderSeedIT' >/dev/null 2>&1
LEFT=$(psqlq "select count(*) from journey_session where status='ACTIVE'")
LEFT_MARKED=$(psqlq "select count(*) from journey_session where destination_summary->>'ladder' = 'LOAD-ST'")
LEFT_TOKENS=$(psqlq "select count(*) from user_push_token where token like 'loadtest-%'")
echo "leftover active_journeys=$LEFT ladder_journeys=$LEFT_MARKED loadtest_tokens=$LEFT_TOKENS" >$OUT/leftover-$LABEL.txt

# 접수 기록: worker 별 파일을 쉼표로 묶어 넘긴다 — 합산해서 세고, 같은 행이 두 파일에 나오면 인스턴스 사이 중복.
PUSHES=(${(f)"$(ls $OUT/push-$LABEL-w*.tsv 2>/dev/null)"})
python3 -I $HERE/notification_slo.py $OUT/notifrows-$LABEL.tsv $OUT/queue-$LABEL.tsv ${(j:,:)PUSHES} \
  $T_FROM $T_END $OUT/events-$LABEL.tsv >$OUT/slo-$LABEL.txt
SPLIT=${SPLIT:-0} MODE=subway python3 -I $HERE/summarize.py $OUT/prom-start-$LABEL.txt $OUT/prom-$LABEL.txt $OUT/k6-$LABEL.json \
  $OUT/fake-$LABEL.json "$LABEL" $OUT | tee $OUT/summary-$LABEL.txt
cat $OUT/slo-$LABEL.txt | tee -a $OUT/summary-$LABEL.txt
if [[ -s $OUT/leader-$LABEL.tsv ]]; then
  python3 -I $HERE/leader_check.py $OUT/leader-$LABEL.tsv $OUT/events-$LABEL.tsv $T_FROM >$OUT/leader-$LABEL.txt
  cat $OUT/leader-$LABEL.txt | tee -a $OUT/summary-$LABEL.txt
fi
