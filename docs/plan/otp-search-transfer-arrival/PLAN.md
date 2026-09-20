# OTP 검색 응답에 버스 도착예측 싣기 (계약 되돌림)

티어 M. 브랜치: fresh main 기준 `feat/otp-search-transfer-arrival`.

## 0. 배경 — 이전 계약이 취소됐다

- 2026-09-09 결정: "검색 응답은 정적 드롭인, 실시간·예측은 프론트가 별도 API"([[feedback-server-static-search-front-realtime]], `docs/plan/otp-search-static-seoul-id/`).
- **2026-09-20 사용자 지시로 이 계약 취소**: ODsay 시절처럼 **서버가 예측을 응답에 한 번에 실어 내린다.**
- 따라서 `docs/plan/transfer-predict-mobile/HANDOFF.md`(모바일이 직접 호출)는 **폐기한다**(사용자 결정 2026-09-20). 파일을 지운다 — 남겨 두면 모바일이 두 통로 중 무엇을 따라야 하는지 갈린다. 폐기 사유는 `implementation_note.md` 에 남긴다(git 이력에도 남는다).

## 1. 지금 상태 (실측 2026-09-20)

- OTP 분기(`RouteSearchService.runOtpSearch`)는 매핑만 하고 **enricher 3종을 통째로 건너뛴다**. 응답의 `subPaths[].transferArrivals` 는 항상 `null`.
- ODSAY 분기는 지금도 `withRealtime=true` 면 `TransferArrivalEnricher` 를 탄다(`RouteSearchService:163~168`).
- **enricher 가 요구하는 입력은 OTP 매핑이 이미 채운다**: `SubPathResult.startLocalStationID`, `LaneResult.busLocalBlID`/`busNo`(`TransferArrivalEnricher:308,318`). 프로덕션 응답 실측에서 303번 구간에 `startLocalStationID=123000579`, `busLocalBlID=100100053` 확인.
- 예측 자체도 정상: 같은 값으로 `/api/transfer/predict` 호출 시 `source=MODEL status=AVAILABLE waitSeconds=403`.
- 예산 현황: `route.search.deadline-ms=3000`(전체), `otp-ms=3500`(OTP 단독), `route.transfer-arrival.deadline-ms=1500`(이 단계 상한), `max-external-calls-per-search=12`, `max-concurrent-external-calls=8`.
- 현재 검색 지연 실측: 시청→강남 0.88s, 잠실→청계천 0.88s.

## 2. 확정 결정

1. **enricher 를 OTP 분기에 배선한다.** 새로 만들지 않는다 — ODSAY 분기가 쓰는 `TransferArrivalEnricher` 를 그대로 재사용한다. 두 분기가 다른 코드로 예측하면 값이 갈린다.
2. **`withRealtime` 플래그를 지킨다.** `search()`(공개 API)만 보강하고 `searchWithoutRealtime()`(알림 STATIC 경로, `RouteSearchTravelTimeEstimator:49`)은 그대로 둔다.
3. **버스 전용.** ODsay 시절과 동일(`trafficType==2`). 지하철 예측 모델은 없다 — 없는 것을 지어내지 않는다.
4. **순서는 건드리지 않는다.** enricher 는 값만 채운다. 재정렬·강등·MISSED 숨김은 여전히 금지 — 취소된 것은 "예측을 싣지 마라"까지이고, "서버가 경로를 골라 주지 마라"는 유효하다.
5. **전체 예산 `deadline-ms` 를 3000 → 4500 으로 올린다.** OTP 호출(실측 0.9~2.0s, 콜드 더 김)에 enricher 1.5s 를 더하면 3000 안에서는 상시로 잘린다. 잘리면 `meta.degraded=[bus_realtime]` 로 조용히 빈 값이 나가므로, 값을 실으라는 지시의 목적을 못 지킨다.
6. **실패는 degrade 로 끝낸다.** enricher 실패가 검색 자체를 죽이지 않는다(기존 `stage()` 계약 그대로). `transferArrivals=null` + `meta.degraded` 에 `bus_realtime`.

## 3. 변경

### 3-1. 본체
- [ ] `src/main/java/watoo/grd/nextroute/application/route/service/RouteSearchService.java`
  - `runOtpSearch(...)` 시그니처에 `boolean withRealtime`, `Instant searchStartedAt` 추가(호출부 `runSearch` 가 이미 둘 다 들고 있다 — `:100`).
  - 매핑 직후(`legacyRouteResponseMapper.toLegacy(...)` 결과)에 ODSAY 분기와 **같은 모양**으로 단계 추가:
    ```java
    RouteSearchResult mapped = legacyRouteResponseMapper.toLegacy((RoutingResult) outcome);
    if (!withRealtime) {
        return mapped;
    }
    RouteSearchResult afterBus = mapped;
    return stage(ctx, SearchComponent.BUS_REALTIME,
            () -> transferArrivalEnricher.enrich(afterBus, searchStartedAt, ctx));
    ```
  - 클래스 javadoc 과 `runOtpSearch` javadoc 의 "실시간 보강은 타지 않는다" 문구를 **고친다**(남겨 두면 다음 사람이 코드와 주석 중 무엇을 믿을지 모른다).
- [ ] `src/main/resources/application.yaml`
  - `route.search.deadline-ms` 기본값 `3000` → `4500`. 주석에 근거(OTP 0.9~2.0s + 보강 1.5s)와 되돌리는 법(env `ROUTE_SEARCH_DEADLINE_MS`) 기록.

### 3-2. 손대지 않는 것
- `TransferArrivalEnricher` 내부 로직, `TransferStopResolver`, ML 경로 — 전부 그대로.
- ODSAY 분기 — parity 유지.
- `transfer.predict` 단일 API — 보조 통로로 유지.

## 4. 테스트

- [ ] `RouteSearchServiceOtpWiringTest`(또는 `RouteSearchServiceTest` OTP 케이스)에 **계약 3개** 추가:
  1. `search()`(withRealtime=true) + OTP provider → `transferArrivalEnricher.enrich` 가 **호출된다**, 그 반환이 응답이 된다.
  2. `searchWithoutRealtime()` + OTP provider → enricher **호출 안 된다**.
  3. enricher 가 값을 못 채워도(그대로 반환) 검색은 200 이고 순서가 안 바뀐다.
- [ ] `RouteSearchDeadlineProperties` 기본값 가드: `deadlineMs == 4500`.
- [ ] 회귀: ODSAY 분기 기존 테스트(`TC_ODSAY_롤백_경로는_폴리라인_없이_도보_환승_보강만_거친다`) green 유지.
- [ ] 검증 명령: `./gradlew compileJava compileTestJava` → `./gradlew test --tests "*RouteSearch*" --tests "*TransferArrival*"` → 전체 `./gradlew test`.
- [ ] 기존 무관 실패 1건 예상: `JourneyTransferAssessmentIT.assessmentIsPersisted`(baseline 확인됨).

## 5. 배포·실측

- [ ] 마이그레이션 없음.
- [ ] 배포 절차 = 이 트리에서 `./gradlew bootJar` → VPS `app.jar` 백업 → scp → `DEPLOY_REVISION=<sha> ./deploy.sh all`.
- [ ] 배포 후 실측 3종을 노트 `### 검증` 에 붙인다:
  1. 잠실역 8호선 → 청계천(`paths[2]` 에 303번 버스 구간) 응답에서 `transferArrivals` **non-null**, `status`/`waitSeconds` 확인.
  2. 검색 지연 before/after (현재 0.88s). `meta.degraded` 에 `bus_realtime` 이 상시로 올라오면 예산 재조정.
  3. `http_client_requests_seconds_count{client_name="ws.bus.go.kr",role="api"}` 증가량 — 검색 1건당 외부 호출 수가 상한(12) 안인지.

## 6. 문서·메모리 정리

- [ ] `docs/plan/transfer-predict-mobile/HANDOFF.md` **삭제**(폐기). 사유는 노트에 남긴다. `/api/transfer/predict` 엔드포인트 자체는 코드에 남는다 — 여정 평가기와 재탐색이 같은 `PredictTransferUseCase` 를 쓰므로 지우면 안 된다.
- [ ] 메모리 `feedback-server-static-search-front-realtime` 를 **삭제하지 말고** "취소된 계약 + 취소 사유" 로 고친다(왜 한 번 그렇게 갔었는지가 다음 판단의 재료다).
- [ ] `implementation_note.md` 에 `### 확정`(계약 뒤집힘) / `### 검증`(실측) 기록.

## 7. 범위 밖

- 재정렬·강등·MISSED 숨김 — 여전히 금지.
- 지하철 예측 — 모델 없음.
- 여정(journey) 진행 중 대기 단계 ETA — 다른 경로(`project-journey-boarding-wait-null`)에서 추적 중.
