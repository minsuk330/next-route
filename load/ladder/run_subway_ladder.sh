#!/bin/zsh
# 지하철 이벤트 사다리 한 칸(#427). run_ladder.sh 에서 MODE=subway 로 부른다.
# usage: MODE=subway run_ladder.sh <journeys> on [duration=10m] [read_rps=20]
# 이벤트 경로만 잰다(폴링 대비 전후는 #423 몫) — 두 번째 인자는 on 만 받는다.
# 전제: 로컬 Postgres(st-pg, pg_stat_statements 적재)·Redis, env/*.env, docker(k6), 저장소 루트에서 bootJar 빌드됨.
set -u
N=$1; EVENT=$2; DURATION=${3:-10m}; READ_RPS=${4:-20}
[[ $EVENT == on ]] || { echo "지하철 사다리는 이벤트 on 만 잰다(폴링 대비는 #423)"; exit 2; }
HERE=${0:A:h}; REPO=${REPO:-$PWD}; OUT=${OUT:-$HERE/out}; mkdir -p $OUT
PG=${PG_CONTAINER:-st-pg}; PGU=${POSTGRES_USER:-myuser}; PGD=${POSTGRES_DB:-mydatabase}
SOURCE=${SUBWAY_SOURCE:-real}   # real = 서울 실시간 도착 API(프록시), model = 가상 열차(subway_model.py)
LABEL="subway-${SOURCE}-n${N}-${RUN_TAG:-r1}"
psqlq() { docker exec -i $PG psql -U $PGU -d $PGD -At -F $'\t' -c "$1"; }
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
WORKER_ONLY=(--journey.notification.relay.enabled=true --journey.notification.sender=loadtest)
# 지표를 긁을 JVM 들: "이름:포트". 한 JVM 이면 하나, SPLIT=1 이면 api·worker 둘.
JVMS=(app:8080)
if [[ ${SPLIT:-0} == 1 ]]; then
  # 운영 모양(compose: api = prod,api / worker = prod,worker — 컨테이너가 나뉘어 풀·힙·CPU 를 따로 쓴다).
  # api JVM = k6 대상 8080, worker 프로필 없음(→ !worker 빈인 지도 타일·워밍업이 뜬다, 운영과 같음), 발송처 미설정(noop).
  # worker JVM = 8081, 평가기·수집기·재평가·relay. 각자 Hikari 풀 20(기본값). 힙은 각 1g — 한 JVM 2g 를 반씩(합 동일).
  LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx1g -jar build/libs/*-SNAPSHOT.jar \
    --spring.profiles.active=dev,api --server.port=8080 $COMMON >$OUT/app-$LABEL.log 2>&1 & APP=$!
  LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx1g -jar build/libs/*-SNAPSHOT.jar \
    --spring.profiles.active=dev,worker,loadtest --server.port=8081 $COMMON $WORKER_ONLY >$OUT/app-$LABEL-worker.log 2>&1 & WORKER=$!
  JVMS=(api:8080 worker:8081)
else
  LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 java -Xmx2g -jar build/libs/*-SNAPSHOT.jar \
    --spring.profiles.active=dev,worker,loadtest $COMMON $WORKER_ONLY >$OUT/app-$LABEL.log 2>&1 & APP=$!
  WORKER=
fi
for j in $JVMS; do
  for i in {1..90}; do curl -sf localhost:${j#*:}/actuator/health >/dev/null && break; sleep 2; done
done
# 지표 스냅숏: 한 JVM 이면 예전 이름 그대로(prom-start-$LABEL.txt), 분리면 api 가 그 이름, worker 는 -worker 꼬리.
promsnap() { # $1 = prom-start | prom
  for j in $JVMS; do
    local suffix=""; [[ ${j%%:*} == worker ]] && suffix="-worker"
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
    docker stats --no-stream --format "{{.CPUPerc}}" $PG | tr -d '%' | awk -v t=$(date +%s) '{print t"\tpg\t"$1/100"\t"}'
    sleep 15
  done >$OUT/cpu-$LABEL.tsv ) & CPUS=$!
psqlq "select pg_stat_statements_reset()" >/dev/null
T_FROM=$(date +%s)
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
kill $APP $WORKER $FAKE 2>/dev/null; wait $APP $WORKER 2>/dev/null

# 6. 감지 지연(측정 창 = 워밍업 끝 ~ 끝 130초 전 — 여유 SLO 2분 안에 끝나지 못하는 꼬리를 미판정으로 세지 않게)
python3 -I $HERE/subway_detection.py $OUT/changes-$LABEL.tsv $OUT/samples-$LABEL.tsv $OUT/seed-$LABEL.tsv \
  $T_FROM $((T_END - 130)) >$OUT/detection-$LABEL.txt

# 7. 지우기 + 남은 여정 0 확인(같은 DB 를 쓰는 IT 의 선점 시험이 남의 여정을 잡지 않게)
LADDER_SUBWAY_SEED=true LADDER_CLEAN=true ./gradlew -q cleanTest test --tests '*SubwayLadderSeedIT' >/dev/null 2>&1
LEFT=$(psqlq "select count(*) from journey_session where status='ACTIVE'")
LEFT_MARKED=$(psqlq "select count(*) from journey_session where destination_summary->>'ladder' = 'LOAD-ST'")
LEFT_TOKENS=$(psqlq "select count(*) from user_push_token where token like 'loadtest-%'")
echo "leftover active_journeys=$LEFT ladder_journeys=$LEFT_MARKED loadtest_tokens=$LEFT_TOKENS" >$OUT/leftover-$LABEL.txt

SPLIT=${SPLIT:-0} MODE=subway python3 -I $HERE/summarize.py $OUT/prom-start-$LABEL.txt $OUT/prom-$LABEL.txt $OUT/k6-$LABEL.json \
  $OUT/fake-$LABEL.json "$LABEL" $OUT | tee $OUT/summary-$LABEL.txt
