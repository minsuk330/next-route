# PLAN — 버스 감시자 데이터량 예산 + 노선별 조회 방식

티어: M (단일 PR, 스키마 없음). 근거: 감시자 하나에 갇힌 변경, 공개 API·DB 계약 변화 없음.

## 사실 (조사 완료)
- `BusRouteWatcher` 는 노선 단위 `getArrInfoByRouteAll` 만 부른다. 몫 = `quota.tryAcquire("reeval-watcher-arrival", busWatcherDailyLimit)`(기본 0), 플래그 `JOURNEY_REEVAL_BUS_WATCHER`(기본 false)
- 정류장 단위 `getArrInfoByStop(stopId, routeId, ord, timeoutMs)` 가 같은 포트에 있음 — 평가기가 쓰는 것과 같은 호출, `CachedSearchTimeBusAdapter` 캐시 키 `bus:search:arr:{stop}:{route}:{seq}`
- 응답 크기 실측(143번, 정류장 113): 노선 259KB(정류장당 약 2.3KB), 정류장 2.4KB, gzip 미지원
- 감시자 지문(BusMark)은 정류장 단위 응답에서도 같은 필드(vehicleId1·sectionOrder1·arrivalMsg1·vehicleId2)를 얻음

## 변경
1. `BusQueryResult` 에 `long bytes` 추가(캐시 적중·실패는 0). 어댑터가 받은 본문 길이(바이트)를 넣는다
2. `BusApiQuotaPort` 에 `tryConsume(bucket, amount, dailyLimit)` — 바이트용. 호출 몫과 같은 Redis 일 카운터. 응답 크기는 받은 뒤에야 알므로 "받기 전 남은 몫 > 예상 크기면 부르고, 받은 뒤 실제 크기를 더함"
3. `RouteFetchPlanner`(순수 함수): 입력(k, 노선 정류장 수 또는 모름, 남은 호출 몫, 남은 바이트 몫) → ROUTE / STOPS / SKIP. 비용 = 호출/남은호출 + 바이트/남은바이트, 작은 쪽. 어느 쪽도 몫을 넘으면 SKIP
4. `BusRouteWatcher.tick`: 노선마다 planner 결정. STOPS 면 지켜보는 키마다 `getArrInfoByStop` 호출 후 같은 handle 경로(그룹 → 캐시 채우기 → 지문). ROUTE 면 지금처럼 + 정류장 수 기억
5. 바이트 몫 소진 = degraded(호출 몫 소진과 같음)
6. 설정: `bus-watcher-daily-bytes`(기본 0), 정류장당 예상 바이트 2400/노선 정류장당 2300(측정값, 설정으로 덮기 가능)
7. 지표: `journey.reeval.bus.calls{mode}`, `journey.reeval.bus.bytes{mode}`
8. 가짜 공급자(load/ladder) 응답 크기를 실측대로(노선 = 정류장 수 × 2.3KB)

## 계약 테스트
- planner: k=1·정류장 113 → STOPS / k=60 → ROUTE / 호출 몫 거의 바닥 → ROUTE / 바이트 몫 바닥 → STOPS 또는 SKIP / 정류장 수 모름 → STOPS / 둘 다 부족 → SKIP
- 감시자: STOPS 노선도 지문 변화 시 트리거, 캐시 채움 / 바이트 몫 소진 시 degraded·추가 호출 0 / 어댑터 bytes 기록(캐시 적중 0)
- 기존 BusRouteWatcher 테스트 전부 통과

## 미결 (사용자)
- 데이터 몫 숫자: 운영 월 수신량 중 다른 수집이 쓰는 양을 재고 남는 양에서 정함
- 감시자 켜기 자체: 공급자 호출 한도 상향 뒤

## 검증
컴파일 + 위 테스트 + 전체 `cleanTest test`(기존 무관 12) + 사다리 2,000 감시 켬/끔(감지 p95·호출·바이트) → 노트 `### 검증`
