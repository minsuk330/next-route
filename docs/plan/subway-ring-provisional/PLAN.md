# 잠정(PROVISIONAL) 고정에도 진행 링·잠금화면을 그린다 (M)

## Context
시나리오 IT(#242)에서 드러난 문제: 판정기는 **후보가 1대뿐이면 확정하지 않고**(`SubwayTrainMatcher.confirmStage` `comparable.size() < 2`), 링은 **확정(CONFIRMED)에만** 그려진다. 그래서 배차가 뜸한 시간대에는 잠정 → 8분 뒤 버려짐 → **링이 한 번도 안 뜬다.**

사용자 결정(2026-09-29):
1. **C** — 잠정 고정에도 링을 그린다. 확정 규칙 자체는 그대로.
2. **"추정/확실하지 않음"은 보여주지 않는다.** 서버 API 는 `bindingStatus=PROVISIONAL` 을 그대로 내리고, 앱이 CONFIRMED 와 **구분 없이** 그린다(모바일 HANDOFF). 잠금화면 payload 는 원래 상태 필드가 없어 변경 불필요.
3. **원래 설계 C6(미확정이면 하차 안내 금지) 폐기** — 잠정에서도 하차 단계(`SUBWAY_ALIGHT`)·push 전부 동일.
4. **교정되면 새 열차 기준으로 즉시 다시 그린다**(링 한 번 튐 허용, C4 완화).

알고 가는 위험(사용자에게 공유함): 급행 탑승(급행은 후보 제외)·다른 지선 열차(행선지 미검사)·출발 기록 누락·폰 이동 오감지·만원으로 못 탐 → 앞차/뒷차 링이 그대로 보이고 하차 안내가 한 정거장 일찍/늦게 뜰 수 있다. 벗어나는 길은 "이 열차 아니에요"뿐.

## 변경

### 운영 코드 (5곳)
| 파일 | 지금 | 바꾼 뒤 |
|---|---|---|
| `domain/journey/repository/SubwayLegBindingRepository.java:82` `findRingTargets` | `WHERE b.status = 'CONFIRMED'` | `IN ('PROVISIONAL','CONFIRMED')` + javadoc(:67~) 결정·날짜로 교체 |
| `application/journey/service/JourneySubwayGuidanceComposer.java:69` | CONFIRMED 아니면 `pending()` | `status.isBound()` 이면 링 조회, 아니면 `pending()`. 클래스 javadoc "확정 전에는 링을 만들지 않는다" 절 교체 |
| `application/journey/service/SubwayRingPushRelayTxService.java:93` 발송 직전 재확인 | `!= CONFIRMED` 이면 건너뜀 | `!isBound()` 이면 건너뜀. `binding_version` 대조는 그대로 → 교정되면 옛 행은 구형으로 스킵 |
| `application/journey/service/SubwayTrainMatcher.java:221~223` | 잠정 후 확정 창(8분) 넘으면 `abandon("confirm-window-expired")` | **분기 삭제** — 잠정을 버리면 운행 중 링이 꺼진다. 잠정은 확정·교정·사용자 reset·여정/개정본 종료(`SubwayBindingLifecycleService`)로만 끝난다. 늦게 온 정차 관측으로 이후 역에서 확정될 기회는 계속 열어 둔다 |
| `domain/journey/entity/SubwayBindingStatus.java:36` | `allowsAlightGuidance()` (CONFIRMED, **미사용**) | 삭제하고 `isBound()`(= PROVISIONAL·CONFIRMED, "열차가 정해져 있어 링을 그린다") 추가. C6 폐기와 어긋나는 헬퍼를 남기지 않는다 |

설정 정리: `confirm-window` 가 쓰이지 않게 되므로 `application.yaml:563`·`JourneySubwayBindingProperties.confirmWindow`(:54) 제거.

부수: `SubwayRingPushPayloadFactory:36`·`SubwayRingPushEnqueueService:163` javadoc 의 "CONFIRMED 만" 문구를 "열차가 정해진 고정(잠정·확정)"으로 정정. `SubwayRingCalculator.phaseOf` 는 **안 바꾼다**(C6 폐기 = 지금 로직 그대로 하차 단계 전환).

관측: `SubwayRingMetrics.recordUpdated` 에 `binding=provisional|confirmed` 태그 추가 — 잠정 링이 얼마나 그려지는지, 나중에 오판율 분석 재료(사용자 결정 2 "서버에는 사실을 남긴다"의 연장).

### 시험 (계약 하나당 최소 1개)
- `SubwayRingIT`: "미확정 고정은 대상이 아니다"(:155) → **잠정은 훑고 PENDING 은 안 훑는다**로 분리. "미확정 고정에는 링을 만들지 않는다"(:238) → PENDING 한정. 신규 "잠정 고정에도 링을 만든다"
- `SubwayTrainMatcherTest`: "확정 창을 넘기면 접는다"(:245) → **오래된 잠정도 접지 않는다**. :232 "확정 창까지는 산다" 문구·기대 정리, setUp :54 `setConfirmWindow` 제거
- `SubwayRingPushIT`: 신규 "잠정 고정도 발송한다", 기존 "고정이 접히면 발송하지 않는다" 유지, 신규 "교정되면 옛 개정 세대 행은 구형으로 건너뛴다"(기존 세대 테스트가 덮으면 생략)
- `SubwayRideScenarioIT`(#242, 아직 main 에 없음): 시나리오 4 뒤집기는 **이 PR 범위 밖** — 아래 "#242 와의 순서" 참조

### 문서
- `docs/plan/subway-ring-provisional/{PLAN,SUMMARY}.md` (`/plan-split`)
- `docs/plan/subway-ring-provisional/HANDOFF.md` — 모바일: `bindingStatus=PROVISIONAL` 이어도 CONFIRMED 와 **똑같이** 그린다, "확인 중/추정" 문구 금지, 교정 시 진행률·다음 역이 한 번 튈 수 있다, `ABANDONED`·`PENDING` 은 지금처럼 링 없음, 잠금화면 payload 변경 없음
- `docs/plan/m7-subway-train-binding/PLAN.md` C4·C6 행 옆에 "2026-09-29 폐기/완화 → subway-ring-provisional" 한 줄 포인터

## 작업 순서
- [ ] 0. **격리**: 진행 중인 다른 작업(#242, 공유 작업트리의 다른 세션)에 영향이 없도록 스크래치 디렉터리에 `git clone`(git worktree 아님) → `origin/main` 기준 새 브랜치 `feat/subway-ring-provisional`. 공유 작업트리의 파일·브랜치는 건드리지 않는다. #242 도 머지하지 않는다
- [ ] 1. `/plan-split subway-ring-provisional` → PLAN/SUMMARY/HANDOFF (클론 안에)
- [ ] 2. 시험 먼저 뒤집기/추가 → 실패 확인
- [ ] 3. 운영 코드 5곳 + 설정 정리 + 메트릭 태그
- [ ] 4. 검증 → 노트 `### 검증`(공유 작업트리의 `implementation_note.md` 에 append — 로컬 노트 관례 유지) → 클론에서 커밋·push → PR
- [ ] 5. 배포는 머지 후 사용자 확인을 받고 진행(마이그레이션 없음, jar 교체만)

### #242 와의 순서 (스택 금지)
- 이 브랜치는 main 기준이라 `SubwayRideScenarioIT` 가 없다 → **시나리오 4 뒤집기는 이 PR 에 넣지 않는다.**
- 대신 PR 본문에 명시: **#242 의 시나리오 4("한산한 시간대 링 안 뜸")는 이 PR 과 모순된다.** 어느 쪽이 먼저 머지되든 나중 PR 에서 시나리오 4 를 "링이 뜨고 8분 뒤에도 유지"로 뒤집는 커밋을 추가한다(나중 PR 을 main 에 맞춰 갱신할 때).
- 이 PR 자체의 계약은 `SubwayRingIT`·`SubwayRingPushIT`·`SubwayTrainMatcherTest` 로 덮는다.

## 경계 리뷰 포인트 (사람이 볼 것)
- **상태 전이**: PROVISIONAL → ABANDONED(확정 창 만료) 경로가 사라진다. 남는 끝: 확정·교정(CONFIRMED)·사용자 reset·여정/개정본 종료
- **계약**: `findRingTargets` 대상 확장, 발송 재확인 조건 완화, `subwayGuidance` 에 잠정 링이 실림
- **플래그**: 새 플래그 없음. 기존 `journey.subway.ring.*` 가 켜진 프로덕션에 머지·배포하면 **즉시 적용**된다

## 검증
- `./gradlew test --tests '*SubwayRing*' --tests '*SubwayTrainMatcher*' --tests '*SubwayRideScenarioIT'` → XML 수치 확인
- `./gradlew test --tests '*Subway*'` 무회귀, 전체 `./gradlew test` (기존 실패 `JourneyTransferAssessmentIT` 는 "기존 무관")
- 전제: 로컬 Postgres 기동, Redis 는 Testcontainers
