# 넉넉한 heap에서 A/H/F/HF 비교 — 에이전트 실행 계획

> **2026-09-15 사용자 결정 — 데이터 25% 축소 실험 진행 중.** 아래 §P4-R이 우선 계약이다. 기존 P0–P3는 완료 이력이고, 수도권 전체 P4는 보류한다. 축소 feed·graph와 R1 정합성, R2 c1 pair-01~05 및 c1 bootstrap, c2 동일 HF SHA 5 pair 및 bootstrap까지 실행됐다. c2는 peak RSS median +18.24% 탐색 신호를 보였지만 p50/p95는 INCONCLUSIVE이고, 현재 Mac의 swap-in delta가 0이 아니어서 `REDUCED_PRESSURE_EXPLORATORY`로만 기록한다. c4·전체 R3 판정과 zero-swap 재실행은 아직 실행하지 않았다.

작성: 2026-09-14. 갱신: 2026-09-15. 상태: **P0 완료, P1 정확성 gate 통과(전용 F 단위 테스트는 미확보), P2 16셀 탐색 완료, P3 JFR·직접 카운터 분석 완료, R0/R1 축소 실험 통과, R2 c1 5 pair·bootstrap 완료, c2 pair-04까지 부분 완료, c4 대기, formal no-swap 성능 gate 미통과**. P2/P3와 feasibility pilots, 현재 R2 c1 다섯 pair와 c2 유효 pair 네 개는 formal performance evidence가 아니다. block 3 F는 host swap I/O가 크게 증가해 비교에서 제외한다. block 4 HF 첫 실행은 sampler 공백 986초로 RSS/GC 무효였고, fresh JVM 재실행으로 대체했다. block 4 A는 host swap-in 약 29MiB, block 4 F/HF/H도 swap-in 관측이 있어 모두 P4 증거가 아니다. P4 A feasibility runs의 swap-in은 +7,675 pages(약 119.9MiB), +250 pages(약 3.91MiB), +88 pages(약 1.38MiB)로 세 번 모두 부적격이다. 두 번째 A run이 4-OD reference를 만들었고 세 번째 A run과 각 OD 100건 대조에서도 fingerprint mismatch는 0이었다. 별도 full-detail query에서도 A/HF 네 OD 각 1회 mismatch 0이지만 정식 P4 성능 비교는 미실행이다. 03:33:46–03:36:32 KST에 host swap counter가 잠시 그대로였고 memory pressure free 78%를 보였지만, 이어진 세 번째 A run에서 swap-in이 다시 발생했다. 반복 A의 OD별 p50은 약 12% 흔들리고 F-only는 pressure-contaminated 셀 하나를 제외한 비교에서도 느린 방향이다. direct counter는 두 OD의 탐색량·배열 payload 산식을 좁혔지만 H 접근당 비용과 F 회귀 원인은 미확정이다. 최신 축소 실행 수치는 [REDUCED-25-RUN-LEDGER.md](REDUCED-25-RUN-LEDGER.md), [REDUCED-25-RESULT.md](REDUCED-25-RESULT.md), [REDUCED-25-MANIFEST.json](REDUCED-25-MANIFEST.json)을 참조한다.

## 1. 목표

동일한 수도권 검색에서 원본 A와 변경안 HF의 메모리·지연 차이를 측정하고, H(시각표 배열 제거)와 F(dummy Path 재사용)의 기여를 분리한다. 개선·회귀·차이 불명확 모두 유효한 결론이다. 기존보다 항상 빠르다는 결론을 전제하지 않는다.

최종 답할 질문:

1. 충분한 heap에서 A 대비 HF의 RSS, 요청당 할당량, p50/p95, GC 정지는 얼마나 다른가?
2. H와 F 각각 무엇을 줄이고 어떤 비용을 추가하는가? 둘을 함께 적용할 때 상호작용이 있는가?
3. 시청→강남 c1 회귀가 반복되는가? 반복된다면 어느 변경과 관련되는가?
4. PR에서 확정적으로 주장할 수 있는 범위와 미확정 범위는 무엇인가?

## 2. 먼저 읽을 자료와 현재 증거

- [LARGE-HEAP-FOLLOWUP.md](LARGE-HEAP-FOLLOWUP.md) §0.5: 10GB c1/c2/c4, 5-pair 전체 비교 완료. host swap-in 영향으로 탐색 결과다.
- [ALLOCATION-LOOKUP-PROFILE.md](ALLOCATION-LOOKUP-PROFILE.md): 두 OD A/HF JFR 분석 완료. allocation 감소 관측, 원인 가설은 미검증.
- [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md): 새 A/H/F/HF 두 OD JFR 분리 분석 및 F inlining 진단.
- [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md): 두 OD A/H/F/HF 직접 카운터 8-run 및 payload 산식.
- [ABLATION-RESULT.md](ABLATION-RESULT.md): 이번 A/H/F/HF 10GB ablation의 최신 종합 판정.
- [VPS-OBSERVATION-RESULT.md](VPS-OBSERVATION-RESULT.md): 운영 용량 관측 완료. 재실행하지 않는다.
- OTP 저장소와 작업 경로의 `AGENTS.md`가 있으면 먼저 읽는다.

10GB 탐색 결과는 c1 p50 우위 미확정, c1 p95 HF 열세 방향, c2/c4 p50 개선, 모든 동시성 RSS 감소였다. JFR 실행에서는 시청→강남 지연 방향이 바뀌었다. JFR 지연값을 일반 성능 비교에 합치지 않는다.

**가설 갱신:** F의 receiver-type 변경이 관측한 comparator getter의 JIT 인라이닝을 깨뜨린다는 단순 가설은 JFR에서 지지되지 않았다. `MutableDummyPath`와 `Path` accessor가 각각 성공적으로 인라인된 사례가 있다. comparator 호출량·후보 집합 비용 및 H의 accessor 총비용은 직접 계측 전까지 미검증이다.

## 3. 작업 경계와 재개 규칙

- 코드: Nextroute의 형제 저장소 `../OpenTripPlanner`. 기록된 branch는 `codex/otp-phase0-3`; 실제 branch·diff를 재확인한다.
- 문서: 이 디렉터리. 원본 checkout의 미커밋 변경과 기존 JAR를 보존하고, 빌드는 `mktemp -d`로 만든 별도 디렉터리에서 수행한다.
- Docker Desktop은 계속 유지한다. 실행기가 만든 정확한 임시 OTP 컨테이너만 정리한다. 다른 앱·DB·Redis·운영 서비스는 종료하지 않는다.
- 동시에 OTP 측정 JVM 한 개만 실행한다. 빌드·다른 부하 실험·JFR을 일반 성능 run과 겹치지 않는다.
- P4-R 준비에서만 데이터 25% 축소와 graph 재빌드를 수행한다. 준비 후 A/HF에는 동일한 축소 graph를 사용하고 calendar, 검색 시간창, 후보 수, GC 정책을 고정한다. 원본 데이터·graph는 보존한다.
- 배포·provider 변경·커밋·PR 제출은 이 계획 범위에 포함하지 않는다.
- 매 단계 종료 시 실행 ledger와 결과 문서를 갱신한다. 완료 run은 재개 시 다시 실행하지 않는다. 실패 run도 보존한다.
- `/private/tmp` raw는 사라질 수 있다. 최종 표·분석 스크립트·manifest를 문서/측정기 경로에 보존하고, 큰 JFR/raw의 보관 위치와 hash를 기록한다.

## 4. 고정 입력

| 항목 | 기준 |
| --- | --- |
| A source | `02c0b4b537eb07bb0b284726397821682248fe20` |
| 기존 A JAR | `/private/tmp/otp-baseline-src-20260913/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` |
| 기존 A SHA | `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52` |
| 기존 HF JAR SHA | `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f` |
| graph | `/private/tmp/otp-graph-rebuild/graph.obj` |
| graph SHA | `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a` |
| 검색 시각 / seed | `2026-09-15T08:00:00+09:00` / `20260908` |
| JVM | Java 25.0.4, G1, `-Xmx10g`, StringDeduplication 유지 |
| container | memory 12g, memory-swap 12g |
| 기존 ARM 이미지 | `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753` |
| 집중 OD | `cityhall-gangnam`, `bupyeong-seoul` |
| 최종 OD | 위 두 개 + `cheonggye-sadang`, `bupyeong-gangnam` |

기존 JAR는 대조용이다. 신규 네 변형은 같은 source baseline·JDK·Maven·빌드 옵션으로 생성하고 각각 새 hash를 기록한다. 예전 ‘H’ 빌드 중 F를 포함한 것이 있으므로 이름만 믿고 H-only로 재사용하지 않는다. 다른 architecture host에서는 해당 architecture의 이미지 digest와 JVM을 고정하고 이전 host 수치와 합산하지 않는다.

`-Xmx10g`는 최대 heap이다. Xms10g를 의미하지 않으며 새로 추가하지 않는다. heap 상한이 크다는 사실만으로 메모리 압박이 없다고 판정하지 않는다.

## 5. 실행 단계

### P4-R — 데이터 25%에서 메모리 압박 없는 성능 회귀 검증 (R0/R1 완료, R2 c1·c2 exploratory 완료, c4 대기)

#### R0. 축소 입력 생성·고정

- `25%`의 기준은 **기존 수도권 GTFS의 trips.txt 행 수 대비 남긴 운행편 수**로 정한다. 파일 용량·bbox 면적·RSS가 정확히 25%가 된다는 뜻은 아니다. 초기 허용 범위는 원본의 22.5–27.5%로 두고 실제 비율을 공개한다.
- 지리적으로 인접한 지역의 노선을 선택하고, 선택 노선의 운행편과 각 운행편의 전체 stop_times를 보존한다. 운행편 무작위 1/4 추출이나 중간 정류장 절단으로 배차·경로를 왜곡하지 않는다. 노선 단위 때문에 허용 범위를 못 맞추면 성능 실행 전에 이유와 실제 비율을 기록하고 선택 규칙을 확정한다.
- stops·routes·service/calendar·shapes·transfers 등 참조를 일관되게 유지한다. calendar의 날짜 범위와 요일은 그대로 두며, 참조 없는 항목 정리만 허용한다. 기존 변환·검증 스크립트를 먼저 확인한다.
- 선택 노선 전체와 도보 접근·환승 연결을 포함하는 OSM 추출 범위를 만들고 bbox·버퍼·추출 규칙을 기록한다. 경계에서 노선이나 도보 연결이 잘리는지 검사한다. OSM 감소 비율도 별도로 기록한다.
- 원본과 축소본의 routes/trips/stops/stop_times 수, OSM 크기, graph 크기·SHA, 입력·빌드 설정·도구 SHA와 선택 목록을 `REDUCED-25-MANIFEST.json`에 남긴다. 원본 경로에 덮어쓰지 않고 별도 디렉터리에 빌드한다. A/HF가 읽는 graph는 같은 파일·SHA여야 한다.
- 축소 지역 안에서 OD 네 개를 고정한다. 짧은/긴 이동 및 환승 경로를 포함하고 기존 OD는 온전히 포함되는 경우 재사용한다. 이번 고정 corpus는 [REDUCED-25-CASES.json](REDUCED-25-CASES.json)이며, measurement runner의 `--cases-file`로 모든 A/HF 실행에 전달한다. OD 선택은 HF 성능을 보기 전에 완료한다. 서비스 날짜·시각·seed는 기존 기준을 유지한다.

#### R1. 정확성·실행 가능성

- 기존 비계측 A/HF JAR의 SHA를 검증해 재사용한다. 신규 축소 graph에서 full-detail correctness 네 OD를 A/HF 각각 실행해 응답 의미·geometry mismatch 0을 확인한다. 기존 전체 graph의 response reference는 재사용하지 않는다.
- `-Xmx10g`(Xms10g 추가 금지), 기존 G1·JDK·이미지·container 12g 조건을 유지한다. 최대 heap 설정만으로 메모리 여유를 판정하지 않는다.
- A/c1 feasibility를 먼저 실행한다. OD별 warmup 최소 100·measured 100, 기존 안정 예열 조건 및 수집 gate를 유지하고 새 4-OD performance reference를 만든다.
- 기존 P4의 run별 host swap-in/out delta 0, warmup 안정화, 요청 성공, RSS/GC OBSERVED, OOM·cleanup 오류 없음 gate를 적용한다. host 가용 메모리·pressure도 전후 기록하고 컨테이너 memory events 등 지원되는 압박 지표를 보관한다. Full GC/evacuation failure가 있으면 압박 원인을 확인하기 전 ‘압박 제거’ 판정을 하지 않는다.
- 축소해도 host swap이 증가할 수 있다. 실제 R1에서 A +38페이지, HF +82페이지가 관측되어 host-wide `swap delta=0` formal gate는 실패했다. 대신 양쪽 모두 peak container 약 1.9GB, OOM 없음, 수집 `OBSERVED`로 OTP/container 압박 감소는 확인됐다. 이후 R2를 실행하면 `REDUCED_PRESSURE_EXPLORATORY`로만 기록하고 formal no-swap 성능 증거로 승격하지 않는다. Docker·DB·Redis·사용자 앱은 계속 유지한다.

#### R2. A/HF paired 비교

- 기존 P4 실행 계약의 c1/c2/c4, 동시성별 최소 5 AB/BA pair(30 run), fresh JVM, OD별 warmup/측정 100, pair 단위 bootstrap 10,000회 및 invalid pair 재실행 규칙을 그대로 쓴다. 현재 host에서 실행하는 결과의 등급은 `REDUCED_PRESSURE_EXPLORATORY`이며, strict formal 결과는 zero-swap host에서 같은 matrix를 재실행해야 한다.
- 단, graph·OD·response reference·산출 경로는 R0/R1에서 확정한 축소 실험 전용 값을 사용한다. `<REDUCED_25_ROOT>/{c1,c2,c4}/pair-<01..05>/{A,HF}/`에 보존한다. 각 동시성에서 요청·RSS·GC·OOM gate를 통과해야 하며, host swap delta는 별도 기록한다. swap delta가 0이 아니면 pair는 exploratory 표본으로만 남긴다.
- p50/p95 및 OD별 p50/p95를 주 판정값으로 삼는다. RSS·GC 총/최대 정지·오류율도 함께 보고한다. allocation 진단이 필요하면 별도 JFR run에서 수집하며 일반 지연 표본과 합치지 않는다.
- `--compare-to` reference는 **동시성별로 별도 생성한 A reference**를 사용한다. c1 reference를 c2/c4에 재사용하지 않는다. manifest의 `concurrency`까지 비교되므로 다른 동시성 reference는 fingerprint가 같아도 invalid attempt가 된다. 각 c2/c4 reference도 동일 graph·cases·when·seed·JVM 조건으로 먼저 만들고, reference 자체의 stable·success·수집 gate를 기록한다.

#### R3. 판정과 주장 범위

- 초기 **실용적 회귀 허용치 δ=5%**를 사전 고정한다. 이는 사용자가 정한 실측 사실이 아니라 이 계획의 권장 판정값이다. ‘조금도 느려지지 않음’을 입증하는 기준은 아니다.
- 개선율 `(A-HF)/A×100`의 run-pair bootstrap 95% CI **하한이 -5%보다 크면** 해당 지표에서 5% 이상 악화를 배제한 것으로 판정한다. CI 상한이 -5% 미만이면 허용치를 넘는 회귀, 경계에 걸치면 `INCONCLUSIVE`다. CI가 0을 포함하는 것만으로 회귀 없음이라고 판정하지 않는다.
- c1/c2/c4 전체 및 사전 고정 OD별 p50/p95가 모두 기준을 충족하고 정확성·환경 gate를 통과해야 `NON_INFERIOR_WITHIN_5_PERCENT`로 보고한다. 5 pair는 최소치이며 CI가 넓으면 미확정으로 종료한다. 결과를 본 뒤 허용치를 늘리지 않는다.
- 성능 개선 주장(예: CI 하한 >0)은 별도로 표시한다. RSS 5% 개선은 이번 비열등성 실험의 필수 조건이 아니다.
- 결론은 ‘GTFS 운행편 약 25%를 보존한 고정 지역·네 OD·동시성 1/2/4에서 5% 이상 지연 회귀를 배제했는가’로 제한한다. 전체 수도권 성능이나 모든 경로로 일반화하지 않는다. 데이터 축소 전후 RSS 차이를 H/F 코드 효과로 계산하지 않는다.
- `REDUCED-25-RESULT.md`, `REDUCED-25-MANIFEST.json`, `REDUCED-25-RUN-LEDGER.md`를 생성하고 NEXT-AGENT와 PR-EVIDENCE에 결과를 연결한다. 기존 전체 데이터 raw·manifest·결과는 이력으로 보존한다.

### P0 — 실행 도구·입력 고정

- [x] 코드 diff·JAR·graph·이미지와 기존 raw 존재 여부를 확인하고 `ABLATION-MANIFEST.json`에 기록한다.
- [x] `run_isolated.py`, `compare_graphql.py`, `summarize_run.py`, `compare_runs.py`의 옵션과 전달 경로를 읽었다.
- [x] 반복 가능한 `--case` 선택, unknown/중복 거부, wrapper 전달을 격리 사본에 구현하고 테스트했다.
- [x] `--when`, `--seed`의 하위 command 전달을 확인했다.
- [x] host pressure/swap, RSS coverage, GC marker, cleanup 필드를 확인했고 runner 테스트 34개가 통과했다.

완료: manifest에 입력 hash, 스크립트 hash, 실제 command, 선택 OD가 기록되고 측정 구간을 정확히 재집계할 수 있다.

### P1 — 네 변형의 분리 빌드와 정합성

| 변형 | 포함할 코드 |
| --- | --- |
| A | baseline 그대로 |
| H | `TripPatternForDates.java`의 참조+offset 변경만 |
| F | `DestinationArrivalPaths.java`, `PathConfig.java`, `McRangeRaptorConfig.java`의 dummy 재사용 변경만 |
| HF | H와 F의 합집합 |

- [x] 현재 diff를 격리 소스 트리로 분리했다. B/C/E2는 포함하지 않았다.
- [x] H/F/HF patch SHA와 변경 파일을 manifest에 기록했다. H-only의 F 부재와 F-only의 H 부재를 source diff로 확인했다.
- [x] H 영향 테스트 18개 및 F Raptor 전체 954개(실패 0, skip 2)를 통과했다. F의 scalar-only 사용처, debug/general comparator fallback, `ParetoSet.qualify`가 후보를 저장하지 않는다는 코드를 확인했다. **단, F 전용 회귀 단위 테스트는 아직 없다**는 잔여 사항을 기록한다.
- [x] A/H/F/HF shaded JAR를 확보했고 class hash로 H/F 변경 클래스가 각 분리 artifact와 HF에서 일치함을 확인했다. 클래스 주입은 사용하지 않았다.
- [x] 같은 graph·시각의 full correctness query에서 A/A, A/H, A/F, A/HF mismatch 0; 전부 두 OD 성공, manifest mismatch 0.

완료: 네 artifact의 출처·변경 범위가 확정되고 correctness mismatch 0. 불일치가 있으면 성능 단계 전에 원인을 해결한다.

### P2 — 비-JFR c1 분리 비교

목적: H/F 중 어느 변경에서 지연 차이가 생기는지 먼저 확인한다.

- [x] 두 집중 OD, c1, OD별 예열 100·측정 200요청, 안정화 window 100·허용 변동 5%·timeout 900초로 고정했다. 연속 window 조건은 manifest에 남겼다.
- [x] 다음 네 블록을 실행했다. 각 칸은 독립 컨테이너/JVM run이며 한 run 안에 두 OD를 동일 seed로 섞었다.

| 블록 | 실행 순서 |
| --- | --- |
| 1 | A → H → HF → F |
| 2 | H → F → A → HF |
| 3 | F → HF → H → A |
| 4 | HF → A → F → H |

총 16 run, 측정 6,400요청이다. 첫 run의 소요시간으로 전체 예상 시간을 ledger에 기록한다. 예열 실패는 같은 조건으로 1회 재시도하고 반복 실패 시 해당 비교를 INCONCLUSIVE로 기록한다. 기준을 바꾸려면 새 실험 ID로 전체 비교 조건을 다시 맞춘다.

- [x] run별·OD별 p50/p95, peak RSS, GC 총정지/최대정지/횟수, 오류율, 환경 delta를 [ABLATION-RUN-LEDGER.md](ABLATION-RUN-LEDGER.md)에 기록했다.
- [x] A↔H, A↔F, A↔HF, H↔HF, F↔HF를 같은 블록 안에서 비교했다. 개별 요청 수천 건을 독립 반복 실험 수로 취급하지 않았다.
- [x] 네 블록 결과는 원인 선별용으로 분류했고, swap I/O 증가 및 sampler 불완전 셀을 구별했다. 작은 표본의 CI를 결정적 증거로 쓰지 않았다.

| Variant | cityhall→gangnam p50 median (vs A) | bupyeong→seoul p50 median (vs A) | peak RSS median (vs A) | GC pause total median (vs A) | Screening signal |
| --- | ---: | ---: | ---: | ---: | --- |
| A | 406ms | 215ms | 3.31GB | 1,542ms | baseline; cityhall A repeat range 393–441ms |
| H | 369ms (-9%) | 167ms (-22%) | 2.63GB (-20%) | 371ms (-76%) | strongest broad latency/RSS/GC signal |
| F* | 486ms (+20%) | 232ms (+8%) | 3.23GB (-3%) | 935ms (-39%) | latency regression signal; little RSS gain |
| HF | 464ms (+14%) | 189ms (-12%) | 2.60GB (-21%) | 293ms (-81%) | H-like memory benefit; cityhall latency mixed/unfavorable |

`*` F summary excludes block 3 F from medians because that run had material host swap-in/out. All P2 medians are screening-only; some runs had nonzero host swap-in, and these are not P4 formal no-swap results. Each valid cell had 400/400 successful requests and zero itinerary fingerprint mismatches. Block 4 HF's first attempt had complete requests but incomplete RSS coverage, so the fresh-JVM rerun is used.

Same-block interaction check: HF p50 versus H was slower in all four blocks (median `+21.5%` cityhall, `+10.4%` bupyeong). HF versus F, excluding host-pressure block 3, was faster in cityhall in two of three blocks (median `-7.3%`) and bupyeong in all three (median `-20.6%`). This is consistent with H improving the F-only latency signal, while F adds latency cost relative to H alone; use P3 to investigate rather than treating these small-block differences as proven causality.

P2 완료 판정: H는 여러 OD에서 메모리·GC 감소 신호와 p50 개선이 보였고, F 단독은 latency regression 방향이 반복됐으며 HF는 메모리 이득과 OD별 latency trade-off가 관측됐다. 이는 screening이지 성능 입증은 아니다.

### P3 — 가설 직접 검증

- [x] allocation 진단: 두 OD 각각 A/H/F/HF를 JFR `profile`로 수집했다. OD별 예열 100·측정 200, 고정 예열, 총 8 run. P2의 지연 표에 합치지 않았다.
- [x] measured marker로 event를 제한하고 allocation weight/request, class·allocation site, CPU stack을 집계했다. 표본 가중치를 정확한 객체 수·retained heap·RSS로 쓰지 않았다.
- [x] H: 별도 계측 빌드에서 measured 200개 route 요청 배치의 `TripPatternForDates` 생성 수, Σ(stops×trips), Σtrips 및 두 배열/accessor 원소·호출 수를 계측했다. `UseCompressedOops=true`를 확인해 교체되는 원소 payload 모델(A 배치 42.07GB, H 0.659GB; 요청당 평균 A 210.37MB, H 3.29MB)을 계산했다. 배열 header/alignment·공통 필드는 별도이며 전체 heap 감소량으로 해석하지 않는다.
- [x] F JIT 및 직접 진단: custom JFR에서 comparator→scalar, `Path`/`MutableDummyPath` 인라이닝 decision을 확인했다. 추가 A/H/F/HF 계측 8회(두 OD, 각 변형 200 measured request)에서 qualifier/comparator 호출 수·candidate-set histogram이 변형 간 동일했고 receiver pair만 `Path`에서 `MutableDummyPath`로 바뀌었다. 관측한 getter inlining 실패, 비교 횟수 증가, 후보 집합 증가 근거는 없다.
- [x] 고빈도 계측은 diagnostic 전용 JAR에서 thread-local 누적 후 종료 시 합산했다. JAR/source hash를 구분했고 계측 run의 지연값은 성능 비교에 사용하지 않았다.
- [x] JFR과 직접 계측을 합쳐 가설 등급을 갱신했다. 두 OD에서 F comparator 호출/후보 증가와 H accessor 호출량 증가는 지지되지 않는다. H 접근당 비용 및 F latency 회귀의 최종 원인은 미확정이다. [P3 JFR](ABLATION-P3-JFR-RESULT.md) 및 [직접 계측](ABLATION-P3-COUNTER-RESULT.md)을 참조한다.

P3 진단 산출은 완료했다. 추가 구현은 원인 근거를 더 얻고자 할 때 별도 후보로 제안한다. P4 formal performance gate는 아래 조건을 별도로 충족해야 한다.

### P4 — PR용 A/HF 최종 비교

- [x] 현재 18GiB Mac에서 A feasibility를 세 번 시도했다. swap-in은 각각 +7,675, +250, +88 pages(약 119.9, 3.91, 1.38MiB)여서 세 run 모두 no-swap gate에 실패했다. 세 run의 지연값은 성능 근거로 쓰지 않는다.
- [x] 03:33 KST 비-benchmark 재점검에서는 30초 동안 swap-in +12 pages를 관측했고, 이후 약 2분 46초 counter가 정체된 구간이 있었다. 그 뒤 feasibility run 중 +88 pages가 발생해 현재 조건에서도 formal gate는 미통과다. 상세 카운터는 [feasibility report](ABLATION-P4-FEASIBILITY.md)에 있다.
- [x] 전체 4개 OD A reference를 두 번째 run에서 생성했다. 첫/두 번째 A measured fingerprint와 세 번째 run의 A reference 비교 모두 OD당 100건에서 mismatch 0이었다.
- [x] Full-detail correctness query에서 A/HF 각각 OD당 1건, 4/4 성공, mismatch 0 및 manifest mismatch 없음. 비교는 전체 itinerary/time/ID/coordinates/geometry를 포함한다. 적용 범위는 고정 네 OD이며 성능 결과가 아니다. [상세](ABLATION-P4-FEASIBILITY.md).
- [ ] swap-in/out이 공식 측정 내내 0인 별도 통제 host를 확보하고 A feasibility gate를 통과시킨다. Docker Desktop 및 DB/Redis를 메모리 확보 목적으로 종료하지 않는다.
- [ ] 환경을 통제할 수 있으면 4 OD, c1/c2/c4, OD당 예열 최소 100·측정 100, 안정 예열 timeout 900초, 동시성별 A/HF 최소 5 AB/BA pair(총 30 run)를 실행한다.
- [ ] 같은 host·같은 빌드끼리만 비교한다. 10,000회 paired bootstrap은 run pair 단위로 수행한다. p50/p95/RSS 각각 개선율과 95% CI, OD별 회귀를 공개한다. Baseline corpus는 측정 workload와 같은 4개 OD여야 한다.
- [ ] 예열·측정 중 host swap-in/out 증가 run은 기존 formal gate 계약상 제외한다. OTP 단독 책임으로 단정하지 않는다. VM counter 미수집·호스트 경합도 한계로 기록한다. 깨끗한 반복 표본을 확보하지 못하면 환경 조건 미충족으로 종료하고 탐색 결과만 제시한다.

#### P4 다음 에이전트 실행 계약

1. 먼저 runner·artifact·graph 경로와 SHA-256을 확인하고, host ID·아키텍처·OS·JDK·Docker image digest를 기록한다. 다른 아키텍처에서는 그 host용 image를 고정하고 이 Mac 결과와 합산하지 않는다. 기존 output 경로는 덮어쓰지 말고 실험 ID를 새로 만든다.
2. P4 matrix 전에 A/c1 feasibility 1회를 실행한다. 네 OD 각각 warmup 100·측정 100, `--query-mode performance`, 안정화 window 100·tolerance 0.05·timeout 900초, `-Xmx10g`, container 12g를 사용한다. 이 run은 4-OD performance-fingerprint 기준 `requests.jsonl`을 만드는 용도이며 paired 표본에는 넣지 않는다.
3. 각 run의 `environment.json`에서 `hostPressureBefore/After`, `hostSwapBefore/After`, `benchmarkExitCode`, `containerInspect`, `metricsStatus`를 보존한다. macOS는 `vm_stat`의 누적 `Swapins`/`Swapouts` 차이를, Linux는 `/proc/vmstat`의 누적 `pswpin`/`pswpout` 차이를 **run별로** 기록한다. 현재 runner의 Linux `hostPressure`는 `/proc/meminfo`만 보관하므로 Linux에서는 `/proc/vmstat` 스냅샷을 별도로 남겨야 한다. 누적 swap-in/out delta 중 하나라도 0이 아니거나 값을 판독할 수 없으면 해당 run은 부적격이다. swap 사용량 스냅샷만 같거나 감소한 것은 통과 근거가 아니다.
4. feasibility A가 no-swap·warmup-stable·400/400 성공·RSS/GC `OBSERVED`·OOM 없음·cleanup 오류 없음 조건을 모두 충족할 때만 다음으로 간다. 실패하면 paired matrix를 시작하지 않는다. 새 host에서 실행할 경우 먼저 full-detail A/HF correctness query를 다시 통과시킨다.
5. matrix는 다음 순서로 수행한다. 각 A/HF 실행은 매번 새 JVM·컨테이너이고, 두 실행 사이에 다른 부하 실험이나 빌드를 겹치지 않는다. `AB`는 A 다음 HF, `BA`는 HF 다음 A를 뜻한다. 두 방향을 번갈아 순서 효과를 완화한다. 각 concurrency의 A/HF run은 그 concurrency 전용 A reference에 `--compare-to`한다.

| 동시성 | 독립 pair 실행 순서 |
| --- | --- |
| c1 | AB, BA, AB, BA, AB |
| c2 | BA, AB, BA, AB, BA |
| c4 | AB, BA, AB, BA, AB |

   경로는 `<P4_ROOT>/{c1,c2,c4}/pair-<01..05>/{A,HF}/`처럼 고정하고 run ID에도 host·동시성·pair·variant를 넣는다. 모든 성능 run은 고정 4 OD, warmup/측정 각각 100건/OD, 같은 `when`·seed·graph·JDK·image·JVM 옵션·container limit를 쓴다. A/HF 양쪽 모두 `--compare-to`에 feasibility A의 4-OD `requests.jsonl`을 지정해 fingerprint mismatch가 0인지 확인한다. bootstrap 입력 순서는 실행 순서와 무관하게 항상 `--pair <A run dir> <HF run dir>`이다. output directory는 실행 전 존재하지 않아야 한다.
6. 한 run이라도 warmup gate 실패, 요청 실패, fingerprint mismatch, RSS/GC incomplete, OOM, cleanup error, swap delta 양수/미확인이라면 raw를 보존하되 유효 pair에 넣지 않는다. 해당 pair의 A와 HF를 모두 새 attempt 경로에서 원래 순서대로 다시 수행하고 둘 다 통과할 때만 pair로 계산한다. 유효 pair 5개를 못 채우면 해당 concurrency는 `INCONCLUSIVE`다.
7. concurrency별로 `compare_runs.py`를 한 번씩 호출하고, 그 concurrency의 5개 pair만 전달한다. 이 도구는 각 run의 전체 p50/p95와 peak RSS 및 OD별 p50/p95에 대해 run-pair 개선율의 10,000회 bootstrap을 계산한다. 양수 개선율은 HF가 A보다 개선됐다는 뜻이다. 95% CI 상한이 0 미만이면 회귀, 하한이 0 초과이고 median 개선율이 5% 이상이면 목표 충족, 하한이 0 초과지만 median이 5% 미만이면 양의 차이는 있으나 목표 미달, CI가 0을 포함하면 차이 미확정으로 해석한다. 단일 요청들을 독립 표본으로 간주하지 않는다. 스크립트는 swap·correctness gate를 직접 확인하지 않으므로 결과 JSON과 pair별 원자료를 ledger에 보존하기 전에 3–6번 gate를 별도로 확인한다.

완료는 ‘개선 입증’이 아니라 비교 결과의 확정이다. CI가 0을 포함하면 우위 미확정, 열세면 회귀로 보고한다. 항상 빠르다는 주장은 하지 않는다. c1 회귀가 남으면 메모리 절감과 지연의 trade-off로 PR 근거에 명시한다.

## 6. 산출물과 종료 기준

- `ABLATION-MANIFEST.json`: source/patch/JAR/graph/toolchain/scripts hash, 변형 범위, 고정 조건.
- `ABLATION-RUN-LEDGER.md`: 단계·run ID·변형·블록·command·시작/종료·성공/실패·raw 경로·재시도 이유.
- `ABLATION-RESULT.md`: 비-JFR 비교표, JFR/직접 계측 결과, 가설별 판정, c1 회귀 재현 여부, 환경 한계.
- `PR-EVIDENCE.md`: PR에서 가능한 주장·불가능한 주장, 환경별 표, 정확성 근거, 재현 명령.
- 완료 후 이 계획의 체크박스와 [LARGE-HEAP-FOLLOWUP.md](LARGE-HEAP-FOLLOWUP.md)를 갱신한다.

다음 에이전트는 P4-R의 R0→R1→R2→R3 순서로 진행한다. 완료한 P0–P3는 재실행하지 않는다. 정합성 실패는 해당 변형의 성능 실험을 중단하고, 환경 제약은 결과 등급을 낮춰 기록한다. 측정되지 않은 가설을 검증 완료로 표현하지 않는다.
