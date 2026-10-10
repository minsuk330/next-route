# 지하철 탑승 증거(boarding-evidence)·자동 판정기 제거 — PLAN

티어 M. 브랜치 `chore/remove-subway-boarding-evidence` (main `4a4a43dd` 기준). 출처: 앱 nextroute-mobile#15 "서버 boarding-evidence 자동 판정 코드 정리".

## 배경 (확인된 사실)
- 앱은 모션 기반 자동 탑승을 제거했다(앱 #13). 앱 `origin/main` 에서 `boarding-evidence`·`binding/reset`·`not-boarded` 호출 0건. 앱이 쓰는 지하철 바인딩 경로는 `train-selection`·`train-candidates`·`trackability` 뿐(`src/features/journey/journey-api.ts:44-58`).
- 자동 판정기(`SubwayBindingMatchService`, worker 2초 tick)는 `findMatchable` = `status IN (PENDING, PROVISIONAL) AND last_movement_seq IS NOT NULL` 만 본다(`SubwayLegBindingRepository.java:64`). `last_movement_seq` 는 evidence 서비스만 채우므로 지금 판정기는 입력 0 — 죽은 코드.
- VPS compose `JOURNEY_SUBWAY_BINDING_ENABLED: "true"`(line 153) → 스케줄러 빈은 떠서 빈 스캔만 한다.

## 결정
1. **API 3개 전부 삭제**: `POST /{journeyId}/subway/boarding-evidence`·`/binding/reset`·`/binding/not-boarded`(`JourneySubwayBindingController`). 셋 다 `SubwayBindingUseCase` 하나를 공유하고 앱 호출 0. 열차 바꾸기는 `train-selection` 재호출이 대체.
2. **판정기 일체 삭제**: MatchService·MatchTxService·TrainMatcher·Scheduler·Decision DTO·판정 지표.
3. **DB 무변경**: 컬럼(`boarded_detected_at`·`last_movement_seq`·`last_episode_seq`·`source`·`confidence`)·enum 값(`MOTION_*`·`PROVISIONAL`)·`subway_binding_event` 행 유지. 기존 행 읽기 호환, 마이그레이션 0. `boardedDetectedAt` 은 선택 흐름(`ChosenTrain.aboard` → `markBoardedDetected`)이 계속 쓴다.
4. **남기는 공유 부품**: `SubwayBindingLifecycleService`(재탐색 상속·여정 종료 정리), `SubwayBindingLockGuard`, `SubwayBindingEventRepository`, 링·선택 흐름 전부. `PROVISIONAL` 처리 분기도 기존 행 때문에 유지.
5. **설정**: `JourneySubwayBindingProperties` 는 `lockTimeout`·`algorithmVersion` 만 남긴다(LockGuard:49, Lifecycle:367). `enabled`·`tickMs`·`batchSize`·관문 값 제거. VPS env `JOURNEY_SUBWAY_BINDING_ENABLED` 는 무해한 잔여 — 배포 시 compose 에서 지워도 되고 안 지워도 된다(별건).

## 작업 순서
- [ ] 1. 삭제(main)
  - `infrastructure/adapter/in/api/journey/JourneySubwayBindingController.java`
  - `infrastructure/adapter/in/api/journey/dto/SubwayBoardingEvidenceRequest.java`
  - `application/journey/port/in/SubwayBindingUseCase.java`
  - `application/journey/service/SubwayBoardingEvidenceService.java`
  - `application/journey/dto/SubwayBoardingEvidence.java`
  - `application/journey/service/SubwayBindingMatchService.java`
  - `application/journey/service/SubwayBindingMatchTxService.java`
  - `application/journey/service/SubwayTrainMatcher.java`
  - `infrastructure/adapter/in/scheduler/journey/SubwayBindingMatchScheduler.java`
  - `application/journey/dto/SubwayBindingDecision.java` (판정기 전용이면. grep 으로 확인)
  - `SubwayBindingView` 등 위 컨트롤러 전용 DTO — grep 으로 남은 사용처 0 이면 삭제
- [ ] 2. 축소
  - `SubwayLegBindingRepository`: `findMatchable`·`findForMatch`·`casBind`·`casAbandon`·`abandonStaleWithoutMovement` 중 남은 사용처 0 인 것 삭제(grep). `findForUpdate`·`findRingTargets`·`abandonOpen*`·`findBoundOfRevision` 유지
  - `SubwayLegBinding`: `advanceMovementSeq`·`advanceEpisodeSeq`·`resetByUser`·`pending()` 등 사용처 0 이 된 메서드 삭제. 필드·컬럼 매핑은 유지
  - `SubwayBindingMetrics`: 판정기 전용 지표(DECISIONS·APPLIED·SCANNED·FAILURES) 제거, LIFECYCLE 유지
  - `JourneySubwayBindingProperties`: `lockTimeout`·`algorithmVersion` 만
  - `application.yaml:611` `journey.subway.binding` 블록: `lock-timeout`·`algorithm-version` 만 남기고 주석 갱신. ring 블록 주석 "binding 이 먼저 켜져 있어야" → "열차 선택(selection)이 먼저"로 수정
  - `SubwayBindingLifecycleService:58` 주석의 `binding.enabled` 언급 갱신
- [ ] 3. 테스트
  - 삭제: `SubwayBindingMatchServiceTest`·`SubwayBindingMatchTxServiceTest`·`SubwayBoardingEvidenceServiceTest`·`SubwayTrainMatcherTest`·`SubwayBindingMatchIT`
  - `SubwayBindingConcurrencyIT`(4): 판정기·evidence 경합 케이스 삭제. 선택 경로 경합만 남으면 유지, 없으면 파일 삭제
  - `SubwayTrainSelectionIT`(13): `findMatchable`·`reset`·`sweepStale` 의존 케이스 삭제/수정, 선택 계약 케이스 유지
  - `SubwayBindingRerouteInheritanceIT`(5): `findMatchable` 단언(:160) 제거 또는 상태 단언으로 대체
  - `SubwayRideScenarioIT`(11): 판정기 선택 시나리오(앞차 A/B 확정, "이 열차 아니에요", 후보 1대, 응답 중복, 위치 수집 꺼짐) 삭제. 링·위치 폴백·실시간 끊김·종료 프레임 시나리오는 셋업을 사용자 선택(`SubwayLegBinding.selected`/선택 서비스)으로 바꿔 유지
  - `SubwayLegBindingSelectionTest`: `MOTION_AUTOMOTIVE` 셋업이 남은 생성자에 의존하면 맞춰 수정
- [ ] 4. 검증
  - `./gradlew compileJava compileTestJava`
  - `./gradlew test --tests '*Subway*' --tests '*JourneySubwayGuidance*'`
  - 전체 `./gradlew test --continue` — 기존 무관 12건(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·JourneyNotificationSchemaIT 1 flaky) 외 신규 실패 0
  - `git grep -nE 'boarding-evidence|SubwayBindingMatch|SubwayTrainMatcher|SubwayBoardingEvidence' -- src` → 0
- [ ] 5. 노트·PR: `implementation_note.md` append, PR 에 이 PLAN/SUMMARY 포함

## 스크래치 IT 환경
일회용 `rp-pg`(:55434, myuser/rp, mydatabase)·`rp-redis`(:56381). env: `POSTGRES_HOST=localhost POSTGRES_PORT=55434 POSTGRES_DB=mydatabase POSTGRES_USER=myuser POSTGRES_PASSWORD=rp REDIS_HOST=localhost REDIS_PORT=56381 GONG_API=x KAKAO_REST_API_KEY=x SEOUL_BUS_USAGE=x SEOUL_SUBWAY_MASTER=x SEOUL_SUBWAY_ROUTE=x`.

## 배포
마이그레이션 없음. api(컨트롤러 제거)+worker(스케줄러 제거) 교체. 앱 호출 0 이라 순서 무관.

## 실행 중 정정 (2026-10-10)
- `JourneySubwayBindingController` 는 **삭제하지 않는다** — `train-candidates`·`trackability`·`train-selection`(앱이 쓰는 3개)도 같은 컨트롤러에 있었다. evidence 3개 메서드와 `SubwayBindingCorrectionRequest` 만 지웠다
- `SubwayBindingLifecycleService.reissueEvidence`(잠정 계승 시 근거 감사행 재발행)도 판정기 전용이라 삭제. 잠정 행 자체 계승은 유지
- `SubwayBindingConcurrencyIT` 는 판정기 경합 2건 삭제, 잠금 대기 1건을 선택 경로(`SubwayTrainSelectionTxService.bind`)로 바꿔 유지
