# 10GB H+F CPU·할당 진단 — 두 OD 초기 결과

작성일: 2026-09-14. 상태: **A/HF 두 경로 JFR 수집·초기 분석 완료. 원인 확정이나 성능 gate 판정은 아님.**

## 목적

10GB 반복 비교에서 c1의 경로별 결과가 달랐다. `cityhall-gangnam`은 HF 열세 방향, `bupyeong-seoul`은 개선이었다. 두 OD의 같은 검색 workload에서 A와 H+F의 CPU 샘플과 할당 샘플을 비교해, 어느 비용이 사라졌고 어떤 비용이 남았는지 확인한다.

## 실행 조건

각 OD·변형마다 새 OTP 컨테이너/JVM 하나씩 실행했다. 각 run은 예열 100건, 측정 200건이며 JFR `profile` 설정을 사용했다. 이는 프로파일링 실행으로, 요청 지연 수치를 성능 비교로 해석하지 않는다.

| 항목 | 값 |
| --- | --- |
| heap / container | `-Xmx10g`, `--memory=12g --memory-swap=12g` |
| JVM / GC | Java 25.0.4, G1, `UseStringDeduplication` |
| Java image | `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753` |
| graph | SHA-256 `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a` |
| baseline A JAR | SHA-256 `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52` |
| candidate HF JAR | SHA-256 `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f` |
| route query | 2026-09-15 08:00 KST; seed `20260908`; `first: 3` |

Docker Desktop과 Postgres/Redis는 유지했다. 네 run의 개별 OTP 컨테이너는 완료 후 러너가 정리했다. host의 기존 swap과 앱 상태는 이전 10GB 실험 때와 이어져 있어 완전히 격리된 측정 환경은 아니다.

## 관측 결과

`jdk.ObjectAllocationSample.weight` 합은 JFR sampling 기반 할당량 추정치다. 실제 heap 보유량, RSS, 정확한 할당 byte 계측으로 보면 안 된다. `ExecutionSample`은 CPU stack sample이므로 호출 횟수나 정확한 CPU 시간도 아니다.

| OD / 변형 | weighted 할당 MB/요청 | `int[]` MB/요청 | `TripPatternForDates.<init>` MB/요청 | `Path.dummyPath` MB/요청 | `StopArrivalFactoryC1` MB/요청 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 시청→강남 A | 683.4 | 192.2 | 178.3 | 191.7 | 123.3 |
| 시청→강남 HF | 298.4 | 13.7 | 2.4 | 0.0 | 147.1 |
| 부평→서울 A | 384.1 | 183.2 | 156.9 | 81.6 | 53.7 |
| 부평→서울 HF | 114.8 | 12.2 | 4.6 | 0.0 | 47.6 |

HF에서 전체 allocation weight는 시청→강남 약 56%, 부평→서울 약 70% 낮았다. `TripPatternForDates` 생성 stack은 두 경로에서 약 97–99% 줄었고, `int[]` class allocation weight도 약 93% 줄었다. A에서 큰 `Path.dummyPath` weight가 관측됐지만 HF에서는 관측되지 않았다. 두 OD 모두 `StopArrivalFactoryC1` 할당은 남아 있었고, 시청→강남에서는 HF가 A보다 약 19% 높았다.

코드 구조와 일치하는 결과다. A는 `TripPatternForDates` 생성 시 정류장 수 × 운행편 수만큼 도착·출발 `int[]`를 준비한다. HF는 그 두 배열을 없애고 운행편당 `TripTimes` 참조와 날짜 offset을 보관한 다음 accessor에서 시간을 읽는다. 이로써 큰 초기 배열 할당은 사라졌고, 경로별로 남는 Raptor 상태·정류장 도착 객체 비용은 별도로 존재한다.

### 변경 지점별 allocation 절감의 크기

두 수정의 기여를 더 구체적으로 보면, 전체 감소는 시각표 자료구조(H)만으로 설명되지 않는다.

| OD | 전체 A−HF 차이 | `TripPatternForDates.<init>` site A−HF | `Path.dummyPath` A의 관측치 | `StopArrivalFactoryC1` HF−A |
| --- | ---: | ---: | ---: | ---: |
| 시청→강남 | 385.0 MB/요청 | 175.9 MB/요청 | 191.7 MB/요청; HF에서 weighted sample 미관측 | +23.8 MB/요청 |
| 부평→서울 | 269.4 MB/요청 | 152.3 MB/요청 | 81.6 MB/요청; HF에서 weighted sample 미관측 | −6.2 MB/요청 |

H의 constructor-site 차이와 F의 `Path.dummyPath` A 관측치를 단순 합산하면 전체 A−HF 차이의 약 95% (시청→강남), 87% (부평→서울)에 해당한다. 이는 두 수정 지점이 allocation 감소의 대부분을 설명할 가능성을 보여주는 **프로파일 기반 근사**이지, 각 변경을 분리한 인과 측정은 아니다. 특히 `Path.dummyPath`의 HF 값은 “할당 0”이 아니라 해당 200요청 JFR 표본에서 weighted sample이 관측되지 않았다는 뜻이다. 시청→강남의 정류장 도착 생성 site는 HF에서 오히려 늘어 전체 절감분 일부를 상쇄한다.

코드 diff와 샘플의 대응은 분명하다. H는 정류장×운행편 크기의 두 primitive 배열 대신 운행편별 `TripTimes` 참조와 offset 배열을 둔다. F는 scalar-only comparator이고 debug listener가 없을 때 request-local `MutableDummyPath` 한 개를 재사용하며, debug나 일반 comparator 경로에서는 원래 immutable dummy `Path`를 유지한다. 따라서 현재 JFR은 H와 F 양쪽에서 기대한 큰 allocation site가 줄어든 모양과 맞지만, H/F 각각의 수치 확정에는 분리 빌드가 필요하다.

따라서 다음 실험의 우선순위는 A/H-only/F-only/H+F를 같은 조건으로 비교하는 것이다. H-only는 위 timetable 생성 차이가 실제 H 변경에서 오는지, F-only는 path dummy 재사용만으로 얼마가 줄어드는지 분리한다. 그 다음에도 c1 지연 차이가 남으면 Pareto 비교·경로 후보 비용을 계측한다. 현재 표본에서 accessor 직접 호출은 눈에 띄지 않았지만, JIT 인라이닝 때문에 이것만으로 accessor 비용을 배제할 수는 없다.

CPU `ExecutionSample`에서 해당 메서드가 포함된 stack의 비율은 다음과 같았다. 서로 겹칠 수 있고 독점 CPU 비율이 아니다.

| OD | timetable 생성 A→HF | board search A→HF | Pareto 관련 A→HF |
| --- | ---: | ---: | ---: |
| 시청→강남 | 6.1% → 1.1% | 5.4% → 8.0% | 16.6% → 32.4% |
| 부평→서울 | 12.1% → 2.5% | 3.1% → 5.5% | 8.6% → 21.0% |

HF의 CPU 샘플에서는 `RaptorPath` 비교 메서드와 `ParetoSet`/경로 우선순위 비교가 더 높은 비중을 차지했다. 반면 `TripPatternForDates.arrivalTime/departureTime` 및 `TripTimes` accessor 자체는 샘플에서 거의 잡히지 않았다. 이 메서드들은 JIT 인라이닝될 수 있어, 표본이 적다는 이유만으로 조회 비용이 없다고 결론 낼 수는 없다.

코드를 대조하면 F 쪽의 검증할 만한 가설이 하나 생긴다. `ParetoSet.qualify`는 새 dummy 후보와 이미 저장된 결과를 comparator로 여러 번 비교하고, comparator는 `RaptorPath` scalar getter들을 호출한다. 기존에는 dummy 후보도 `Path`였지만 최적화 경로는 `MutableDummyPath`를 후보로 전달한다. 따라서 getter 호출의 실제 receiver 타입이 달라져 JIT의 devirtualization/inlining이 달라졌을 가능성이 있다. 이것은 Pareto stack 증가와 맞는 **가설일 뿐**이며, `ExecutionSample`만으로 타입 분기나 인라이닝 실패를 확인한 것은 아니다. F-only 비교와 JIT compilation/inlining 로그(또는 해당 getter/comparator의 전용 미세 계측)로 검증해야 한다.

## 해석과 제한

직접 확인된 점은 HF가 `TripPatternForDates` 생성 및 `int[]` allocation weight를 크게 낮추고 `Path.dummyPath`의 관측된 할당을 없앴다는 것이다. 이전 결과에서 나타난 RSS 감소와 방향이 같다.

Pareto 및 board-search stack 비중이 늘어난 현상은 HF에서 남은 검색 비용의 후보를 가리킨다. 하지만 이것만으로 c1 지연 열세의 원인이라고 확정할 수 없다. JFR 수집 run은 한 변형·한 OD당 1회이고 프로파일링 overhead가 있다. 실제 이 진단 실행의 p50은 시청→강남 A 462ms/HF 429ms, 부평→서울 A 228ms/HF 179ms였는데, 앞선 비-JFR 5-pair c1의 시청→강남 OD별 통계와 방향이 달랐다. 따라서 여기의 p50/p95는 원인 설명이나 PR 성능 주장에 사용하지 않는다. 네 run은 모두 200/200 요청 성공했고, HF routing fingerprint는 각 A baseline과 일치했다.

또한 현재 결과는 H+F 묶음 비교다. timetable 구조(H)와 path 처리(F) 중 어느 쪽이 각각 할당·Pareto stack 변화에 기여했는지 분리하지 못했다. Allocation weight는 할당 샘플 추정이며 retained heap이나 RSS 절감 byte로 환산하면 안 된다.

## 다음 분석

1. A/H-only/F-only/H+F를 같은 OD와 동일한 실행 조건으로 분리해 각 변경의 allocation·CPU 효과를 나눈다.
2. F-only에서 `MutableDummyPath`와 저장된 `Path`가 comparator getter에 주는 receiver 타입 및 JIT inlining을 확인한다. Pareto 비교 횟수/CPU도 계측한다.
3. `TripPatternForDates` time accessor 호출·비용을 직접 계측한다. JIT 인라인 가능성이 있으므로 JFR stack 비율만으로 조회 병목을 판단하지 않는다.
4. 원인을 좁힌 뒤에만 lazy cache 등 후속 후보를 설계하고, 별도 비-JFR paired 성능 gate로 검증한다.

## 원시 자료

JFR와 분석 JSON은 `/private/tmp/otp-od-profile-20260914-18Rel2/`에 보존했다.

| Run | JFR SHA-256 |
| --- | --- |
| A 시청→강남 | `9a115dbd13c1d39b20feb7d389268ce7da92be2a86814ce4ad37dd3fe7c55a1c` |
| HF 시청→강남 | `89ce70c3ea0a285129611b3be2c704bad94b00a76ba72af375fc7304379323df` |
| A 부평→서울 | `70ac4ad1b8ade8efbb791092e5575d5fbef2754ffecbaa5ef0d3d3e629f3507b` |
| HF 부평→서울 | `64ff31f9def66d9ded1feb1394b067b24cd89a08226edcfe721f495ab1dfa62a` |
