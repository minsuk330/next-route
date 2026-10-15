#!/bin/zsh
# 부하 사다리 한 칸(#415-4). usage: run_ladder.sh <journeys> <event:on|off> [duration=10m] [read_rps=20]
# 전제: 로컬 Postgres/Redis(POSTGRES_*, REDIS_*), env/*.env, docker(k6), 저장소 루트에서 bootJar 빌드됨.
set -u
N=$1; EVENT=$2; DURATION=${3:-10m}; READ_RPS=${4:-20}
HERE=${0:A:h}; REPO=${REPO:-$PWD}; OUT=${OUT:-$HERE/out}; mkdir -p $OUT
LABEL="n${N}-refill-${REFILL:-true}-precheck-${PRECHECK:-true}-tol-${TOL:-30}"
cd $REPO

# 0. 공급자 호출 몫 카운터를 비운다(Redis 일 단위 카운터 — 앞 회차가 다 쓰면 평가가 공급자를 못 부르고 바로 끝나
#    처리량이 거짓으로 빨라진다). 사다리는 서버 단계의 포화 순서를 보므로 한도는 풀어 둔다(공급자 한도는 COST-MODEL.md).
redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} --scan --pattern 'bus:quota:*' | xargs -r redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} del >/dev/null 2>&1 \
  || docker exec st-redis sh -c "redis-cli --scan --pattern 'bus:quota:*' | xargs -r redis-cli del" >/dev/null 2>&1

# 1. 가짜 공급자
python3 -I $HERE/fake_providers.py 18999 & FAKE=$!

# 2. 여정 심기(지난 회차 지우고)
LADDER_SEED=true LADDER_CLEAN=true ./gradlew -q cleanTest test --tests '*LadderSeedIT' >/dev/null 2>&1
LADDER_SEED=true LADDER_JOURNEYS=$N LADDER_OUT=$OUT/ladder.tsv ./gradlew -q cleanTest test --tests '*LadderSeedIT' >$OUT/seed-$LABEL.log 2>&1
python3 -I $HERE/tokens.py $OUT/ladder.tsv $OUT/tokens.json

# 3. 앱(api+worker 한 JVM, 가짜 공급자에 붙임)
REEVAL=false; [[ $EVENT == on ]] && REEVAL=true
java -Xmx2g -jar build/libs/*-SNAPSHOT.jar \
  --spring.profiles.active=dev,worker \
  --logging.level.watoo.grd.nextroute=WARN \
  --management.endpoints.web.exposure.include=prometheus,health \
  --seoul.api.bus-base-url=http://127.0.0.1:18999/api/rest \
  --ml.predictor.base-url=http://127.0.0.1:18999 --ml.predictor.enabled=true \
  --realtime.subway.enabled=false \
  --bus.quota.search.arrival=100000000 --bus.quota.search.position=100000000 \
  --journey.navigation.expiry.enabled=false \
  --journey.notification.relay.enabled=false \
  --journey.subway.ring.enabled=false \
  --journey.navigation.evaluator.enabled=true \
  --journey.navigation.evaluator.refill-on-completion=${REFILL:-true} \
  --journey.query.version-precheck=${PRECHECK:-true} \
  --journey.navigation.evaluator.change-tolerance-seconds=${TOL:-30} \
  --journey.navigation.reeval.enabled=$REEVAL --journey.navigation.reeval.shadow=false \
  >$OUT/app-$LABEL.log 2>&1 & APP=$!
for i in {1..90}; do curl -sf localhost:8080/actuator/health >/dev/null && break; sleep 2; done

# 4. k6 (워밍업 뒤 지표 스냅숏을 한 번 떠 둔다 — 요약은 워밍업 뒤 구간만)
WARMUP_S=${WARMUP_S:-180}
docker run --rm -v $HERE:/scripts -v $OUT:/work -e BASE_URL=http://host.docker.internal:8080 \
  -e TOKENS=/work/tokens.json -e DURATION=$DURATION -e READ_RPS=$READ_RPS \
  grafana/k6:2.1.0 run --summary-export=/work/k6-$LABEL.json /scripts/ladder.js >$OUT/k6-$LABEL.log 2>&1 &
K6=$!
sleep $WARMUP_S
curl -s localhost:8080/actuator/prometheus >$OUT/prom-start-$LABEL.txt
wait $K6

# 5. 지표 수집
curl -s localhost:8080/actuator/prometheus >$OUT/prom-$LABEL.txt
curl -s localhost:18999/counts >$OUT/fake-$LABEL.json
kill $APP $FAKE 2>/dev/null; wait $APP 2>/dev/null
# 6. 심은 데이터 지우기 — 남겨 두면 같은 DB 를 쓰는 다른 IT 의 선점 시험이 엉뚱한 여정을 잡는다
LADDER_SEED=true LADDER_CLEAN=true ./gradlew -q cleanTest test --tests '*LadderSeedIT' >/dev/null 2>&1
python3 -I $HERE/summarize.py $OUT/prom-start-$LABEL.txt $OUT/prom-$LABEL.txt $OUT/k6-$LABEL.json $OUT/fake-$LABEL.json "$LABEL" | tee $OUT/summary-$LABEL.txt
