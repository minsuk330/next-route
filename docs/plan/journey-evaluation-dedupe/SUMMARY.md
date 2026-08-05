# 여정 평가 중복 조회 합치기 (Phase 4 ⑤)

## 뭘 하나

여러 여정이 같은 것을 물을 때 외부를 한 번만 부르게 한다. 조사해 보니 버스 도착·위치는 이미
합쳐지고 있었고, **ML 예측 호출과 지하철 역별 조회에만 구멍**이 있었다. 그 두 곳에 기존 버스 캐시와
같은 모양의 껍데기를 씌운다.

## 왜

지금은 여정 10,000건이면 30초마다 ML serving 을 10,000번(≈초당 333회) 부른다. 답이 모두에게 같은
질문이라 한 번이면 된다.

## 핵심 결정

- **ML 캐시 키 = feature 값 전체의 해시** — 같은 입력이면 같은 예측이라 틀릴 수가 없다. 버스 위치
  스냅샷이 갱신되면 키가 저절로 죽어 TTL 을 조일 필요도 없다 (조합 키 방식 기각)
- **ML 은 캐시만, 지하철은 캐시 + single-flight** — 지하철은 호출 단위가 역 이름 하나로 명확하고
  조회 경로도 쓴다. ML 은 호출 단위가 배치라 배치 키가 재사용되지 않아 이득이 없다
- **버스는 손대지 않는다** — 이미 Redis 15초 캐시 + single-flight 로 목표를 만족한다
- **차량 고르기는 그대로 여정별** — `userReadyAt` 이 여정마다 달라 합치면 안 된다(④ 제약).
  합치는 것은 원시 응답까지다
- **부하 실험은 이번 범위 밖** — ⑧ 부하 하네스가 맡는다. 여기서는 작은 통합 시험으로 통과 기준만 못박는다

## 건드는 곳

`MlPredictionCachePort`(신규) · `MlFeatureCacheKey`(신규) · `RedisMlPredictionCacheAdapter`(신규) ·
`CachedMlArrivalPredictorAdapter`(신규) · `MlPredictorProperties` ·
`SubwayStationArrivalCachePort`·`RedisSubwayStationArrivalCacheAdapter`·`CachedSubwayApiAdapter`(신규) ·
`SubwayStationArrivalProperties`·`SubwayRealtimeMetrics`(신규) · `RouteSearchMetrics` ·
`application.yaml` · `PHASE-4.md`. **마이그레이션 없음.**

## 리스크

- ML 캐시 효율이 **버스 위치 캐시 TTL 에 매달린다.** `TRANSFER_ARRIVAL_CACHE_TTL=0` 이면 상시 miss 가
  된다(오늘 동작으로 퇴화할 뿐 나빠지진 않음). 키 시험이 그 성질을 못박고 코드 주석에 남겼다
- 평가기는 꺼져 있어도 **검색 경로는 머지 즉시 캐시를 탄다.** 그래서 TTL 기본값을 짧게 둔다(ML 30초,
  지하철 10초)
- Redis 장애 시 전부 miss → 오늘과 같은 호출량. 요청 실패로는 절대 이어지지 않게 예외를 삼킨다

## 검증법

`./gradlew test` 전체 통과(기존 1049 tests 회귀 0). 그리고 **같은 정류장·노선을 보는 여정 3건이 ML
호출 1회, 같은 역을 세 번 물어도 지하철 호출 1회**임을 통합 시험이 단언한다. 캐시를 끄면 그 시험이
실제로 깨지는 것까지 확인했다(단언에 이빨이 있다).
