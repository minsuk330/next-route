# H-only 최적화 이해 문서 — OTP 전체 흐름부터 Baden-Württemberg 실험까지

작성일: 2026-09-16  
대상 독자: H 변경을 코드, 알고리즘, 성능 측정 관점에서 직접 설명해야 하는 사람  
범위: OpenTripPlanner의 `TripPatternForDates` H-only 변경과 Baden-Württemberg 공식 SpeedTest 관측

---

## 0. 먼저 결론

H는 길찾기 결과를 계산하는 RAPTOR 알고리즘의 규칙을 바꾸지 않았다. 대신 RAPTOR가 읽을 **요청 전용 시각표를 만드는 방식**을 바꿨다.

- A(원본): 요청마다 모든 `stop × trip` 도착·출발 시각을 큰 배열 두 개에 미리 복사한다.
- H(변경): 원본 `TripTimes` 참조와 날짜별 offset만 저장하고, 실제 시각은 읽을 때 계산한다.

두 구현이 반환하는 값은 수식상 같다.

```text
A: 미리 저장한 값 = TripTimes의 시각 + service-day offset
H: 조회할 때 계산 = TripTimes의 시각 + service-day offset
```

이번 Baden-Württemberg 관측에서는 다음 결과가 나왔다.

| 지표 | A | H | H 변화 |
| --- | ---: | ---: | ---: |
| RAPTOR Worker 평균 | 77.0 ms | 79.0 ms | +2.6% |
| 전체 요청 Total 평균 | 159.8 ms | 151.0 ms | **-5.5%** |
| `TripPatternFiltering` 평균 | 63.5 ms | 53.8 ms | **-15.4%** |
| 결과 CSV | 동일 | 동일 | 기능 결과 일치 |

첫 샘플을 제외하면 Worker는 A와 H가 모두 77.0ms로 같았고, Total은 H가 7.3% 빨랐다. 따라서 이번 실험에서 확인된 주된 이득은 RAPTOR 검색 속도 향상이 아니라 **RAPTOR 실행 전에 큰 배열을 만들고 채우는 작업의 제거**다.

다만 이 결과는 A 다음 H를 한 번 실행한 관측이다. H에 유리한 강한 신호이지만, PR에 일반화된 성능 수치로 쓰려면 교차 반복 실험이 더 필요하다.

---

## 1. 이 문서에서 구분하는 것

### 1.1 A와 H

| 이름 | 의미 |
| --- | --- |
| A | upstream 원본 구현. `arrivalTimes`, `departureTimes`를 요청마다 미리 만든다. |
| H | H-only 구현. 큰 배열 대신 `TripTimes[]`와 `offsetsByTrip[]`를 저장한다. |

이 문서는 F 또는 H+F 조합을 다루지 않는다. H 자체가 무엇을 바꾸고 어떤 결과를 냈는지만 설명한다.

### 1.2 두 종류의 성능 질문

기존 서울/VPS 실험과 이번 Baden 실험은 서로 다른 질문에 답한다.

| 실험 | 주로 답하는 질문 |
| --- | --- |
| 제한된 heap의 HTTP/VPS 실험 | 메모리 압박과 GC가 있는 실제 서비스에서 더 안정적인가? |
| Baden 공식 SpeedTest | 통제된 엔진 실행에서 어느 단계가 빨라지거나 느려지는가? |

따라서 Baden에서 RSS 차이가 작다는 사실은 제한된 VPS에서의 메모리 효과를 부정하지 않는다. 반대로 VPS에서 RSS가 크게 줄었다는 사실만으로 모든 graph에서 엔진 자체가 빨라진다고 말할 수도 없다.

---

## 2. OTP 프로젝트를 큰 그림으로 보기

OTP는 크게 **graph를 만드는 과정**과 **graph를 사용해 검색하는 과정**으로 나뉜다.

```text
[오프라인 graph build]
OSM 도로망 + GTFS 대중교통 시각표
              ↓
        OTP graph builder
              ↓
           graph.obj

[온라인 route request]
graph.obj 로드
    ↓
출발지·도착지와 검색 조건 수신
    ↓
요청 전용 RAPTOR 데이터 생성
    ↓
Access/Egress 계산
    ↓
RAPTOR 대중교통 검색
    ↓
경로 생성·필터링·응답 렌더링
```

`graph.obj`는 장기간 살아 있는 공용 데이터다. 반면 H가 건드린 `TripPatternForDates`는 검색 요청을 처리하는 동안만 살아 있는 **요청 지역(request-local) 데이터**다.

즉 H는 다음을 바꾸지 않는다.

- OSM 또는 GTFS를 읽는 방식
- `graph.obj`의 구조와 직렬화 형식
- RAPTOR의 라운드 진행 규칙
- 환승, 비용, 도착시각의 우열 판정
- API 요청·응답 형식
- 최종 itinerary 선택 규칙

H가 바꾸는 것은 graph의 시각표를 요청용 RAPTOR 모델로 투영하는 저장 방식 하나다.

---

## 3. 먼저 알아야 하는 GTFS와 OTP 데이터 모델

### 3.1 기본 용어

| 용어 | 뜻 | 예 |
| --- | --- | --- |
| stop | 정류장 또는 역 | Stuttgart Hbf |
| route | 승객에게 보이는 노선 | S1, 42번 버스 |
| trip | 특정 시각에 한 번 운행하는 차편 | S1 08:12 출발편 |
| stop time | 한 trip이 각 stop에 도착·출발하는 시각 | A 08:12, B 08:17 |
| trip pattern | 같은 stop 순서를 통과하는 trip 묶음 | A→B→C를 지나는 모든 상행편 |
| service day | 운행일 기준 날짜 | 새벽 01시는 전날 25:00일 수 있음 |

trip pattern 하나를 표로 보면 다음과 같다.

```text
                   Trip 0   Trip 1   Trip 2
Stop 0 departure    08:00    08:10    08:20
Stop 1 arrival      08:07    08:17    08:27
Stop 1 departure    08:08    08:18    08:28
Stop 2 arrival      08:15    08:25    08:35
```

표의 한 열, 즉 trip 하나의 전체 시각표를 OTP에서는 `TripTimes`가 표현한다.

### 3.2 장기 데이터와 요청 데이터

OTP 내부의 관련 객체를 수명으로 나누면 이해하기 쉽다.

| 수명 | 주요 객체 | 역할 |
| --- | --- | --- |
| graph 수명 | `TripTimes`, `RoutingTripPattern`, `RaptorTransitData` | 모든 요청이 공유하는 원본 시각표와 네트워크 |
| 날짜별 스냅샷 | `TripPatternForDate` | 특정 service day에 실제 운행하는 trip 묶음 |
| 요청 수명 | `RaptorRoutingRequestTransitData`, `TripPatternForDates` | 요청 날짜·필터·실시간 상태를 반영한 RAPTOR 입력 |

H의 핵심은 이미 graph에 존재하는 `TripTimes` 값을 요청 객체에 다시 복사할 필요가 있는지 묻는 것이다.

---

## 4. RAPTOR 알고리즘은 무엇을 하는가

RAPTOR는 대중교통 경로를 환승 횟수별 라운드로 탐색한다.

1. 출발지에서 걸어서 갈 수 있는 stop들의 최초 도착시각을 기록한다.
2. 새로 갱신된 stop을 지나는 trip pattern을 찾는다.
3. 각 pattern에서 내가 도착한 뒤 탑승할 수 있는 첫 trip을 찾는다.
4. 그 trip을 타고 뒤쪽 stop들의 도착시각을 갱신한다.
5. 다음 환승 라운드에서 같은 작업을 반복한다.

pattern을 검색할 때 RAPTOR는 다음 인터페이스를 반복해서 호출한다.

```java
int arrivalTime(int stopPositionInPattern, int tripIndex);
int departureTime(int stopPositionInPattern, int tripIndex);
```

중요한 점은 두 가지다.

- 이 조회는 RAPTOR의 hot path에서 매우 많이 호출된다.
- H는 RAPTOR의 탐색 순서나 판정 규칙은 그대로 두지만, 이 두 메서드가 값을 얻는 방법은 바꾼다.

따라서 “H는 RAPTOR를 전혀 건드리지 않았다”는 표현은 지나치게 단순하다. 정확한 표현은 다음과 같다.

> H는 RAPTOR 알고리즘을 바꾸지 않았지만, RAPTOR가 읽는 요청용 timetable의 구현과 hot-path 조회 방식을 바꿨다.

---

## 5. 요청마다 `TripPatternForDates`가 필요한 이유

graph에 `TripTimes`가 이미 있는데도 요청 전용 객체가 필요한 이유는 요청마다 후보 시각표가 달라지기 때문이다.

### 5.1 여러 service day를 하나의 시간축으로 합치기

자정 주변 검색에서는 전날의 25:00 trip과 오늘의 01:00 trip이 동시에 후보가 될 수 있다. OTP는 여러 날짜의 `TripPatternForDate`를 같은 pattern 기준으로 합친다.

각 날짜를 검색 기준 시간축으로 옮기기 위해 offset을 더한다.

```text
실제 검색 시각 = TripTimes 내부 시각 + service-day offset
```

### 5.2 요청별 필터 적용

요청은 다음 조건을 가질 수 있다.

- 운행하지 않는 날짜 제외
- 금지된 route 또는 trip 제외
- 휠체어 탑승 가능 여부
- 특정 stop에서 탑승·하차 가능 여부
- 실시간 업데이트 사용 여부

### 5.3 요청 시작 시점의 스냅샷 고정

실시간 갱신이 있더라도 한 요청은 자신이 잡은 timetable 스냅샷을 일관되게 읽어야 한다. `TripPatternForDates`는 이 요청에서 사용할 pattern과 trip의 집합 및 순서를 고정한다.

이 작업은 `RaptorRoutingRequestTransitDataCreator.merge(...)`에서 pattern별로 수행되고, 각 pattern마다 `new TripPatternForDates(...)`를 만든다.

---

## 6. A가 하던 일: 모든 시간을 미리 복사

A는 pattern 하나를 만들 때 다음 두 배열을 할당한다.

```java
int[] arrivalTimes   = new int[nStops * numberOfTripSchedules];
int[] departureTimes = new int[nStops * numberOfTripSchedules];
```

그 후 모든 `trip × stop` 조합을 순회한다.

```java
for (int trip = 0; trip < numberOfTripSchedules; trip++) {
  TripTimes tt = ...;
  int offset = ...;

  for (int stop = 0; stop < nStops; stop++) {
    arrivalTimes[stop * numberOfTripSchedules + trip] =
      tt.getArrivalTime(stop) + offset;

    departureTimes[stop * numberOfTripSchedules + trip] =
      tt.getDepartureTime(stop) + offset;
  }
}
```

### 6.1 왜 이런 구조를 사용했는가

이 구조에는 분명한 장점이 있다.

- board search가 stop 하나의 모든 trip을 순서대로 읽을 때 메모리가 연속적이다.
- 조회 시 배열 인덱스 계산만 하면 된다.
- `TripTimes` 객체를 따라가는 간접 참조가 없다.
- offset 덧셈도 생성 시 한 번 끝낸다.

즉 A는 **요청 준비 비용과 메모리를 더 쓰고, 검색 중 조회를 최대한 싸게 만드는 구조**다.

### 6.2 비용은 얼마인가

pattern 하나의 두 배열 payload는 대략 다음과 같다.

```text
2 arrays × stops × trips × 4 bytes
```

stop 50개, trip 300개라면:

```text
2 × 50 × 300 × 4 = 120,000 bytes
```

이 값은 pattern 하나의 배열 payload일 뿐이다. 요청이 수천 개 pattern을 준비하면 복사량과 임시 메모리가 빠르게 커진다.

또한 CPU는 메모리를 할당하는 데서 끝나지 않는다. 각 칸마다 원본 값을 읽고, offset을 더하고, 목적 배열에 써야 한다.

---

## 7. H가 바꾼 일: 값 대신 원본과 offset을 저장

H는 `stops × trips` 배열 두 개를 없애고 trip 수만큼의 배열 두 개를 둔다.

```java
private final TripTimes[] tripTimesByIndex;
private final int[] offsetsByTrip;
```

생성 시에는 각 global trip index에 해당하는 원본과 offset만 기록한다.

```java
tripTimesByIndex[trip] = tripTimes;
offsetsByTrip[trip] = offset;
```

시각은 RAPTOR가 요구할 때 계산한다.

```java
public int arrivalTime(int stop, int trip) {
  return tripTimesByIndex[trip].getArrivalTime(stop)
    + offsetsByTrip[trip];
}

public int departureTime(int stop, int trip) {
  return tripTimesByIndex[trip].getDepartureTime(stop)
    + offsetsByTrip[trip];
}
```

### 7.1 공간 복잡도의 변화

H 전후의 추가 요청 메모리는 다음처럼 볼 수 있다.

```text
A: O(stops × trips)
H: O(trips)
```

H가 `TripTimes` 자체를 새로 만드는 것은 아니다. 기존 graph 객체를 가리키는 참조만 보관한다.

### 7.2 CPU 비용이 이동한다

H는 비용을 완전히 없애는 것이 아니라 위치를 바꾼다.

| 구간 | A | H |
| --- | --- | --- |
| 요청 준비 | 모든 stop×trip을 복사 | trip별 참조와 offset만 저장 |
| RAPTOR 조회 | 연속 배열 직접 조회 | `TripTimes` 간접 참조 후 offset 덧셈 |
| 임시 할당 | 큼 | 작음 |
| cache locality | 유리 | 상대적으로 불리할 수 있음 |

H가 유리한 조건은 다음 부등식으로 생각할 수 있다.

```text
제거한 복사 CPU + 줄어든 GC 비용
>
추가된 조회 간접 참조 비용
```

이번 Baden 실험에서는 왼쪽이 더 컸다.

---

## 8. 왜 A와 H의 검색 결과가 같은가

global trip index `i`가 가리키는 원본을 `tt[i]`, 날짜 offset을 `offset[i]`, stop 위치를 `s`라고 하자.

A가 생성할 때 저장하는 값은 다음과 같다.

```text
A_arrival[s, i] = tt[i].getArrivalTime(s) + offset[i]
```

H가 조회할 때 반환하는 값도 다음과 같다.

```text
H_arrival(s, i) = tt[i].getArrivalTime(s) + offset[i]
```

departure도 같은 관계다. 두 구현이 같은 trip 순서와 같은 offset을 사용한다면 반환값이 같다.

실제로 H는 다음 동작을 그대로 유지한다.

- `createTripTimesForDaysIndex(...)`가 만드는 global trip 순서
- 날짜별 offset 계산
- 탑승·하차 가능 stop 비트셋
- 휠체어 접근성 배열
- frequency 기반 trip 처리 분기
- RAPTOR가 보는 `TripSearchTimetable` 인터페이스

### 8.1 실시간 업데이트와 참조 안전성

H의 안전성은 `TripTimes`가 요청 중 제자리에서 변경되지 않는다는 계약에 의존한다. 실시간 업데이트는 기존 객체를 수정하는 대신 새로운 timetable 객체를 만들어 교체한다. 요청이 생성 시 잡은 `TripTimes` 참조는 그 요청이 끝날 때까지 같은 스냅샷을 가리킨다.

이 불변성 계약이 깨진다면 H의 안전성도 다시 검토해야 한다. 따라서 PR에서는 이 전제를 코드와 테스트로 분명히 설명해야 한다.

---

## 9. H가 실제 요청 흐름의 어디에 들어가는가

온라인 transit 요청의 핵심 순서는 다음과 같다.

```text
TransitRouter.route()
  ↓
RaptorTransitData 선택
  ↓
RaptorRoutingRequestTransitData 생성
  ↓
RaptorRoutingRequestTransitDataCreator.merge(...)
  ↓
pattern마다 TripPatternForDates 생성  ← H 변경 위치
  ↓
finishedPatternFiltering()
  ↓
Access/Egress 계산
  ↓
RAPTOR Worker 실행
  ↓
itinerary 생성·필터링·렌더링
```

`TripPatternFiltering`이라는 이름은 단순히 pattern 몇 개를 제외하는 작업만 뜻하지 않는다. 이 타이머에는 요청용 RAPTOR 데이터 모델을 만들고 여러 날짜의 pattern을 합치는 과정이 포함된다. H의 생성자 비용이 이 구간에 들어가는 이유다.

---

## 10. SpeedTest의 Worker와 Total을 정확히 읽는 법

SpeedTest는 서로 다른 범위를 측정한다.

```text
Total (routing.total)
├─ 요청 준비
├─ TripPatternFiltering       ← H의 큰 배열 생성이 있던 곳
├─ Access/Egress 계산
├─ Worker (routing.raptor)    ← RAPTOR 검색 본체
├─ itinerary 생성·필터링
└─ 렌더링
```

`Worker`는 `DebugTimingAggregator.ROUTING_RAPTOR`, `Total`은 `ROUTING_TOTAL`을 읽는다.

따라서 다음 두 문장은 동시에 참일 수 있다.

- Worker는 A와 H가 같다.
- Total은 H가 더 빠르다.

이 경우 차이는 Worker 밖, 즉 요청 준비나 결과 처리 단계에서 발생한 것이다. 이번 로그에서는 `TripPatternFiltering` 감소가 그 차이를 설명한다.

---

## 11. Baden-Württemberg 실험은 어떻게 진행됐는가

### 11.1 고정된 것

- 같은 Baden-Württemberg `graph.obj`
- 같은 256개 공식 요청 corpus
- 같은 검색 날짜와 SpeedTest 설정
- A와 H 각각 4개 sample
- 동일한 결과 CSV 형식과 비교 기준

같은 graph를 공유하는 이유는 graph build의 차이를 코드 변경 효과로 오인하지 않기 위해서다.

### 11.2 sample 하나의 순서

SpeedTest의 sample은 다음 순서로 실행된다.

```text
명시적 System.gc()
  ↓
작은 JIT warm-up
  ↓
측정 타이머 시작
  ↓
256개 요청 실행
  ↓
Worker·Total·세부 타이머 집계
```

네 sample이 끝난 뒤 JVM 메모리 통계를 기록하기 전에 `System.gc()`가 한 번 더 실행된다. 따라서 A와 H에서 관측된 Full GC 5회는 모두 SpeedTest가 의도적으로 요청한 것이다.

### 11.3 이 설계가 제거하는 것과 남기는 것

제거하거나 줄이는 영향:

- sample 시작 전 남은 garbage 차이
- 초기 JIT 컴파일 영향 일부
- 서로 다른 graph로 인한 결과 차이

여전히 남는 영향:

- A를 먼저, H를 나중에 실행한 순서 효과
- 호스트 온도, 백그라운드 작업, CPU frequency 변동
- sample 1의 추가 JIT 또는 cold-cache 영향
- 한 번의 A/H 실행에서 생기는 우연한 차이

그래서 이번 실행은 원인을 이해하기에는 유용하지만 최종 PR 수치로는 교차 반복이 필요하다.

---

## 12. 성능 결과를 단계별로 해석하기

### 12.1 전체 평균

낮을수록 빠르다.

| 지표 | A | H | H 변화 |
| --- | ---: | ---: | ---: |
| Worker 평균 | 77.0 ms | 79.0 ms | +2.6% |
| Total 평균 | 159.8 ms | 151.0 ms | **-5.5%** |
| `TripPatternFiltering` 평균 | 63.5 ms | 53.8 ms | **-15.4%** |

표면적으로는 H Worker가 2ms 느려 보인다. 그러나 sample별로 보면 첫 번째 H Worker 85ms 하나가 평균을 올렸다.

### 12.2 sample별 결과

| Sample | A Worker | H Worker | A Total | H Total | A PatternFiltering | H PatternFiltering |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 77 | 85 | 160 | 160 | 63 | 55 |
| 2 | 76 | 77 | 158 | 149 | 63 | 54 |
| 3 | 77 | 77 | 161 | 148 | 64 | 53 |
| 4 | 78 | 77 | 160 | 147 | 64 | 53 |

sample 2~4만 보면:

- Worker: A 77.0ms, H 77.0ms
- Total: A 159.7ms, H 148.0ms — H가 7.3% 빠름
- `TripPatternFiltering`: 요청당 평균 약 10.3ms 감소

따라서 이번 관측의 가장 자연스러운 해석은 다음이다.

1. 첫 H sample의 Worker 상승은 아직 남은 JIT/cold 상태의 영향과 일치하지만, 이번 한 번의 실행만으로 원인을 확정할 수는 없다.
2. warm 상태의 RAPTOR Worker는 A와 H가 사실상 같다.
3. H는 매 요청의 `TripPatternForDates` 준비 시간을 반복적으로 줄였다.
4. 이 절감이 Total 감소로 이어졌다.

### 12.3 왜 Total 감소량이 PatternFiltering 감소량과 비슷한가

요청당 `TripPatternFiltering`은 약 9.8ms 줄었고 Total은 첫 sample 제외 시 약 11.7ms 줄었다. 두 크기가 비슷하다.

이는 H의 성능 차이가 RAPTOR의 경로 탐색 규칙이나 결과 필터가 아니라 요청 데이터 준비 단계에서 발생했다는 설명과 일치한다.

---

## 13. GC 결과를 올바르게 읽기

### 13.1 자연 발생 Young GC

전체 프로세스 로그:

| 지표 | A | H | 변화 |
| --- | ---: | ---: | ---: |
| Young GC 횟수 | 68 | 61 | **-10.3%** |
| Young GC 총 정지시간 | 1.613s | 1.485s | **-7.9%** |
| 평균 정지시간 | 23.7ms | 24.3ms | 거의 동일 |
| 최대 정지시간 | 159.7ms | 155.4ms | 거의 동일 |

graph 로딩 이후 sample 구간만 보면:

| 구간 | A Young GC | H Young GC | A 정지시간 | H 정지시간 |
| ---: | ---: | ---: | ---: | ---: |
| Sample 1 | 11 | 9 | 76.5ms | 71.1ms |
| Sample 2 | 10 | 8 | 61.2ms | 47.0ms |
| Sample 3 | 10 | 8 | 88.5ms | 45.8ms |
| Sample 4 | 9 | 8 | 79.3ms | 52.9ms |
| 합계 | 40 | 33 | 305.4ms | 216.7ms |

측정 구간에서 H는 Young GC 횟수를 17.5%, 정지시간을 29.0% 줄였다. 이는 H가 요청 중 생성하는 임시 데이터의 양을 줄였다는 방향과 일치한다.

### 13.2 Full GC

| 지표 | A | H |
| --- | ---: | ---: |
| Full GC 횟수 | 5 | 5 |
| 자연 발생 Full GC | **0** | **0** |
| `System.gc()`에 의한 Full GC | 5 | 5 |
| Full GC 총시간 | 3.023s | 2.963s |

Full GC는 메모리가 부족해서 발생한 것이 아니다. SpeedTest가 sample 전 네 번, 최종 메모리 통계 전 한 번 명시적으로 실행했다.

이 Full GC는 측정 타이머 밖에 있으므로 Worker와 Total 수치에 포함되지 않는다.

### 13.3 Total 향상이 GC 때문인가

주된 원인은 GC 정지시간이 아니다.

- H의 sample 구간 Young GC 정지시간 절약: 전체 약 88.7ms
- H의 `TripPatternFiltering` 절약: 요청당 약 9.8ms, 256건 sample당 약 2.5초
- H의 전체 요청시간 절약: sample당 약 2.2~3.5초

즉 H가 빨라진 주된 이유는 GC가 아니라 **큰 배열을 할당하고 모든 칸을 채우는 CPU 작업을 하지 않은 것**이다. Young GC 감소는 이 설명을 보조하는 증거다.

---

## 14. 메모리 결과를 올바르게 읽기

### 14.1 RSS 결과

| RSS | A | H | H 변화 |
| --- | ---: | ---: | ---: |
| 측정 구간 p50 | 7.415GiB | 7.360GiB | 약 -56MiB |
| 측정 구간 peak | 7.628GiB | 7.546GiB | 약 -84MiB |
| 전체 실행 peak | 7.651GiB | 7.546GiB | 약 -108MiB |

이번 실험에서 RSS 차이는 약 1% 수준이다. 이 값만 보고 H가 메모리를 거의 줄이지 못했다고 결론 내리면 안 된다.

### 14.2 post-GC live set

강제 Full GC 뒤 살아남은 heap은 사실상 동일했다.

```text
A: 약 2.960~2.961GB
H: 약 2.961~2.962GB
```

이는 예상한 결과다. H는 graph나 장기 생존 객체를 없애지 않는다. 요청 중 잠깐 존재하는 큰 배열을 없앤다.

```text
H가 줄이는 것: transient allocation, 배열 채우기, Young GC 부담
H가 줄이지 않는 것: graph.obj의 live data, 장기 생존 timetable
```

### 14.3 임시 할당 감소가 RSS에 작게 보이는 이유

RSS는 현재 살아 있는 Java 객체의 합이 아니다.

```text
RSS = committed Java heap의 상주 페이지
    + Metaspace
    + thread stack
    + JIT code cache
    + GC 자료구조
    + native/off-heap 영역
```

G1은 한 번 확보한 heap region을 객체가 죽었다고 즉시 운영체제에 반환하지 않을 수 있다. 그래서 임시 배열이 사라져도 다음 현상이 가능하다.

- post-GC live set은 동일
- allocation rate와 Young GC는 감소
- 전처리 CPU는 감소
- RSS 차이는 작음

따라서 이번 Baden 실험에서 H의 핵심 메모리 성과는 “영구 메모리 절반”이 아니라 **요청별 임시 할당과 GC 빈도 감소**다.

---

## 15. 이번 실험으로 입증된 것과 입증되지 않은 것

### 15.1 입증된 것

이 실행 범위에서는 다음을 관측했다.

1. A와 H의 결과 CSV가 동일했다.
2. H는 `TripPatternFiltering`을 평균 15.4% 단축했다.
3. 첫 sample 이후 RAPTOR Worker는 A와 H가 같은 수준이었다.
4. H는 Total 평균을 5.5%, 첫 sample 제외 시 7.3% 줄였다.
5. H는 Young GC 횟수와 총 정지시간을 줄였다.
6. 자연 발생 Full GC는 A와 H 모두 없었다.
7. post-GC live set은 동일해 H가 장기 생존 graph 메모리를 줄이는 변경은 아님을 확인했다.

### 15.2 아직 입증되지 않은 것

이번 한 번의 순차 실행만으로는 다음을 주장할 수 없다.

- 모든 graph에서 H가 항상 빠르다.
- H가 RAPTOR Worker 자체를 빠르게 만든다.
- process RSS가 보편적으로 크게 줄어든다.
- 정확히 5.5%가 재현 가능한 일반 성능 수치다.
- 첫 sample의 Worker 차이가 반드시 JIT 하나만의 영향이다.
- 장시간 HTTP 서비스에서도 같은 비율이 유지된다.

정확한 표현은 다음과 같다.

> Baden-Württemberg 공식 corpus의 이번 실행에서 H는 결과를 유지했고, 반복적으로 `TripPatternFiltering`을 줄였으며, Total 개선과 Young GC 감소가 함께 관측됐다.

---

## 16. graph에 따라 결과가 달라질 수 있는 이유

H의 이득과 비용은 서로 다른 데이터 크기에 비례한다.

### H가 제거하는 비용

- 요청에서 포함되는 pattern 수
- pattern별 stop 수
- pattern별 trip 수
- 검색에 포함하는 service day 수
- 동시 요청 수와 allocation pressure

특히 A의 복사 비용은 대략 `Σ(stops × trips)`에 비례한다.

### H가 추가하는 비용

- RAPTOR가 arrival/departure 시각을 조회하는 횟수
- `TripTimes` 객체 간접 참조
- offset 덧셈
- 흩어진 객체 접근에 따른 cache locality 손실 가능성

따라서 단순히 stop 수나 pattern 수 하나만으로 결과를 예측할 수 없다. 중요한 것은 다음 두 양의 균형이다.

```text
요청 준비에서 실제로 복사하는 셀 수
vs.
RAPTOR Worker에서 실제로 조회하는 셀 수
```

Baden에서는 전처리 절감이 조회 오버헤드보다 컸다. 다른 graph에서도 같은지 확인하려면 같은 graph를 공유한 A/H 교차 실험이 필요하다.

---

## 17. PR용 최종 증거를 만드는 다음 실험

현재 결과를 upstream PR의 수치로 승격하려면 다음 조건을 지키는 것이 좋다.

### 17.1 실행 순서

```text
A → H → H → A
```

또는 여러 AB/BA pair를 번갈아 실행한다. 각 실행은 fresh JVM을 사용한다.

### 17.2 고정 조건

- 동일한 `graph.obj`와 SHA-256
- 동일한 JAR 빌드 방식과 JVM 옵션
- 동일한 corpus, profile, sample 수
- 동일한 CPU·memory 제한
- swap 증가 없음
- 백그라운드 부하 기록

### 17.3 주요 판정 지표

| 영역 | 주요 지표 |
| --- | --- |
| 기능 | 결과 CSV 또는 itinerary fingerprint mismatch 0 |
| 전처리 | `routing.tripPatternFiltering` |
| 검색 | `routing.raptor` Worker |
| 전체 | `routing.total` |
| 할당/GC | Young GC 횟수·정지시간, JFR allocation |
| 메모리 | post-GC live heap과 measured-window RSS를 분리 |

### 17.4 주장 방식

PR의 주장은 측정 결과에 맞춰 좁게 잡는다.

- 강한 주장: 요청별 timetable materialization을 제거했다.
- 강한 주장: Baden에서 결과 동일, pattern filtering과 Total 감소가 반복 관측됐다.
- 보조 주장: Young GC 감소가 allocation 감소 방향과 일치한다.
- 하지 않을 주장: 모든 graph에서 항상 빠르고 RSS가 크게 줄어든다.

---

## 18. 코드 리뷰 때 예상되는 질문

### Q1. 왜 원본은 굳이 큰 배열을 만들었나?

RAPTOR hot loop에서 한 stop의 여러 trip 시간을 연속 메모리로 빠르게 읽기 위해서다. A의 설계도 합리적인 최적화이며, H는 준비 비용과 메모리가 더 중요한 경우 다른 균형점을 제안한다.

### Q2. H가 실제로 같은 값을 돌려준다는 근거는?

A가 생성 시 계산해 저장하던 `TripTimes 값 + offset`을 H가 조회 시 그대로 계산한다. global trip 순서와 offset 생성 로직은 바꾸지 않았다. Baden 결과 CSV도 동일했다.

### Q3. 실시간 업데이트 중 값이 바뀌지 않나?

요청은 생성 시 잡은 immutable `TripTimes` 스냅샷을 참조한다. 실시간 갱신은 기존 객체를 수정하지 않고 새 객체를 교체한다는 계약에 의존한다.

### Q4. 조회가 느려질 가능성은?

있다. 객체 간접 참조와 offset 덧셈이 hot path로 이동한다. 이번 Baden의 warm sample에서는 Worker 차이가 없었지만 다른 graph에서는 별도 검증해야 한다.

### Q5. RSS가 거의 같은데 메모리 최적화라고 할 수 있나?

H가 줄이는 것은 live graph가 아니라 transient allocation이다. 그래서 allocation/Young GC/전처리 시간은 줄어도 큰 heap에서 RSS는 비슷할 수 있다. “영구 메모리 감소”가 아니라 “요청별 materialization 제거”라고 표현하는 것이 정확하다.

### Q6. Full GC가 5회나 발생했는데 메모리 압박 아닌가?

아니다. SpeedTest의 명시적 `System.gc()`가 만든 것이며 측정 타이머 밖이다. 자연 발생 Full GC는 둘 다 0회였다.

### Q7. 5.5%를 PR 제목에 써도 되나?

현재는 이 실행의 관측값으로만 쓸 수 있다. AB/BA 교차 반복과 통계 구간을 확보한 뒤 대표 수치로 승격해야 한다.

---

## 19. 30초 설명

> OTP는 요청 날짜와 필터에 맞춘 RAPTOR 시각표를 만들 때 pattern마다 모든 stop×trip의 도착·출발 시각을 큰 배열 두 개에 복사합니다. H는 원본 `TripTimes`가 요청 동안 안정적인 스냅샷이라는 점을 이용해, 그 배열 대신 trip별 원본 참조와 service-day offset만 저장하고 시각을 읽을 때 계산하도록 바꿨습니다. RAPTOR의 경로 탐색 규칙은 그대로이고 데이터 접근 방식만 달라집니다. Baden-Württemberg 공식 corpus에서는 결과 CSV가 동일했고, warm 상태의 Worker는 같았으며, 요청 전처리인 `TripPatternFiltering`이 약 15%, Total이 약 5.5% 줄었습니다. Young GC도 감소했지만 post-GC live set은 같아서, 이 변경의 본질은 영구 graph 메모리 감소가 아니라 요청별 시각표 materialization 제거입니다.

---

## 20. 자가 점검 질문

다음 질문에 답할 수 있으면 H 변경의 전체 흐름을 이해한 것이다.

1. `graph.obj`와 요청 지역 RAPTOR 데이터는 수명이 어떻게 다른가?
2. stop, trip, trip pattern, `TripTimes`의 관계는 무엇인가?
3. RAPTOR의 한 라운드는 무엇을 의미하는가?
4. 요청마다 `TripPatternForDates`가 필요한 이유는 무엇인가?
5. service-day offset은 왜 필요한가?
6. A는 pattern 하나에 어떤 배열을 만들었는가?
7. A 배열의 공간 복잡도와 H 배열의 공간 복잡도는 어떻게 다른가?
8. H가 RAPTOR 알고리즘은 바꾸지 않았지만 hot path에는 영향을 준다는 말의 뜻은?
9. A와 H가 같은 시각을 반환한다는 것을 수식으로 설명할 수 있는가?
10. `TripPatternFiltering`, Worker, Total의 측정 범위는 어떻게 다른가?
11. 첫 sample을 제외했을 때 Worker가 같은데 Total이 줄어든 이유는?
12. Young GC 감소가 Total 개선의 주원인이 아닌 이유는?
13. Full GC 5회가 메모리 압박 증거가 아닌 이유는?
14. post-GC live set이 같은데도 H가 의미 있는 이유는?
15. 이 결과를 모든 graph에 일반화하면 안 되는 이유는?
16. PR용 확정 수치를 위해 왜 AB/BA 교차 반복이 필요한가?

---

## 21. 관련 코드와 문서

OTP 코드 좌표:

- `application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/request/TripPatternForDates.java`
- `application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/request/RaptorRoutingRequestTransitDataCreator.java`
- `application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/router/TransitRouter.java`
- `application/src/main/java/org/opentripplanner/routing/framework/DebugTimingAggregator.java`
- `application/src/test/java/org/opentripplanner/transit/speed_test/SpeedTest.java`

배경 문서:

- [기존 OTP 최적화 이해 문서](UNDERSTANDING.md)
- [H-only 기여 계획](handoffs/H-ONLY-CONTRIBUTION-PLAN.md)
- [H-only PR 초안](handoffs/H-ONLY-PR-DRAFT.md)
- [PR 근거 정리](handoffs/PR-EVIDENCE.md)

---

## 22. 최종 기억 문장

> H는 RAPTOR를 새 알고리즘으로 바꾼 것이 아니다. 요청마다 미리 복사하던 `stop × trip` 시각표를 원본 참조와 offset으로 표현해, 같은 값을 더 적은 materialization 비용으로 제공하도록 바꾼 것이다. Baden에서는 그 효과가 RAPTOR Worker가 아니라 `TripPatternFiltering`과 Total에서 나타났다.
