# OTP 최적화 이해 문서 — 기초부터 "왜 그렇게 했나"까지

작성일: 2026-09-11. 대상 독자: 이 변경을 직접 설명해야 하는 사람(= 나). 코드 좌표는 OTP 저장소 기준.

---

## 0. 한 줄 요약

**길찾기 엔진이 검색할 때마다 시각표를 통째로 복사하던 것을, 원본을 가리키는 참조로 바꿔 메모리 절반을 덜 쓰게 했다.** 그리고 "이 경로가 쓸모 있나" 판정할 때마다 만들던 임시 객체 하나를 재사용하게 했다.

비유: 도서관에서 책을 볼 때마다 전권을 복사해 책상에 쌓아 두고 읽던 것을, 책꽂이의 원본을 펼쳐 보는 것으로 바꿨다. 대신 페이지를 넘길 때 책꽂이까지 한 번 더 걸어가야 한다. 그래서 혼자 읽을 땐 조금밖에 안 빨라지고, 넷이 동시에 읽을 땐(책상이 좁아 서로 밀치던 상황) 크게 빨라진다.

---

## 1. 알아야 할 기초

### 1.1 OTP 가 뭐고 우리는 왜 쓰나

OpenTripPlanner(OTP) = 오픈소스 대중교통 길찾기 엔진. 시각표(GTFS)와 도로망(OSM)을 읽어 하나의 큰 그래프 파일(`graph.obj`)로 만들고, 그것을 메모리에 올려 두고 검색 요청에 답한다.

우리가 쓰는 이유: 기존 검색은 ODsay API 였는데 호출 한도가 있어 재탐색 같은 기능을 제한하고 있었다(`docs/plan/national-otp-map-migration/PLAN.md`). 자체 엔진이면 호출 한도가 없다. 대신 **메모리를 우리 서버(VPS 8GiB, 다른 컨테이너와 공유)가 감당해야 한다.** 용량 실험(`capacity-report.md`)에서 전국 그래프는 4GiB heap 으로 동시 4건 검색 시 20~45초, Full GC 41회였다. 수도권으로 범위를 줄여도 메모리가 병목이라는 사실은 그대로였다. 그래서 "코드로 메모리를 줄일 수 있나"가 이 작업의 출발점.

### 1.2 GTFS 용어 (시각표 데이터 모양)

| 용어 | 뜻 | 예 |
|---|---|---|
| stop | 정류장/역 | 강남역 |
| route | 노선 | 2호선, 472번 버스 |
| trip | 그 노선을 한 번 달리는 특정 차편 | 472번 07:10 출발편 |
| stop_times | trip 하나가 각 정류장에 도착·출발하는 시각 목록 | 07:10 A, 07:14 B, 07:19 C … |
| trip pattern | 같은 정류장 순서를 지나는 trip 들의 묶음 | 472번 상행(정류장 순서 동일)의 trip 200개 |
| service day | 운행일 기준. 새벽 1시 열차는 "전날 25:00"으로 표현됨 | |

핵심: **한 pattern 은 "정류장 목록"과 "그 정류장을 지나는 trip 들의 시각 표"를 갖는다.** 표는 세로가 정류장, 가로가 trip 인 2차원 표라고 생각하면 된다.

OTP 안에서 이 표의 한 열(trip 하나의 시각들)이 `TripTimes` 객체다. 정류장별 도착시각 `int[]`, 출발시각 `int[]` 를 들고 있고, `timeShift`(전체를 몇 초 미루는 값)를 더해서 돌려준다(`ScheduledTripTimes.getScheduledArrivalTime` → `timeShifted(arrivalTimes[stopPos])`). **그래프에 영구히 올라가 있는, 바뀌지 않는 객체**다.

### 1.3 Raptor — 길찾기 알고리즘의 뼈대

Raptor 는 "환승 횟수"를 라운드로 삼아 반복한다.

1. 라운드 0: 출발지에서 걸어서 갈 수 있는 정류장들에 도착시각을 적는다.
2. 라운드 k: 지난 라운드에 도착시각이 갱신된 정류장을 지나는 **모든 pattern** 을 훑는다. 각 pattern 에서 "이 정류장에서 내 도착시각 이후 가장 먼저 출발하는 trip" 을 찾고(=board search), 그 trip 을 타고 가며 뒤 정류장들의 도착시각을 갱신한다.
3. 갱신이 없을 때까지 또는 최대 환승 수까지 반복.

board search 가 하는 일 = 표의 **한 행(정류장 하나)** 을 trip 순서대로 보며 `departureTime(stop, trip) >= 내가 도착한 시각` 인 첫 trip 찾기(`TripScheduleBoardSearch.java:166`). 이 호출이 **검색당 수백만 번** 일어난다. 그래서 이 표를 어떻게 저장하느냐가 성능을 좌우한다.

두 가지 변형이 더 붙어 있다.
- **Range Raptor**: 출발시각 하나가 아니라 "지금부터 한 시간 안 아무 때나 출발" 같은 창을 처리. 창의 뒤쪽 시각부터 앞으로 오면서 반복 실행한다. 반복 횟수만큼 위 작업이 곱해진다.
- **Multi-criteria(McRaptor)**: "가장 빠른 것" 하나가 아니라 시간·환승수·비용(c1)·보조 기준(c2)이 서로 우열을 못 가리는 경로들을 전부 남긴다. 이 "서로 못 가리는 집합"이 **파레토 집합(ParetoSet)**. 새 후보가 오면 집합의 기존 원소와 비교해 지배당하면 버리고, 아니면 넣는다.

### 1.4 JVM 메모리 — 왜 "임시 객체 많이 만들기"가 느리고 무거운가

- **heap**: 자바 객체가 사는 곳. `-Xmx3500m` 은 heap 상한.
- **RSS**: 운영체제가 보는 프로세스 실제 메모리. heap + 스레드 스택 + JIT 코드 + GC 메타데이터 + 네이티브. 우리가 줄이려는 것은 이것(서버 메모리에 실제로 잡히는 값).
- **할당(allocation)**: `new int[…]` 할 때마다 heap 에서 공간을 받는다. 요청마다 수백 MB 를 할당하면 (1) GC 가 자주 돌고 (2) GC 가 도는 동안 검색 스레드가 멈추고 (3) 살아 있는 객체가 동시에 많으면 heap 이 커져 RSS 도 커진다.
- 동시 요청이 늘수록 이 비용이 겹친다. 동시 4건이 각각 수백 MB 를 만들면 heap 3.5G 가 금방 차고 Full GC 가 반복된다 — 용량 실험에서 본 그 증상.

**JFR(Java Flight Recorder)**: JVM 에 내장된 프로파일러. 실행 중 "어느 코드가 CPU 를 썼나", "어느 코드가 어떤 타입을 얼마나 할당했나"를 **표본 추출**로 기록한다. 전수 조사가 아니라 표본이라, "비중"은 믿되 "정확한 바이트"로 쓰면 안 된다(PLAN §4 지표 표에 그렇게 적혀 있다).

---

## 2. 문제를 찾은 순서 — 실제로는 이렇게 흘러갔다

### 2.1 처음엔 프로파일 없이 시작했다 (B, C)

에이전트가 먼저 "그럴듯한" 최적화 두 개를 만들었다.
- **B**: `TripPatternForDate.newWithFilteredTripTimes` 가 제외할 trip 이 없어도 새 리스트를 만들던 것 → 제외가 생길 때만 만들기.
- **C**: `createTripPatternsPerStop` 이 크기 모르고 리스트에 쌓던 것 → 개수 먼저 세고 정확한 크기의 배열 만들기(2-pass).

둘 다 "할당을 줄인다"는 말은 맞다. 그런데 재 보니(PLAN §2 "현재 증거 수준") **동시성 1 에서 p50 566ms → 784ms 로 오히려 느려졌다.** 메모리 개선 증거도 없었다.

교훈 1: **할당 감소 ≠ 성능 개선.** 줄인 할당이 전체의 몇 %인지 모르면 의미 없다. C 의 2-pass 는 순회를 두 번 하니 CPU 를 더 쓴다.

교훈 2: 그래서 계획서 §1 에 "임시 객체 할당량 감소, 단위 테스트 통과, 빌드 성공만으로 성공 선언 금지" 가 들어갔다.

### 2.2 프로파일을 찍었다 (Phase 3)

JFR 로 동시 4건 부하를 찍어 보니(`handoffs/PHASE-3.md` "프로파일 근거"):
- CPU 상위는 Raptor 본체(`alightOnlyRegularTransferExist`, 파레토 비교). B/C 가 건드린 메서드는 hotspot 이 아니었다(각 1.66%, 4.34%).
- **할당 상위는 `int[]` 가 38%. 시간 구간으로 잘라 보면 그 `int[]` 의 94% 가 `TripPatternForDates.<init>`**, 즉 생성자에서 나온다.

여기서 방향이 잡혔다. "생성자에서 만드는 큰 int 배열"이 뭔지 보면 된다.

---

## 3. `TripPatternForDates` — 무엇이고 왜 요청마다 만드나

### 3.1 무엇

`application/.../raptoradapter/transit/request/TripPatternForDates.java`. Raptor 가 검색 중 읽는 **pattern 하나의 검색용 시각표**다. 인터페이스 `TripSearchTimetable` 의 두 메서드가 핵심:

```java
int arrivalTime(int stopPositionInPattern, int tripIndex);
int departureTime(int stopPositionInPattern, int tripIndex);
```

board search 가 부르는 것이 바로 이것이다.

### 3.2 왜 그래프의 `TripTimes` 를 바로 안 쓰고 요청마다 새로 만드나

검색 조건이 요청마다 다르기 때문이다(`RaptorRoutingRequestTransitDataCreator`).
- **날짜 합치기**: 오늘 새벽 검색이면 "어제 service day 의 늦은 trip" 과 "오늘 trip" 이 같이 후보다. 여러 날의 pattern 을 하나로 합치고, 날짜별로 `offset`(어제면 -86400초 같은 값)을 더해 **같은 시간축**으로 맞춘다.
- **필터**: 휠체어 가능 여부, 금지 노선, 탑승·하차 가능 정류장(`filterAvailableStops`).
- **실시간 스냅샷**: 지연 반영된 시각표를 그 시점 기준으로 고정.

그래서 "요청 시점의 조건으로 정리된 표"를 요청마다 만든다. 이건 OTP 설계상 타당하다. 문제는 **그 표를 어떻게 채우느냐**였다.

### 3.3 원본이 하던 일 — 전부 복사

원본 생성자(변경 전):

```java
this.arrivalTimes   = new int[nStops * numberOfTripSchedules];
this.departureTimes = new int[nStops * numberOfTripSchedules];
for (int i = 0; i < numberOfTripSchedules; i++) {
  var tt = ...;  // 그래프의 TripTimes
  for (int s = 0; s < nStops; s++) {
    arrivalTimes[s * numberOfTripSchedules + i]   = tt.getArrivalTime(s) + offset;
    departureTimes[s * numberOfTripSchedules + i] = tt.getDepartureTime(s) + offset;
  }
}
```

pattern 마다 `정류장 수 × trip 수` 크기의 int 배열을 **2개** 만들고, 그래프에 이미 있는 값을 offset 만 더해 전부 베껴 넣는다. 배열은 stop-major: `[1A, 1B, 2A, 2B]` 처럼 "정류장 1 의 모든 trip, 정류장 2 의 모든 trip" 순서다.

왜 이렇게 만들었나(원본 설계의 근거, 반드시 이해할 것):
- board search 는 **한 정류장의 trip 들을 순서대로** 본다. stop-major 배열이면 그 값들이 메모리에 연속으로 붙어 있어 CPU 캐시에 한 번에 올라온다. 배열 인덱스 계산 한 번이면 값이 나온다.
- 이 구조는 upstream 이 2026-03 에 PR #7393 로 다듬은 것이다. 그 전엔 람다(`IntUnaryOperator`)를 돌려줬는데, 람다 객체 할당이 6.5% 를 차지해 직접 인덱싱으로 바꿨다. 즉 **upstream 도 이 자리의 할당을 신경 쓰고 있었고, 배열 자체는 의도적으로 남겼다.**

규모 감: 수도권 그래프의 pattern 은 수천 개, 정류장 30~60개, trip 수백 개. `50 × 300 × 4바이트 × 2 = 120KB` 가 pattern 하나. 수천 개면 **요청 하나에 수백 MB.** JFR 표본 가중치로 원본 34,850MB(측정 구간 누적 표본)가 이 생성자에서 나왔다(`NEXT-AGENT.md` §2).

---

## 4. 후보 H — 복사하지 말고 가리켜라

### 4.1 무엇을 바꿨나

```java
// 변경 후
private final TripTimes[] tripTimesByIndex;   // trip 마다 그래프의 원본 TripTimes 참조
private final int[]       offsetsByTrip;      // trip 마다 날짜 offset

public int arrivalTime(int stop, int trip) {
  return tripTimesByIndex[trip].getArrivalTime(stop) + offsetsByTrip[trip];
}
```

배열 2개(`nStops × nTrips`)가 배열 2개(`nTrips`)로 줄었다. 정류장 수만큼 나눈 크기. 값은 읽을 때 원본에서 꺼내 offset 을 더한다.

### 4.2 이게 안전한 이유 — 리뷰어가 가장 먼저 물을 것

"요청 중에 원본 `TripTimes` 가 바뀌면(실시간 지연 갱신) 검색 도중 값이 흔들리지 않나?"

답: **`TripTimes` 는 불변 스냅샷이다.** `ScheduledTripTimes` 는 final 필드뿐이고, `RealTimeTripTimes` 도 실시간 갱신 시 **새 객체를 만들어 timetable 에 교체**한다. 이미 참조를 잡은 요청은 옛 객체를 끝까지 본다. 원본 코드도 생성자에서 같은 참조로부터 값을 읽었으니, "요청 시작 시점의 값을 본다"는 성질은 동일하다. (PHASE-3.md "후보 H" 항목에서 확인한 계약.)

### 4.3 잃는 것 — 리뷰어가 두 번째로 물을 것

접근 한 번의 비용이 늘었다.

| | 원본 | H |
|---|---|---|
| 메모리 접근 | 배열 1회 (연속 메모리) | `tripTimesByIndex[trip]` → 그 객체 → 그 안의 `int[]` : 간접 참조 2단 |
| 연산 | 인덱스 곱셈 1회 | `timeShift` 덧셈 + `offset` 덧셈 |
| 호출 | 없음 | `getArrivalTime` 가상 호출 (Scheduled/RealTime 두 구현) |
| 캐시 지역성 | 한 정류장의 trip 들이 붙어 있음 | trip 마다 다른 객체로 흩어짐 |

그래서 **동시성 1 에서는 이득이 작다**(p50 +7.8%, 신뢰구간 하한 +1.3%). GC 압력이 낮을 땐 복사 비용 절감보다 접근 비용 증가가 상쇄한다. **동시성 4 에서는 이득이 크다**(p50 +40.7%, p95 +54.9%). heap 이 꽉 차 GC 가 병목일 때는 할당을 없앤 효과가 압도한다. 메모리(RSS)는 동시성 무관하게 41~48% 감소.

이 트레이드오프를 숨기지 않고 말하는 것이 PR 의 설득력이다. "우리 서버처럼 메모리가 빠듯한 환경에서는 이쪽이 낫다. 메모리 넉넉하고 단일 요청 지연이 전부인 환경에선 원본이 나을 수 있다."

### 4.4 중간에 버린 후보 E2 — 왜 부족했나

E2 = 출발시각 배열을 "도착시각과의 차이(정차 시간)"로 `short[]` 에 저장. 절반(departure)만 절반 크기로 줄인 셈. 프로파일을 다시 찍어 보니 남은 `int[]` 는 여전히 `arrivalTimes`. 근본은 "복사 자체"라서 반쪽 최적화로는 목표(5%)에 못 미쳤다. H 로 감.

---

## 5. 후보 F — 판정용 임시 경로를 재사용

### 5.1 배경: 목적지 파레토 집합과 가지치기

McRaptor 는 목적지에 도착한 경로들을 `DestinationArrivalPaths` 안의 파레토 집합에 모은다. 그리고 강력한 가지치기(`Optimization.PARETO_CHECK_AGAINST_DESTINATION`)를 한다:

> 어떤 정류장에 도착했을 때, 미리 계산한 휴리스틱(여기서 목적지까지 최소 시간·최소 환승·최소 비용)을 더해 **"최선을 다해도 이미 있는 경로보다 못하면" 그 정류장 도착을 버린다.** (`HeuristicsProvider.rejectDestinationArrivalBasedOnHeuristic`)

이 판정을 위해 `paths.qualify(출발, 도착, 환승, 비용, c2)` 를 부른다. 원본은 여기서 `Path.dummyPath(...)` 로 **leg 없는 가짜 경로 객체**를 만들어 파레토 집합의 비교자에 넘긴다. 비교만 하고 버린다. 그런데 이 호출이 **정류장 도착마다** 일어난다. JFR: 할당 표본의 `Path` 가 21%.

### 5.2 무엇을 바꿨나

`MutableDummyPath` 하나를 `DestinationArrivalPaths` 인스턴스(= 요청 하나)에 두고, `qualify` 때마다 `set(...)` 으로 값만 바꿔 넘긴다. 할당 0.

### 5.3 안전 조건 — 왜 플래그를 달았나

재사용은 두 가지가 보장될 때만 안전하다.
1. **집합이 그 객체를 저장하지 않는다.** `ParetoSet.qualify` 는 boolean 만 돌려주고 원소를 넣지 않는다. 확인됨.
2. **비교자가 leg 를 읽지 않는다.** `MutableDummyPath` 는 leg 가 비어 있다(`accessLeg()` 가 null). OTP 내장 비교자는 시간·환승·c1·c2 같은 **스칼라만** 읽는다. 그러나 `createDestArrivalPaths(costConfig, comparator)` 는 public 이라 외부가 임의 비교자를 넣을 수 있다. 그 비교자가 leg 를 읽으면 빈 leg 로 잘못 판정한다.

그래서 `scalarOnlyComparator` 파라미터를 추가하고 **기본은 false(기존 immutable dummy)**, OTP 내부 설정 경로(`McRangeRaptorConfig`, `createDestArrivalPathsStdSearch`)만 true 로 켠다. 또 **디버그 모드에서는 끈다**: 디버그 리스너는 거절된 값을 보관할 수 있어 재사용 객체면 나중에 값이 바뀐다.

OTP 의 개발 결정 기록(DDR) 에 "불변 타입 선호"가 있다. F 는 이를 어긴다. 어긴 이유(요청 지역·저장 안 됨·플래그로 격리)를 Javadoc 과 PR 본문에 적어야 한다. 이미 Javadoc 에 적혀 있다.

### 5.4 F 의 근거는 H 보다 약하다

F 단독 측정은 동시성 4 에서 오히려 나빠진 쌍이 있어 `INCONCLUSIVE`. 확실한 것은 "Path 할당 21% → 0.01%" 뿐이다. 최종 판정은 H+F 를 함께 잰 것이다. upstream 에 낼 때는 F 를 따로 떼고 "할당 감소"만 주장해야 한다.

---

## 6. 탈락한 것들 한눈에

| 후보 | 내용 | 결과 | 왜 |
|---|---|---|---|
| B | 필터 결과 리스트 지연 생성 | 탈락 | hotspot 아님(CPU 1.66%). 효과 측정 안 됨 |
| C | 정확 크기 배열 2-pass | 탈락 | 순회 2회로 CPU 증가, 이득 없음 |
| E2 | 출발시각 short delta | 부족 | 복사 절반만 줄임 |
| G | 휴리스틱으로 StopArrival 생성 지연 | 탈락 | (PHASE-3 §후보 G) 이득 미확인 |
| B/C/F/H 조합 | 전부 합침 | **NOT_MET** | 평균 p50 +7% 였지만 `cityhall-gangnam` OD 하나가 3회 연속 5% 넘게 악화. 계획서 §1 "개별 경로 회귀를 평균으로 숨기지 않는다" 위반 |
| **H+F** | | **VERIFIED** | 아래 §7 |

"조합이 평균으로는 좋았는데 버렸다"가 이 작업에서 가장 설명하기 좋은 판단이다. 특정 경로가 느려지는 변경을 평균 뒤에 숨기면 사용자 일부는 매번 느려진다.

---

## 7. 어떻게 쟀나 — 왜 이렇게 까다롭게

성능 측정은 틀리기 쉽다. 계획서 §4 는 그 함정을 하나씩 막는다.

| 함정 | 막는 방법 |
|---|---|
| JIT 예열 전 값이 섞임 | 예열 100건 이상 + 연속 3구간 p50 변동 5% 이내여야 측정 시작 |
| 같은 JVM 을 이어 쓰면 앞 실행의 GC 상태가 남음 | 실행마다 새 JVM(새 컨테이너) |
| 먼저 돈 쪽이 유리/불리(호스트 온도·캐시) | 원본→후보, 후보→원본 순서를 번갈아(AB/BA) |
| 한 번 재서 운 좋은 값 | 독립 쌍 5개 이상, 쌍별 개선율의 중앙값 + paired bootstrap 95% 구간. 하한이 0% 넘어야 함 |
| 평균은 좋은데 특정 경로가 나쁨 | OD 별 p50/p95 따로 판정 |
| 결과가 달라졌는데 빨라진 것 | 모든 응답의 itinerary 전체(노선·trip·정류장·시각·geometry) fingerprint 를 원본과 비교, mismatch 0 |
| 오류 응답이 빨라서 평균을 낮춤 | 오류는 latency 집계에서 제외하고 별도 기록 |
| heap 만 보고 메모리 줄었다고 함 | 컨테이너 안 Java PID 의 `/proc/<pid>/status VmRSS` 를 100ms 마다 샘플링. `docker stats` 는 안 씀 |
| 잠깐은 좋은데 오래 돌리면 새는 것 | 동시성 4, 30분 soak, 5분 창별 RSS/GC/p95 추세 |
| 소스가 뭔지 모르는 JAR | 정상 Maven 빌드 + SHA-256 기록. class 주입 JAR 은 최종 근거에서 제외 |

최종 결과(H+F, 원본 대비 개선율, `PLAN.md` "2026-09-11 최신 실행 판정"):

| 동시성 | p50 | p95 | peak RSS | 쌍 수 |
|---|---|---|---|---|
| 1 | +7.79% [+1.26, +16.54] | +20.54% [+6.32, +25.75] | +41.27% [+36.87, +47.40] | 10 |
| 2 | +22.71% [+17.19, +30.62] | +28.72% [+26.65, +52.31] | +47.68% [+42.60, +50.29] | 5 |
| 4 | +40.74% [+40.04, +46.57] | +54.90% [+47.85, +67.80] | +45.56% [+42.65, +53.00] | 5 |

soak: 16,000/16,000 성공, 31.5분, peak RSS 1.98GB(원본 계열 ~4.0GB). 정합성 14건 mismatch 0. 회귀 테스트 8,225개 통과.

읽는 법: 대괄호는 "진짜 값이 있을 법한 범위". 동시성 1 의 p50 하한 +1.26% 는 "거의 잡음일 수도 있다"는 뜻이라 헤드라인으로 쓰지 않는다. 헤드라인은 RSS(41~48%)와 동시성 4 의 p95(55%).

### 7.1 VPS에서 실제로 확인한 값

로컬 검증 뒤 x86 8GB VPS의 라이브 환경에서 OTP 컨테이너에 4500m 상한을 주고 A/H+F를 각각 1회 관측했다. OD 4개 × 20건, 동시성 1·2·4 모두 80/80 성공했고 OOM은 없었다.

| 동시성 | A / H+F peak RSS | A / H+F p95 | A / H+F GC 총정지 |
| ---: | ---: | ---: | ---: |
| 1 | 3.48G / 2.04G | 1,175 / 1,303ms | 766 / 266ms |
| 2 | 4.08G / 2.09G | 1,596 / 1,380ms | 4,461 / 282ms |
| 4 | 4.07G / 1.98G | 2,224 / 1,750ms | 6,240 / 449ms |

여기서 정정된 핵심은 “원본은 VPS에서 실행되지 않는다”가 아니라 “원본도 실행되지만 H+F가 절반 수준의 RSS와 훨씬 짧은 GC 정지로 더 큰 운영 여유를 준다”는 것이다. c4에서 H+F는 p95도 약 21% 낮았다. 단, 각 조건 1회 관측이고 로컬과 graph SHA·CPU·아키텍처가 달라 통계적 일반화나 특정 객체의 인과 증명으로 쓰지 않는다. 자세한 근거는 [VPS-OBSERVATION-RESULT.md](handoffs/VPS-OBSERVATION-RESULT.md)와 [LARGE-HEAP-FOLLOWUP.md](handoffs/LARGE-HEAP-FOLLOWUP.md)를 따른다.

---

## 8. upstream 맥락 — 우리 변경은 남의 최근 결정 위에 서 있다

- PR #7393 (2026-03, Entur 의 vpaturet): `TripPatternForDates` 의 람다를 없애고 지금의 flat 배열 인덱싱을 만든 PR. 근거 형식 = JFR 할당 프로파일 + A/B 3회 × 9,999 요청.
- 우리 H 는 그 배열을 없앤다. **같은 자리, 같은 방법론(프로파일→할당 지점→A/B), 한 단계 더 나간 제안.** 이 맥락을 PR 본문에 먼저 써야 리뷰어가 "이미 우리가 본 자리인데?" 가 아니라 "그 다음 단계군" 으로 읽는다.
- 예상 질문과 답:
  1. "실시간 갱신 중 참조가 흔들리지 않나?" → §4.2. 불변 스냅샷, 갱신은 새 객체 교체.
  2. "hot loop 에 간접 참조를 넣으면 느려지지 않나?" → §4.3. 동시성 1 에서 이득 작음을 인정. GC 압력 있는 환경에서 이득. 표 제시.
  3. "너희 데이터셋(수도권)에서만 재본 것 아닌가?" → 맞다. upstream speed test(norway 등)로 재현 요청하면 돌리겠다고 미리 제안.
  4. "F 의 mutable 객체는 DDR 위반" → §5.3. 플래그 기본 false, 내부 경로만, 디버그 시 비활성. 별도 PR 로 분리.
  5. "AI 도구로 만든 코드인가?" → CONTRIBUTING 에 "본인이 이해하고 설명 가능해야" 조항. 이 문서가 그 준비.

---

## 9. 30초 설명 (면접·PR 첫 문단)

> OTP 는 검색할 때마다 노선 pattern 별로 시각표 전체를 int 배열에 복사합니다. 프로파일링하니 요청당 할당의 38%가 int 배열이고 그중 94%가 이 복사였습니다. 원본 시각표는 불변 객체라 복사할 필요가 없어서, 배열 대신 원본 참조와 날짜 offset 만 들고 읽을 때 더하도록 바꿨습니다. 접근당 간접 참조가 하나 늘어 단일 요청에서는 이득이 작지만, 메모리가 빠듯한 동시 요청 상황에서는 GC 압력이 사라져 RSS 41~48%, 동시 4건 p95 55% 개선을 독립 JVM 20쌍 paired bootstrap 으로 확인했습니다. 평균은 좋았지만 특정 경로가 느려진 조합은 버렸습니다.

## 10. 자가 점검 — 막힘 없이 답할 수 있어야 하는 질문

1. Raptor 의 "라운드"는 무엇을 뜻하나?
2. board search 가 `departureTime(stop, trip)` 을 어떤 순서로 부르나? 그래서 원본 배열이 stop-major 인 이유는?
3. `TripPatternForDates` 를 요청마다 새로 만드는 이유 세 가지는?
4. offset 은 무엇이고 왜 trip 마다 다를 수 있나?
5. `TripTimes` 가 요청 중 안 바뀐다고 어떻게 확신하나?
6. H 가 접근당 추가로 치르는 비용 네 가지는?
7. 동시성 1 과 4 에서 이득 차이가 나는 이유는?
8. F 의 `qualify` 는 어디서, 무엇을 판정하려고 불리나?
9. F 가 안전하려면 두 가지 조건은? 플래그 기본값이 false 인 이유는?
10. B/C/F/H 조합을 평균이 좋았는데도 버린 근거는?
11. paired bootstrap 95% 구간 하한이 0 을 넘어야 하는 이유는?
12. heap 과 RSS 의 차이는? 왜 `docker stats` 값을 안 썼나?
13. PR #7393 은 무엇을 했고 우리 변경과 어떤 관계인가?

답이 막히는 번호가 있으면 해당 §로 돌아간다. 코드로 확인하려면: 2→`TripScheduleBoardSearch.java:160-220`, 3→`RaptorRoutingRequestTransitDataCreator.java:120-150`, 5→`ScheduledTripTimes`/`RealTimeTripTimes` 필드 선언, 8→`HeuristicsProvider.java:52-110`, 9→`DestinationArrivalPaths.java` 생성자와 `qualify`.
