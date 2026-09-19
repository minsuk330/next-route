# H-only 후속 설계 — Lazy Stop-Column

작성일: 2026-09-16  
상태: **설계 방향 기록만 완료. 구현·검증 미실행.**

## 1. 목적

현재 H는 `TripPatternForDates` 생성 시 만들던 두 개의
`nStops × numberOfTripSchedules` primitive 시간 배열을 제거하여 allocation과
heap 확장을 줄인다. 대신 RAPTOR hot path의 시간 조회마다 `TripTimes` 객체를 따라가
arrival/departure를 읽고 날짜 offset을 더한다.

관측 결과는 workload에 따라 이 교환관계가 달라짐을 보여준다.

- 서울 VPS `-Xmx3g`: H는 A보다 Young GC·GC 시간·used/committed/RSS가 낮고 지연도
  개선됐다. A/H 모두 Full GC 0인 조건에서도 개선 방향이 남았다.
- Helsinki 408-case VPS amd64 `-Xmx3g`: H는 A보다 지연이 약 9% 느렸고 모든 주요
  percentile에서 열세였다. post-GC live set은 A/H 약 895/894MB로 같고 GC 총시간도
  약 1.9초로 같았다. RSS는 약 1.90/1.79GB로 H가 소폭 낮았다.
- Helsinki Mac Docker의 5경로 × 12회에서는 A/H 지연과 post-GC live set이 사실상
  같았다. 기존 Helsinki 408-case Mac Docker에서도 H의 중앙 지연 열세 방향이
  관측됐다.

따라서 현재 H를 보편적 속도 최적화로 제안하지 않는다. 큰 초기 배열의 제거 효과는
유지하되, 반복 시간 조회의 간접 접근 비용을 줄이는 H2가 필요하다.

## 2. 현재 A와 H의 비용 구조

### A

생성 시 모든 stop/trip 조합의 시간을 계산한다.

```java
arrivalTimes[stop * numberOfTripSchedules + trip]
departureTimes[stop * numberOfTripSchedules + trip]
```

- 생성·할당 비용: `O(nStops × nTrips)`
- 조회 비용: stop-major primitive 배열 1회 접근
- 장점: RAPTOR가 한 stop에서 여러 trip을 스캔할 때 locality가 좋다.
- 단점: 요청마다 큰 두 배열을 생성·초기화한다.

### 현재 H

```java
tripTimesByIndex[trip].getArrivalTime(stop) + offsetsByTrip[trip]
tripTimesByIndex[trip].getDepartureTime(stop) + offsetsByTrip[trip]
```

- 생성·할당 비용: `O(nTrips)`
- 조회 비용: 배열에서 객체 참조 읽기, `TripTimes` 구현 접근, 내부 시간 배열 읽기,
  별도 offset 읽기와 덧셈
- 장점: 큰 stop×trip 배열과 초기 복사를 제거한다.
- 단점: RAPTOR inner loop의 시간 조회마다 간접 접근을 반복하고 A의 stop-major
  locality를 잃는다.

여기서 “순회 오버헤드”는 더 많은 stop이나 trip을 돈다는 뜻이 아니다. 같은 검색에서
`arrivalTime`/`departureTime` 한 번당 수행되는 간접 조회 비용을 뜻한다. 이 비용이
Helsinki 회귀의 원인이라는 해석은 강한 가설이지만 JFR/전용 계측으로 아직 확정하지
않았다.

## 3. 제안: 실제 검색 stop만 lazy materialization

실제 stop은 다음 search 진입점에서 이미 `stopPositionInPattern`으로 전달된다.

- `TripScheduleBoardSearch.search(...)`
- `TripScheduleAlightSearch.search(...)`

따라서 search 시작 시 해당 stop의 시간을 연속 primitive 배열로 요청하고, 이후 binary
search와 linear scan은 그 배열만 읽도록 한다.

개념적 API:

```java
int[] departureTimesAtStop(int stopPositionInPattern);
int[] arrivalTimesAtStop(int stopPositionInPattern);
```

개념적 생성 흐름:

```java
int[] departureTimesAtStop(int stop) {
  var times = departureTimesByStop[stop];
  if (times == null) {
    times = new int[numberOfTripSchedules];
    for (int trip = 0; trip < numberOfTripSchedules; ++trip) {
      times[trip] =
        tripTimesByIndex[trip].getDepartureTime(stop) + offsetsByTrip[trip];
    }
    departureTimesByStop[stop] = times;
  }
  return times;
}
```

처리 위치의 책임은 다음처럼 나눈다.

1. RAPTOR search 객체는 `stopPositionInPattern`을 알고 있으므로 어떤 stop row가 필요한지
   결정한다.
2. `TripPatternForDates`는 `TripTimes`와 offset을 소유하므로 해당 row를 생성한다.
3. board search는 departure row, reverse/alight search는 arrival row를 사용한다.
4. 후보 trip이 선택된 뒤의 드문 개별 조회는 현재 H accessor를 유지하거나 별도로
   최적화한다.

정확한 public/internal API는 구현 전에 기존 SPI 경계와 테스트 대역을 검토해 결정한다.
위 코드는 설계 설명이며 그대로 적용할 패치가 아니다.

## 4. 캐시 정책 후보

### A. 방문한 모든 stop row 보관

- 같은 stop 재검색은 가장 빠르다.
- 실제 접근한 stop만 생성한다.
- 최악의 경우 모든 stop의 arrival/departure row가 만들어져 원래 A와 같은
  `O(nStops × nTrips)` 메모리로 돌아갈 수 있다.

### B. 최근 stop 1~2개만 보관

- 추가 메모리를 `O(nTrips)`로 제한한다.
- stop 재방문 패턴에 따라 row를 다시 채우는 CPU 비용이 발생할 수 있다.

### C. 크기 threshold와 결합

- `nStops × nTrips`가 작은 pattern은 A의 eager 배열을 사용한다.
- 큰 pattern은 H 또는 lazy row 방식을 사용한다.
- threshold는 도시 이름이 아니라 pattern 크기와 실측 비용을 기준으로 결정한다.
- magic number를 넣기 전에 Helsinki/서울 양쪽에서 threshold sweep이 필요하다.

첫 프로토타입은 A 정책으로 실제 materialized stop 수와 byte를 계측한다. 대부분의
pattern에서 일부 stop만 만들어지면 A를 유지하고, 거의 모든 stop이 만들어지면 B 또는
C로 전환한다.

## 5. 구현 전 계측

다음 값을 A/H에서 같은 OTP 버전·graph·corpus로 수집한다.

- `arrivalTime`/`departureTime` 호출 횟수와 접근당 CPU 비용
- pattern별 `nStops`, `nTrips`, `nStops × nTrips`
- 검색당 unique stop row 수와 동일 row 재접근 횟수
- lazy 적용 시 생성된 row 수·byte와 cache hit rate
- `TripTimes` 구현별 접근 비율: scheduled vs realtime
- JFR CPU·allocation, Young/Full GC, GCT, used/committed/RSS

JFR `ExecutionSample`에 accessor frame이 적다는 이유만으로 비용이 없다고 판단하지 않는다.
JIT 인라인 후 caller stack에 비용이 포함될 수 있으므로 전용 counter나 microbenchmark를
병행한다.

## 6. 정확성·안전성 요구사항

- A/H/H2의 전체 응답 fingerprint가 일치해야 한다.
- forward board와 reverse alight를 모두 검증한다.
- service-day 양수/음수 offset, 자정 이후 시간, overlapping days를 검증한다.
- scheduled와 realtime snapshot을 모두 검증한다.
- frequency-based pattern의 기존 custom search를 훼손하지 않는다.
- lazy row가 공유될 수 있는 객체에 저장된다면 thread-safety와 safe publication을
  확인한다. 객체가 request-local이라는 가정을 코드 수명주기로 증명하기 전에는
  unsynchronized cache를 적용하지 않는다.
- 캐시가 live graph나 realtime snapshot보다 오래 살아 stale time을 반환하지 않도록 한다.

## 7. 검증 계획

1. 동일한 upstream SHA에서 A, 현재 H, H2를 각각 분리한다.
2. 단위·영향 모듈 테스트와 전체 fingerprint correctness를 통과시킨다.
3. Helsinki 408-case에서 A/H/H2를 `A-H2-H2-A` 등 교차 순서로 여러 fresh JVM에서
   실행한다.
4. 서울 workload에서도 같은 OTP 버전과 같은 실행 계약으로 반복한다.
5. no-pressure 조건과 제한 heap 조건을 구분하고, 측정 중 명시적 GC를 호출하지 않는다.
6. 측정 종료 후 별도 post-GC live set을 수집해 retention과 transient garbage를 구분한다.

초기 성공 기준은 다음과 같다.

- correctness mismatch 0
- Helsinki 중앙·tail 지연이 A 대비 ±2% 안팎으로 회복
- 서울에서 현재 H의 allocation·GC·committed/RSS 절감 방향 유지
- 어느 graph에서도 통계적으로 뚜렷한 지연 회귀가 없어야 함
- 모든 성능 결론은 반복 JVM run의 paired/ABBA 결과로 판단

## 8. 현재 판단

현재 H는 큰 request-local 배열 할당을 제거한다는 점은 입증됐지만, Helsinki 408-case에서
지연 회귀 위험이 관측됐다. 따라서 현 상태 그대로 범용 성능 개선 PR을 제안하지 않는다.
우선 accessor 비용을 직접 계측하고 lazy stop-column H2를 검증한다. 캐시가 결국 모든
stop을 materialize하거나 회귀를 회복하지 못하면 pattern-size threshold 또는 직접
primitive view 설계를 후속 후보로 검토한다.

관련 과거 진단: [ALLOCATION-LOOKUP-PROFILE.md](ALLOCATION-LOOKUP-PROFILE.md)

