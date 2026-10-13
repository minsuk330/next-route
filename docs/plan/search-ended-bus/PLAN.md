# 길찾기에서 운행 종료 버스 경로 빼기 (#400) — PLAN

티어 M. 브랜치 `fix/search-ended-bus` (main 9c9057b9 기준).

## 배경
- 2026-10-12 23:3x 강남역 → 서울역 검색에서 1위가 `740 > 750B(삼각지역)`였다.
  - 750B 실시간 도착은 1·2번째 모두 `운행종료`였다.
  - OTP 시간표(GTFS)에는 그 시각에도 750B가 다니는 것으로 나온다. 2026-10-13 23:35 출발로 재검색해도 4번째에 그대로 나왔다.
- 사용자 결정(2026-10-13, 메모 feedback-server-static-search-front-realtime):
  - 실시간 `운행종료`인 버스 구간이 있는 경로는 서버가 숨겨도 된다.
  - 후보가 전부 그런 경로면 422로 응답한다.
  - `docs/plan/otp-search-transfer-arrival/PLAN.md:25,81`의 "강등·숨김 금지"는 이 경우에 한해 뒤집는다.

## 사실 (조사)
- 검색 흐름: `RouteSearchService.runOtpSearch` :182-218
  1. OTP 검색
  2. `ItineraryRanker`
  3. `LegacyRouteResponseMapper.toLegacy`
  4. `withRealtime`일 때만 `TransferArrivalEnricher.enrich` (:215-217)
  - ODSAY 분기도 같은 enricher를 쓴다(:165-169).
- enricher는 버스 구간마다 서울 `getArrInfoByRoute`를 불러 `LaneCtx.arrMatched`(`BusArrivalInfo`)를 얻는다(:340-352).
  - 이 응답에 `arrivalMsg1/2`, `firstBusTime`, `lastBusTime`이 있지만 읽는 곳이 없다.
- 커버리지는 사실상 서울뿐이다. 경기·TAGO 노선은 `TransferStopResolver.resolveRoute`가 비워 `UNSUPPORTED_ROUTE`가 된다.
- `RouteOutOfServiceException`은 알림 컨트롤러 안에서만 422로 바뀐다. 검색 컨트롤러에서 던지면 500이 된다.
  - 검색 쪽 422는 `BusinessException` + 새 ErrorCode로 낸다. `GlobalExceptionHandler`가 `{code,message}` 본문으로 바꿔 준다.
- `pathKey`는 컨트롤러의 `snapshotRecorder.attach`가 검색 뒤에 매긴다. 서비스에서 경로를 빼도 키가 어긋나지 않는다.

## 규칙 (결정)
1. **판정**
   - 버스 구간의 노선(lane) 하나가 "운행 종료"인 조건:
     - `arrMatched.arrivalMsg1`가 `운행종료`이고,
     - `arrivalMsg2`도 `운행종료` 또는 비어 있으며,
     - 지금이 그 노선 첫차 시각 이후다(첫차 전은 아직 운행 시작 전이라 판정하지 않는다).
   - 첫차 시각을 파싱하지 못하면 운행 종료로 본다.
   - 구간의 lane이 **전부** 운행 종료일 때만 그 구간이 운행 종료다. 같은 정류장의 대체 노선이 하나라도 살아 있으면 남긴다.
2. **숨김**: 운행 종료 구간이 하나라도 있는 경로는 응답에서 뺀다. 남은 경로의 순서는 그대로 둔다.
3. **전부 막힘**: 원래 경로가 1개 이상이었는데 전부 빠지면 422 `ROUTE_ALL_ENDED`를 던진다. 메시지는 "지금은 탈 수 있는 경로가 없어요"다.
4. **적용 범위**
   - `withRealtime=true`(지금 출발 검색)일 때만 적용한다. 미래 출발 검색은 실시간과 무관하므로 건드리지 않는다.
   - OTP·ODSAY 두 분기 모두 enricher 뒤에서 같은 필터를 쓴다.
5. **실시간을 못 읽은 경우**: 타임아웃·차단·미지원·경기·TAGO는 판정 근거가 없으므로 남긴다.
6. **응답 계약**: 새 enum 값이나 필드를 추가하지 않는다. 빠진 경로는 응답에 나타나지 않는다. 앱의 `TransferArrival.Status` 디코딩에 영향이 없다.
7. **관측**
   - `RouteSearchContext`에 숨긴 경로 수를 남긴다.
   - INFO 로그: `[RouteSearch] 운행 종료 경로 제외 hidden=N kept=M routes=[750B]`
   - 메트릭: `route.search.ended.hidden` 카운터.

## 작업 순서
- [ ] `TransferArrivalEnricher`
  - lane 판정 결과를 밖으로 내보낸다. 경로 인덱스마다 "운행 종료 구간 있음" 집합을 담는다.
  - `enrich`의 반환을 `Enriched(result, Set<Integer> endedPaths, List<String> endedRoutes)`로 바꾸거나 별도 메서드로 둔다.
  - 판정 헬퍼 `BusServiceEnded.isEnded(BusArrivalInfo, Instant now)`(정적, 단위 테스트)를 만든다.
  - 기존 `enrich` 호출부와 테스트를 맞춘다.
- [ ] `RouteSearchService`
  - 두 분기 enricher 뒤에서 `dropEnded(result, ended)`를 부른다.
  - 원래 경로가 1개 이상이고 남은 경로가 0이면 `BusinessException(RouteErrorCode.ROUTE_ALL_ENDED)`를 던진다.
- [ ] `global/error/code/RouteErrorCode.java`를 새로 만든다. `ROUTE_ALL_ENDED`, 422.
- [ ] `RouteSearchControllerErrorTest`: 422 본문 `{code:"ROUTE_ALL_ENDED"}`.
- [ ] 테스트
  - `BusServiceEndedTest`
    - 둘 다 운행종료 → true
    - 첫차 전 → false
    - arrmsg1만 운행종료이고 arrmsg2에 "3분후" → false
    - null → false
  - `TransferArrivalEnricherTest`: lane 2개 중 1개만 종료 → 경로 유지, 2개 모두 종료 → 경로 표시.
  - `RouteSearchServiceOtpWiringTest`
    - 종료 경로 제외 + 순서 유지
    - 전부 종료 → 422
    - withRealtime=false → 그대로
    - enricher 실패 → 그대로
- [ ] `firstTm` 형식 확인: 운영에서 서울 `getArrInfoByRoute` 응답 한 건을 받아 형식(`yyyyMMddHHmmss` 또는 `HH:mm`)을 확인하고 파서를 맞춘다.

## 검증
- `./gradlew test --tests '*TransferArrivalEnricher*' --tests '*RouteSearchService*' --tests '*RouteSearchController*' --tests '*BusServiceEnded*' --tests '*RouteSearch*IT'`
- 배포 뒤 확인
  - 밤 막차 이후 강남역 → 서울역 검색에서 750B 경로가 빠지는지 본다. 로그 `운행 종료 경로 제외`를 확인한다.
  - 낮 검색은 경로 수가 그대로인지 본다(회귀 없음).

## 리스크
- 실시간 `운행종료` 오표기(차고지 회차·일시 공백) 때문에 실제로 탈 수 있는 경로를 숨길 수 있다.
  - 대응: 두 번째 도착까지 확인하고 첫차 전에는 판정하지 않는다. 로그·메트릭으로 감시한다.
- 경기·TAGO 버스는 이번에 판정하지 않는다(실시간 연결이 서울뿐). 필요하면 후속 이슈로 둔다.
