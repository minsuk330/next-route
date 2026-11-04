# 버스 실시간 경로 다중 인스턴스 대비

## 뭘 하나
worker 를 2대 이상으로 늘려도 버스 공급자 호출이 서버 수만큼 늘지 않게 한다. 주기 수집과 도착 알림 폴러는 리더 한 대만 돌리고, 일일 몫은 Redis 카운터로 옮기고, 캐시 miss 는 Redis SET NX 로 서버들 중 한 대만 부르고 결과를 토큰별 결과 키로 나눠 준다. 경기와 TAGO 도 같은 구조(Redis 캐시, 서버 간 묶기, Redis 몫, 합산 동시 상한)로 맞춘다.

## 왜
지금은 수집기 2배, 예산 2배, 캐시 만료 때 같은 정류장 호출이 서버 수만큼 나간다. 리더 락은 지하철만 지킨다. 경기는 하루 10,000건뿐인데 몫을 아무도 안 세고 캐시가 서버마다 따로다.

## 핵심 결정
- 미리 아는 키(수집 노선, 알림 구독)는 리더 한 대: 기존 리더 락 재사용. 새 플래그 없음
- 미리 모르는 키(검색, 재탐색, 평가기 miss)는 서버 간 single-flight: 리더 대행(RPC, MQ) 기각
- 결과는 캐시가 아니라 토큰별 결과 키로 전달: 캐시 쓰기 실패와 "빈 결과 안 씀" 규칙에 기대지 않고, 이전 flight 의 실패를 잘못 읽지 않게. done 표시 방식 기각(검토)
- 리더는 잡은 뒤 캐시를 한 번 더 읽음(double-check): "1회" 가 간헐적으로 2회 되는 경합 제거
- 대기자는 리더의 OK·BLOCKED 만 받고, LIMITED·ERROR 는 직접 부름(리더 서버만의 사정일 수 있어 플래그 꺼짐과 같은 가용성 유지). 리더가 바뀌면 새 토큰을 따라감
- 일일 몫은 기존 `BusApiQuotaPort.tryAcquire` Redis 버킷으로. 경기 몫(8,000)은 어댑터 안 캐시 miss 뒤에만 셈
- TAGO 동시 상한은 OTP 게이트를 건드리지 않고 형제 `RedisNamedSemaphore` 로 서버 합산 8: 세션 30 공유 때문
- 차단기 Redis 화는 지역 버스 두 이름만, 플래그 아래, Redis 오류 시 JVM 폴백: 지하철 hot path 영향 기각
- 플래그 꺼짐이면 전부 기존 경로. 바뀌는 점(예산 fail-closed, 플래그 켬 때 알림 폴러가 Redis 에 묶임)은 PLAN 에 명시

## 건드는 곳
PR-1: BusArrivalScheduler, BusPositionScheduler, BusArrivalAlertScheduler, BusAlertDispatchService, BusArrivalService, BusPositionService, SeoulBusApiAdapter, BusApiCallBudget(삭제), BusApiQuotaPort, RedisLeaderLease(javadoc)
PR-2: CachedSearchTimeBusAdapter, CrossInstanceFlightPort(신규), RedisCrossInstanceFlight(신규), SearchTimeBusProperties, RouteSearchMetrics, SingleFlight(javadoc), 기존 IT 4개 생성자
PR-4: GyeonggiBusArrivalAdapter, TagoBusArrivalAdapter, RedisRegionalBusCacheAdapter(신규), RegionalBusCachePort(신규), RedisNamedSemaphore(신규), ApiCallBlockerPort(추출) + RedisApiCallBlocker(신규), RegionalArrivalLookup
PR-3: run_ladder.sh, run_subway_ladder.sh, MULTI-WORKER.md. 공통: application.yaml

## 리스크
- 수집기가 Redis 장애 때 멈춤(지금은 계속 불렀음). 리더 락을 켜면 버스 도착 알림도 Redis 장애 때 멈춤
- 리더 락을 켜면 지하철과 버스 수집이 한 대에 몰림(부하 집중)
- 포털 세션 30 을 GBIS, TAGO 버스, 지하철 TAGO 가 한 상한으로 나누는 것은 범위 밖

## 검증법
어댑터 3개(api 1 + worker 2 흉내)가 같은 Redis 를 쓸 때 동시 100건(동시 출발)의 공급자 호출이 플래그 끔 3회, 켬 1회(20회 반복 전부, 서울, 경기, TAGO 각각). worker 2대 사다리(하네스 보강 뒤)에서 리더 락 켬일 때 수집 호출이 분당 노선 수 × 1. 전체 테스트 통과.
