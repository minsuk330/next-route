# H 단독 OTP 기여 — 에이전트 실행 계획

## A/H 역할 정의 (소스 검증 기준, 단일 기준)

이 문서와 모든 H-only 실험에서 A/H는 다음처럼 고정한다. 디렉터리명이나 예전 JAR
파일명으로 역할을 다시 추론하지 않는다.

- **A = upstream 원본(baseline)**: `TripPatternForDates`가
  `arrivalTimes[]`와 `departureTimes[]`를 `nStops * numberOfTripSchedules` 크기로
  만들고, 요청 생성 때 모든 stop×trip 시간값을 배열에 복사한다.
- **H = 이 기여의 최적화 코드(H-only)**: 위 두 대형 배열을 제거하고
  `TripTimes[] tripTimesByIndex`와 `int[] offsetsByTrip` 참조를 보관한다. 조회 시 원본
  `TripTimes` 값에 해당 날짜 offset을 더한다.

소스 확인 결과 `/private/tmp/otp-baden-build-LgbuLS/A`는 A 원본(평탄 배열),
`.../H`는 H 최적화(참조/offset)다. 따라서 기존 `otp-baseline.jar`와
`otp-h-only.jar`라는 임시 파일명은 역할명이 아니며, 아래 SHA와 바이트코드 역할만
신뢰한다. 이 정의는 기존에 반대로 기록된 Baden 결과표를 교정하는 기준이다.

단일 기준 문서는 [H-ONLY-A-H-ROLE-DEFINITION.md](H-ONLY-A-H-ROLE-DEFINITION.md)다.

> 2026-09-15 후속 사용자 지시: 메모리·성능 성과 검증은
> [H-ONLY-SHARED-GRAPH-AGENT-PLAN.md](H-ONLY-SHARED-GRAPH-AGENT-PLAN.md)를 우선한다.
> A/H는 반드시 같은 graph를 사용한다. 공유 graph 정확성·반복 성능·JFR 검증까지
> 완료했으며, 결과는 `ALLOCATION_REDUCTION_ONLY`다. RSS와 latency 개선은 주장하지 않는다.

작성: 2026-09-15. 상태: Phase 0–3 로컬 검증 및 공유 graph S0–S6 완료 /
성능 결론: allocation 감소 지지, RSS·latency 개선 없음.

현재 H-only 실행 상태(2026-09-15): 공식 upstream `dev-2.x` SHA
`a0b62f69fc543e84687752e17dae8a66b01c5e28`에서 `/private/tmp/otp-h-only-run-v83UVX` worktree와
`codex/otp-h-trip-times` 브랜치를 분리했다. H 운영 코드와 H 전용 회귀 테스트만 변경했고,
대상·영향 모듈 테스트, Spotless/Checkstyle, shaded JAR 빌드를 통과했다. 상세 명령·SHA·
snapshot 근거는 `H-ONLY-MANIFEST.json`과 `H-ONLY-RESULT.md`에 있다. 공식 Helsinki 입력으로
호스트 `-Xmx8g` graph build와 A/H SpeedTest local equivalent를 완료했지만, Maven exec plugin
부재로 직접 Java main을 사용했고 두 변형의 별도 graph가 15 edge 차이를 보여 성능은
`INCONCLUSIVE`다. Docker는 사용하지 않았고, Docker 실행이 필요해지면 사용자에게 사전
보고한다.

## 1. 목적과 실행 계약

사용자는 H 변경만 분리하여 OpenTripPlanner upstream에 제안하려 한다. H는 `TripPatternForDates`에서 요청마다 만드는 두 개의 `stops × trips` 시간 배열을 `TripTimes` 참조와 trip별 날짜 offset으로 대체하는 변경이다.

이 문서가 **H 단독 기여 작업의 우선 실행 지침**이다. 기존 A/HF P4 및 축소 25% c4 실험은 역사 자료로 유지하며, 이 작업의 선행 조건으로 자동 재개하지 않는다.

최종 산출물은 최신 upstream 기반 H 전용 브랜치, 의미 있는 회귀 테스트, 동일 코드의 검증 기록, 영문 Issue/PR 초안이다. 문서 작성 요청 자체는 GitHub 게시 요청이 아니다. 후속 실행 지시가 준비 작업만 포함하면 로컬 초안까지 완료하고, 게시를 요청받으면 해당 범위에서 게시한다. 운영 배포·머지는 별도 작업이다.

### 작업 위치

- 구현 저장소: `/Users/wa/golbob/개인/OpenTripPlanner`.
- 문서 저장소: `/Users/wa/golbob/개인/nextroute/docs/plan/otp-code-optimization/handoffs/`.
- 확인된 보존 브랜치: `codex/otp-phase0-3`.
- 확인된 혼합 커밋: `8dbad55ba4` (H+F, 실험 도구, 과거 테스트 포함).
- 과거 baseline: `02c0b4b537eb07bb0b284726397821682248fe20`.
- 신규 브랜치 권장명: `codex/otp-h-trip-times`.
- 임시 산출물: `mktemp -d`로 만든 작업별 디렉터리. 실제 경로를 manifest에 기록한다.

위 SHA/브랜치는 재개 시 재확인한다. 한글 경로의 Unicode 정규화 차이에 주의한다. 저장소별 AGENTS.md, CONTRIBUTING.md, ARCHITECTURE.md, PR template을 읽는다. 기존 서비스와 Docker Desktop을 메모리 확보 목적으로 종료하지 않는다.

## 2. Phase 0 — 상태 확인과 격리

1. 양쪽 저장소의 `git status --short`, branch, HEAD, remote를 기록한다.
2. 공식 `opentripplanner/OpenTripPlanner`의 최신 `dev-2.x`를 fetch하고 정확한 SHA를 고정한다. 사용자 fork의 오래된 `origin/dev-2.x`를 최신으로 간주하지 않는다.
3. 그 SHA에서 별도 worktree와 신규 브랜치를 만든다. 기존 혼합 브랜치를 보존한다. 이름이 이미 있으면 상태를 확인해 재개하거나 충돌 없는 이름을 사용한다.
4. A는 고정 upstream SHA, H는 그 SHA에 H만 적용한 코드로 정의한다.
5. 소스 SHA, patch hash, JDK/Maven 버전, OS/CPU/RAM을 manifest에 기록한다.

완료: A/H 기준과 작업 디렉터리가 명확하며 기존 작업을 덮어쓰지 않았다.

## 3. Phase 1 — H 코드만 이식

### 허용 범위

운영 코드의 기본 변경 대상:

`application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/request/TripPatternForDates.java`

구현:

1. `arrivalTimes`, `departureTimes`의 `nStops * numberOfTripSchedules` 배열을 제거한다.
2. 전역 정렬 trip index 순서로 `TripTimes[] tripTimesByIndex`, `int[] offsetsByTrip`를 만든다.
3. `arrivalTime(stop, trip)`는 원본 arrival + 해당 trip offset을 반환한다.
4. `departureTime(stop, trip)`도 같은 방식으로 반환한다.
5. trip 정렬·접근성·boarding/alighting·frequency·검색 방향 동작을 유지한다.

현재 commit 전체를 cherry-pick하지 않는다. 파일 전체를 과거 버전으로 덮지 말고 최신 upstream에 의미상 변경만 적용한다.

### 제외 범위

- F: `DestinationArrivalPaths`, `PathConfig`, `McRangeRaptorConfig`.
- B/C: filtering 및 stop-pattern index 최적화.
- E2: short delta/fallback 최적화.
- `test/performance/seoul-metro/**` 도구의 upstream PR 포함.
- graph/calendar/bbox/검색시간창/후보 수/GC 정책 변경.

기존 `TripPatternForDateTest` 및 `RaptorRoutingRequestTransitDataCreatorTest` 추가분은 B/C 관련이다. 기존 `TripPatternForDatesTest`의 `fallback`, `promotion`, `departure delta` 설명은 E2 잔재다. H 테스트로 그대로 복사하지 않는다.

### 핵심 설계 검토

기존 구현은 요청 생성 시 시간값을 복사한다. H는 객체를 계속 참조하므로 **요청 중 참조한 TripTimes의 값이 바뀌지 않는가**를 확인해야 한다.

- 실제 `TripTimes` 구현과 realtime updater의 snapshot 생성/교체 흐름을 추적한다.
- immutable이라고 이름이나 과거 문서만 보고 판단하지 않는다.
- 기존 snapshot을 참조하는 요청과 새 snapshot을 읽는 요청의 격리를 설명한다.
- mutable 객체가 같은 요청에 노출된다면 진행을 멈추고 구체 경로와 대안을 보고한다. 모든 TripTimes를 복사하여 최적화 취지를 없애는 해결책을 자동 적용하지 않는다.

트레이드오프: 배열 복사·할당은 줄지만 시간 접근마다 참조 조회·getter·offset 덧셈이 발생하며 stop-major 배열의 locality를 잃을 수 있다. 속도 개선을 전제하지 않는다.

완료: 운영 diff가 H에 국한되고 snapshot 안전성 근거가 기록됐다.

## 4. Phase 2 — 회귀 테스트와 빌드

기본 테스트 위치는 `TripPatternForDatesTest.java`이며 필요하면 기존 관련 테스트를 확장한다. 사라진 내부 배열을 reflection으로 검사하는 테스트보다 외부에서 관측 가능한 시간/검색 결과를 검증한다.

| 조건 | 검증 |
| --- | --- |
| 여러 trip·여러 stop | 모든 arrival/departure가 기대값과 일치 |
| 서로 다른 service date | 각 날짜 offset 적용, 음수/양수 offset 포함 |
| 날짜가 겹치는 운행 | 전역 trip 정렬과 각 trip의 시간이 일치 |
| 자정 이후 시간 | 24시간 초과 시각을 손실 없이 유지 |
| 정차시간 존재 | arrival와 departure를 혼동하지 않음 |
| forward/reverse 검색 | 같은 탑승/하차 가능 trip 선택 |
| frequency | 기존 exact/non-exact 지원 계약 유지 |
| 접근성 | trip 순서와 wheelchair 정보 정렬 유지 |
| realtime snapshot | 이전 요청 값 유지, 새 요청에는 갱신값 반영 |

기존 테스트가 이미 조건을 검증하면 해당 테스트를 실행하고 근거를 남긴다. 프로젝트의 실제 fixture/API를 읽은 뒤 구현한다. 비현실적인 운행 순서를 가진 fixture를 새로 만들지 않는다.

검증 순서:

1. 대상 테스트와 직접 관련 테스트.
2. 영향 모듈 및 프로젝트 필수 검사, formatter/checkstyle.
3. 필요 범위의 전체 회귀 테스트와 shaded JAR 빌드.
4. `git diff --check`, 변경 파일 목록 검토.

Maven profile과 Java 버전은 최신 checkout 설정을 따른다. 예전의 8,225개 통과 기록을 새 코드의 실행 결과로 쓰지 않는다. 정확한 명령·성공/실패/skip·JAR SHA-256을 기록한다.

완료: 새 H 코드 검증 통과. 남은 실패가 upstream에서도 재현되면 별도 기록하며 H 성공으로 숨기지 않는다.

## 5. Phase 3 — Helsinki 공식 SpeedTest

기여 제안에 앞서 수행할 권장 검증이다. 환경상 실패해도 Issue/Draft 초안 작성을 차단하지 않으며 미검증 사항을 명시한다.

### 데이터와 메모리

공식 입력: https://otp-performance.leonard.io/data/helsinki/

2026-09-15 조회된 파일:

- `hsl-2026-02-19.osm.pbf`: 약 90MiB.
- `hsl-gtfs-2026-02-19.zip`: 약 52MiB.

최신 checkout의 `test/performance/helsinki/build-config.json` source 및 `speed-test-config.json`과 일치하는 파일을 받는다. config가 원격 source를 지정하면 로컬 파일을 추가하는 것만으로 그 파일이 사용되는 것은 아니다. 필요시 별도 실험 디렉터리에서 source 경로만 변경하고 diff/hash를 보관한다. 날짜·기간·routing 설정은 공식 조건을 유지한다.

공식 대시보드 당시 Helsinki GC 후 heap은 약 1.59GiB였다. **peak RSS나 빌드 필요량은 미측정이며 로컬 성공을 보장하지 않는다.** 앞선 답변의 “충분히 가능/안전”은 추정으로 낮춘다. `memory_pressure -Q`의 free percentage도 JVM에 바로 할당 가능한 RAM 양으로 해석하지 않는다.

로컬 RAM 18GiB, 디스크 여유 당시 약 28GiB. 우선 Xmx6g로 한 번 feasibility를 확인한다. 부족하면 사용량과 원인을 확인한 뒤 동일 A/H 조건에서 8g를 검토한다. 공식 CI의 Xmx50g를 복제하지 않는다. Xms는 대형 고정값으로 설정하지 않는다. Norway/Germany 다운로드·빌드를 자동 진행하지 않는다.

이번 실행에서는 사용자가 `-Xmx8g`를 명시하여 Xmx6g 단계를 생략하고 A/H 모두 동일한
호스트 Java 26.0.2.1 플래그로 진행했다. 결과와 자원 제약은 `H-ONLY-RESULT.md`에
기록했으며, Docker는 시작하지 않았다.

### 빌드 및 실행

1. A/H 각각 같은 JDK·Maven으로 빌드한다. 공식 README와 CI 명령을 재확인한다.
2. 고정 원천 데이터로 graph를 빌드한다. serialization 호환성을 확인하면 동일 graph를 A/H에서 재사용할 수 있다. 확인하지 못하면 같은 입력/config로 각각 빌드한다.
3. testDate가 실제 service 기간 안인지 확인한다.
4. 공식 실행 형태:

```bash
mvn --projects application exec:java \
  -Dexec.mainClass="org.opentripplanner.transit.speed_test.SpeedTest" \
  -Dexec.classpathScope=test \
  -Dexec.args="--dir=<실험 디렉터리> -p md -n 4 -i 3 -0"
```

위 디렉터리는 실제 경로로 교체한다. `mvn install -DskipTests` 등으로 reactor 의존성과 test class를 준비하되 서로 다른 A/H 의존성이 섞이지 않도록 각 run 전에 해당 변형 install을 완료하거나 격리된 Maven 저장소를 사용한다. 무조건 중복 전체 빌드하지 말고 artifact 정체성을 검증한다.

- `-n 4`: 전체 질의 세트 반복 수. 동시성 4가 아니다.
- `-i 3`: 반환 itinerary 수.
- `-0`: 기대 결과 비교에서 cost 제외. H는 cost도 유지해야 하므로 A/H 결과 CSV의 cost를 추가 비교한다.
- `-R`: expected 결과 덮어쓰기이므로 사용하지 않는다.
- 공식 도구는 워밍업/강제 GC를 포함한다. 이 코드를 수정하지 않는다.

### 실행 단계

1. A 1회 feasibility: 빌드/로드/전체 케이스 실행 여부와 RAM·swap 확인.
2. A/H 정확성: 공식 expected 및 A/H 결과 차이 확인. 종료코드만 믿지 말고 FAILED/UNEXPECTED RESULTS와 성공 건수를 검사한다.
3. 성능: feasibility 통과 후 독립 JVM으로 AB, BA, AB 세 쌍을 초기 표본으로 실행한다. 각 run `-n 4`, 같은 flags와 graph 조건. 차이가 애매하면 INCONCLUSIVE로 기록하고 제안 단계로 진행한다. 무기한 반복하지 않는다.
4. JFR: 동일 설정의 A/H 별도 진단 run 한 쌍으로 allocation stack·GC 확인. 프로파일링 run의 지연을 비계측 run과 섞지 않는다.

수집: case별/전체 routing·RAPTOR 시간, 성공 수, 결과 CSV, 프로세스 peak RSS, GC 후 heap, GC 정지, JFR, host swap-in/out 전후 및 측정 구간, 실행 명령·SHA.

기존 swap 사용량만으로 실패라고 판단하지 않는다. 측정 중 swap I/O가 증가하면 성능을 환경 영향이 있는 탐색 결과로 표시한다. 이 경우에도 정확성 검증 결과는 별도 유지한다. OOM·collector 누락·다른 서비스 영향이면 반복 실행을 중단하고 준비된 초안과 한계를 보고한다.

완료: 공식 데이터에 대한 검증 결과 또는 구체적인 환경 제약이 기록됐다. 로컬 SpeedTest 결과를 공식 CI 실행 결과라고 부르지 않는다.

## 6. Phase 4 — 기존 서울 근거 정리

읽을 자료:

- [ABLATION-RESULT.md](ABLATION-RESULT.md)
- [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)
- [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md)
- [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)
- [PR-EVIDENCE.md](PR-EVIDENCE.md)

과거 `H` 이름에 F가 들어간 산출물이 있으므로 source/JAR SHA로 실제 H-only 여부를 검증한다. VPS의 메모리 절반 결과와 reduced25 A/HF 결과는 H-only 성능 수치로 인용하지 않는다.

사용 가능한 주장:

- 대상 배열 payload 모델은 대략 기존 `8 × stops × trips` bytes에서 `trips × (reference 크기 + 4)`로 감소한다. header/alignment와 기타 구조는 별도다.
- 직접 계측한 특정 배치의 42.07GB→0.659GB는 대상 원소 payload 누적 모델이다. 전체 heap·RSS의 98% 감소가 아니다.
- JFR allocation weight는 샘플 기반 추정이며 정확한 전체 할당 바이트가 아니다.
- H-only 과거 지연 자료는 당시 환경과 SHA를 밝힌 탐색 근거다.

새 H 빌드로 서울 고정 corpus 정합성 확인을 수행한다. 기존 runner를 외부에서 활용할 수 있으나 최신 OTP API와 호환되는지 먼저 확인한다. 새 서울 c1/c2/c4 대규모 성능 matrix는 이 기여 준비의 필수 조건이 아니다. Helsinki 결과와 코드 안전성으로 먼저 제안 가능하다.

## 7. Phase 5 — 영문 Issue와 Draft PR 준비

산출 파일:

- `H-ONLY-RESULT.md`: 구현 범위·snapshot 근거·테스트·측정·제약.
- `H-ONLY-MANIFEST.json`: A/H SHA, JAR/data/config/graph hash, 환경, run 경로.
- `H-ONLY-ISSUE-DRAFT.md`: 설계 제안.
- `H-ONLY-PR-DRAFT.md`: 저장소 PR template을 따른 설명.

영문 제목 예시: `Reduce timetable allocation in TripPatternForDates`.

본문 순서:

1. 문제: 요청별 stop×trip 도착/출발 배열 생성 비용.
2. 변경: 정렬된 TripTimes 참조와 날짜 offset 저장, 접근 시 계산.
3. 의미 보존: 날짜·정렬·snapshot 계약과 테스트.
4. 비용 교환: 할당 감소와 접근 비용/locality 영향.
5. 증거: 현재 PR 코드의 결과와 과거 탐색 결과를 명확히 표시.
6. 질문: 설계 수용 가능성과 유지보수자의 공식 대형 데이터 SpeedTest 지원.

upstream의 현재 CONTRIBUTING·architecture 지침과 유사 issue/PR을 확인한다. 큰 변경 사전 논의가 권장되면 Issue 초안을 먼저 제시한다. Issue는 항상 필수라고 단정하지 않는다. 사용자 게시 지시가 있으면 범위에 따라 Issue 또는 Draft PR을 만들고 URL을 기록한다.

커밋 시 프로젝트 convention을 따른다. 한 가지 개념의 운영 변경과 회귀 테스트로 구성하며 측정 도구·대형 데이터·JFR은 PR 코드에 넣지 않는다. 재현에 필요한 작은 명령/설명은 본문에 포함한다. AI 기여 공개 규칙이 있으면 준수한다.

## 8. 종료 기준과 후속

### 제안 준비 완료

- [x] 최신 upstream 기반 H 브랜치, F/B/C/E2 변경 없음.
- [x] snapshot 참조 안전성 검토와 관련 테스트 완료.
- [x] 필수 빌드/검사 통과 및 새 SHA 기록.
- [x] Helsinki 결과 또는 실행 제약 명시.
- [x] H-only 증거만으로 주장 작성.
- [x] 영문 Issue/PR 초안 작성.
- [x] 진행 상태와 다음 작업을 NEXT-AGENT에 반영.

### 머지 검토 단계

유지보수자가 요구하는 데이터·SpeedTest·설계 수정을 반영한다. 공식 러너의 Norway/Germany 검증은 로컬 사전 제출 의무로 두지 않는다. 성능 회귀가 확인되면 평균 개선으로 감추지 않고 데이터/질의별 결과를 전달한다. 하이브리드 저장 방식 등의 대안은 별도 설계 변경으로 검토한다.

다음 에이전트의 최종 보고에는 브랜치/기준 SHA, 변경 파일, 테스트 결과, 성능 결론의 범위, 미완료 사유, 초안 경로를 포함한다. 실험을 실행하지 않았으면 실행했다고 적지 않는다.

## 9. 공식 참조

- https://github.com/opentripplanner/OpenTripPlanner/blob/dev-2.x/CONTRIBUTING.md
- https://github.com/opentripplanner/OpenTripPlanner/blob/dev-2.x/ARCHITECTURE.md
- https://github.com/opentripplanner/OpenTripPlanner/blob/dev-2.x/test/performance/README.md
- https://github.com/opentripplanner/OpenTripPlanner/blob/dev-2.x/.github/workflows/performance-test.yml
- https://otp-performance.leonard.io/data/helsinki/
- https://otp-performance.leonard.io/d/9sXJ43gVk/otp-performance

## 10. 다음 에이전트 시작 프롬프트

> H-ONLY-CONTRIBUTION-PLAN.md를 읽고 Phase 0부터 H 단독 기여 준비를 진행하라. 현재 상태를 먼저 확인하고 완료한 단계는 재실행하지 말라. 최신 upstream에서 H를 격리하고 snapshot 안전성·회귀 테스트·Helsinki feasibility와 검증 결과·영문 Issue/PR 초안을 완성하라. 기존 A/HF matrix를 자동 재개하지 말라. 성능 개선을 전제하지 말고 한계를 명시하라. 사용자 서비스는 유지하고 실험 산출물의 SHA와 실행 기록을 남겨라.
