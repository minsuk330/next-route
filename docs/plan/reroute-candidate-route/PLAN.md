# reroute-candidate-route — PLAN (에이전트 실행용)

이슈: minsuk330/next-route#259. 티어 M. PR 1개. 마이그레이션 없음.
브랜치: `feat/reroute-candidate-route` (fresh main `3e261e23` 기준).

## 배경

앱이 재탐색 UI 를 지도 말풍선으로 바꾼다. 하차 정류장마다 말풍선을 띄우고, 누르면 그 후보의 상세
(`RouteDetail`)를 보여 주고, 새 경로를 지도에 겹쳐 그린다. 지금 후보 응답
(`RerouteCandidateSummary.display`)에는 요약 숫자와 `legSummaries` 문자열뿐이라 상세와 지도를
그릴 수 없다. 말풍선 위치도 서울 stopId 매칭에 기대고 있다.

## 확정 계약 (사용자 승인 2026-10-06)

1. `display.alight` 에 `x`(경도)·`y`(위도)를 `Double` 로 추가한다. 옛 저장 행이거나 못 구하면 null.
2. 신규 `GET /api/journeys/{journeyId}/reroute-proposals/{proposalId}/candidates/{candidateId}/route`

```jsonc
{
  "proposalId": 812,
  "candidateId": "c-2",
  "ride": {                // 지금 타는 구간. 타고 있지 않으면 null. 칸마다 nullable
    "legIndex": 1, "routeName": "2412", "fromStopName": "뚝섬역",
    "alightStopId": "121000123", "alightStopName": "성수역",
    "alightX": 127.0559, "alightY": 37.5446,
    "stopCount": 3, "alightAt": "2026-10-06T09:12:30Z"
  },
  "path": { /* 저장된 RemainingPathApply.path(PathResult) 그대로 = /api/route/search paths[] 원소 */ }
}
```

- 404: `JOURNEY_NOT_FOUND`, `REROUTE_PROPOSAL_NOT_FOUND`, `REROUTE_CANDIDATE_NOT_FOUND`
- 409: `REROUTE_PROPOSAL_STALE` (만료, 처리된 제안, 여정 종료, revision 이나 commandVersion 변경, 지나간 leg)
- 기각한 안
  - (a) `subPaths` 앞에 현재 탑승 구간을 합성해 붙이는 안: `path` 가 수락 원천과 달라져서 기각.
  - `rideSec`: GET 시점에는 이미 낡은 값이라 기각하고 `alightAt` 으로 대체.
- 선택항목 3(능동 제안 테스트 완화): 서버 변경 없음. 앱이 개발 빌드에 요청 진입점을 남긴다.

## 작업 순서

- [ ] **1. AlightStop 좌표**
  - `src/main/java/watoo/grd/nextroute/application/journey/dto/RerouteCandidateDisplay.java`
    - `AlightStop(String stopId, String name, Instant alightAt, boolean planned, Double x, Double y)` 로 바꾼다.
    - 옛 4인자 생성자는 남기고 x/y 를 null 로 둔다. 이 파일의 9인자 생성자가 쓰는 하위호환 패턴과 같다.
  - `src/main/java/watoo/grd/nextroute/application/journey/service/RemainingPathCandidateProvider.java:634`
    - `origin.waypoints().origin().x()/y()` 를 넣는다.
    - `Origin.waypoints().origin()` 은 계획 하차점이면 대기 leg 시작 좌표(`:182` planned), 중간 하차면 `RerouteOriginStop.x/y`(`:187`)다. 둘 다 경도/위도다.
  - 검증
    - `RerouteCandidateContractTest`: x/y 왕복, x/y 없는 옛 JSON 은 null 로 읽힘.
    - `RemainingPathCandidateProviderTest`: 계획/중간 하차 후보의 x/y 값.

- [ ] **2. 후보 읽기와 stale 판정 공용화** (1과 독립)
  - 신규 `application/journey/service/RerouteCandidateReader.java` (`@Component`)
    - `RerouteCandidate read(RerouteProposal proposal, String candidateId)`
      - 현 `RerouteAcceptTxService.candidateOf`(`:602-620`)를 그대로 옮긴다. 실패하면 `REROUTE_CANDIDATE_NOT_FOUND`.
    - `static boolean isStale(JourneySession journey, RerouteProposal proposal, RerouteCandidate candidate, Instant now)`
      - 현 `requireStillApplicable`(`:257-268`)의 조건식을 옮긴다.
  - `RerouteAcceptTxService` 가 reader 를 주입받아 쓰게 바꾼다. 수락 동작은 바뀌지 않는다.
  - 검증: `RerouteAcceptIT` 가 그대로 녹색이어야 한다.

- [ ] **3. 조회 유스케이스** (2에 의존)
  - 신규 `application/journey/port/in/GetRerouteCandidateRouteUseCase.java`
    - `RerouteCandidateRoute get(Long userId, Long journeyId, Long proposalId, String candidateId)`
  - 신규 `application/journey/dto/RerouteCandidateRoute.java`
    - `record RerouteCandidateRoute(Long proposalId, String candidateId, Ride ride, PathResult path)`
    - `record Ride(int legIndex, String routeName, String fromStopName, String alightStopId, String alightStopName, Double alightX, Double alightY, Integer stopCount, Instant alightAt)`
  - 신규 `application/journey/service/RerouteCandidateRouteQueryService.java` (`@Transactional(readOnly = true)`)
    - 처리 순서
      1. `sessionRepository.findByIdAndUserIdAndDeletedAtIsNull` 로 여정을 읽는다. 없으면 `JOURNEY_NOT_FOUND`.
      2. 여정 status 가 ACTIVE 가 아니면 `STALE`.
      3. `proposalRepository.findById` 결과의 journeyId 가 일치해야 한다. 아니면 `REROUTE_PROPOSAL_NOT_FOUND`.
      4. `reader.read` 로 후보를 읽는다.
      5. `proposal.status != ACTIVE` 이거나 `isStale` 이면 `STALE`.
      6. ride 를 조립한다.
      7. `path = ((RemainingPathApply) apply).path()` 를 그대로 반환한다. 복사나 가공은 하지 않는다.
    - ride 조립
      - 어느 leg, 어느 하차점인가
        - 중간 하차(`origin != null && origin.kind == RIDE_INTERIOR`): leg 는 `origin.legIndex`, 하차점은 `origin.stopId` / `origin.seq`.
        - 계획 하차(그 외): leg 는 `fromLegIndex - 1`, 하차점은 `ride.endStopId`.
      - 다음 중 하나면 `null` 을 돌려준다.
        - leg 가 없다.
        - `!type.isRiding()` 이다.
        - `state != ACTIVE` 다.
        - `legIndex != journey.currentLegIndex` 다.
      - 칸별 출처
        - `routeName`: `ride.selectedRouteOptionId` → `JourneyLegRouteOptionRepository.findById` → `routeName`. 공백이면 null. 참고: `SubwayRingPushEnqueueService.lineNameOf:194`.
        - `fromStopName`: `ride.startName`.
        - `alightStopName`, `alightX`, `alightY`, `alightAt`: `display.alight` 의 값.
          - alight 가 null(옛 행)이면 각각 `ride.endName`, null, null, `ride.predictedEndAt`.
          - `alightStopId` 는 위에서 정한 하차점이다.
        - `stopCount`
          - 계획 하차: `ride.remainingStops`.
          - 중간 하차: `BusRideLocator.locate(ride, properties.getNextStop().getProgressMaxAgeSec(), now)` 가 성공하고 `origin.seq - currentSeq > 0` 이면 그 값, 아니면 null.
    - 지나간 하차점 걸러내기(validateOnRead)는 하지 않는다. 목록(`/active`)이 맡고, 수락은 `REROUTE_ALIGHT_UNAVAILABLE` 로 막힌다.
  - 검증: `RerouteCandidateRouteQueryServiceTest`(Mockito 단위)
    - 404 3종
    - STALE 5종(만료, status≠ACTIVE, revision 변경, 지나간 leg, 여정 비활성)
    - ride null(타고 있지 않음)
    - 계획 하차 stopCount
    - 중간 하차 stopCount, locate 실패 시 null
    - routeName 이 없을 때 null
    - 옛 행(alight null)
    - `path` 가 reader 결과와 동일 인스턴스

- [ ] **4. API** (3에 의존)
  - `infrastructure/adapter/in/api/journey/JourneyRerouteProposalController.java` 에 GET 매핑을 추가한다. `GetRerouteCandidateRouteUseCase` 를 주입받는다.
  - 응답 DTO 는 application dto `RerouteCandidateRoute` 를 그대로 반환한다. 그 안에 `apply` 가 없고, 담는 값이 곧 계약이다. 래핑이 필요해지면 `RerouteCandidateRouteResponse` 로 분리한다.
  - 검증: `JourneyRerouteProposalControllerTest`
    - 200 JSON 모양: `path.info`, `path.subPaths`, `ride.alightX`
    - 404 / 409 코드

- [ ] **5. 같은 원천 IT** (2·3 에 의존)
  - `src/test/java/watoo/grd/nextroute/application/journey/RerouteAcceptIT.java` 에 케이스를 추가한다.
    1. 심은 제안의 후보로 조회한다.
    2. `objectMapper.valueToTree(route.path())` 가 DB `reroute_proposal.candidates` 의 해당 후보 `apply.path` 와 같아야 한다.
    3. 이어서 같은 후보를 수락하면 성공해야 한다.
  - 중간 하차 시나리오(`givenRidingWithProgress` + `givenMidRideProposal`)에서 `ride.stopCount` 와 `ride.legIndex` 를 확인한다.

- [ ] **6. 문서**
  - `docs/plan/reroute-candidate-route/HANDOFF.md`(모바일 인계): 계약, 오류표, null 처리, 같은 후보는 캐시 가능, `path` 는 `toRouteOptions` 에 바로 넣는다는 점.
  - `implementation_note.md` 에 append 한다(확정, 함정, 좌표, 검증, 배포).

## 실행 명령

```
./gradlew compileJava compileTestJava
./gradlew test --tests '*RerouteCandidate*' --tests '*RemainingPathCandidateProvider*' \
  --tests '*JourneyRerouteProposalController*' --tests '*RerouteAcceptIT*'
```

IT 는 로컬 Postgres 가 필요하다. 못 띄우면 그 사실을 노트와 PR 에 명시한다.

## 배포

- 마이그레이션 없음. api 와 worker 이미지만 재빌드한다.
- 저장 JSON(`reroute_proposal.candidates`)에 칸이 늘어난다. 배포 중에 옛 이미지가 새 행을 읽을 때 `x/y` 를 unknown property 로 만나므로, 주입된 ObjectMapper 의 `FAIL_ON_UNKNOWN_PROPERTIES` 설정을 확인한다. Spring Boot 기본값은 false 다.
