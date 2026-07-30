# 포트폴리오 API 운영 실측

## 결론

로컬 Mock/Testcontainers 결과를 그대로 운영 수치처럼 바꾸지 않고, 현재 포트폴리오의 API 항목 중
실제 공개 요청으로 직접 검증 가능한 두 주장만 VPS에서 재측정했다.

| 포트폴리오 항목 | 실제 VPS 측정 | 판정 |
|---|---|---|
| 동일 키 요청의 중복 provider 호출 방지 | 동일 키 100건 동시 요청, 외부 호출 owner 1회 | 검증 |
| TTL 15초의 provider 호출 감소 | 20초·250ms 간격 81건, 외부 호출 owner 2회 | 검증 |

외부 API 지연·무응답 주입과 TMAP bulkhead 포화, 알림 200/10,000건 생성은 이번 측정에서 제외했다.

## 환경

| 항목 | 값 |
|---|---|
| 측정 시각 | 2026-07-29 18:38~18:40 KST |
| 요청 경로 | 로컬 Mac k6 → 공개 HTTPS/Nginx → VPS API |
| 대상 | `https://api.refit-100.site` |
| 배포 Git SHA | `778ed9711d0d945aa45a8cfdceeccf718966a56e` |
| k6 | `grafana/k6:2.1.0` |
| 대상 키 | 서울역서부 `101000013` / 262번 `100100044` / 경유순번 `83` |
| 서버 계측 | Spring Actuator Prometheus counter |

요청 결과는 k6에서 검증했고, 실제 provider 호출 여부는 다음 서버 카운터의 실행 전후 차이로 확인했다.

- `singleflight_requests_total{provider="bus",type="arrival",role=...}`
- `bus_realtime_cache_requests_total{type="arrival",result=...}`

## 1. 동일 키 100건 동시 요청

실행 전 대상 Redis 키가 존재하지 않는 것을 확인한 뒤, 100 VU가 같은 공개 API를 각각 한 번 호출했다.
setup health check 1건은 아래 endpoint 집계에서 제외했다.

### k6 결과

| 항목 | 결과 |
|---|---:|
| 실제 버스 도착 요청 | 100건 |
| 200 + 동일 노선의 비어 있지 않은 JSON | 100건 |
| 오류 | 0건 |
| 평균 | 192.65ms |
| p95 | 231.36ms |
| p99 | 277.13ms |
| 최대 | 315.12ms |

### 서버 카운터 변화량

| 처리 경로 | 실행 전 | 실행 후 | 증가 |
|---|---:|---:|---:|
| cache miss | 1 | 11 | 10 |
| Single-flight owner | 1 | 2 | **1** |
| Single-flight joiner | 0 | 9 | 9 |
| cache hit | 0 | 90 | 90 |

공개망·Nginx·API 스레드 스케줄링을 통과한 실제 요청에서는 100건이 모두 동시에 cache miss가 되지 않았다.
먼저 도착한 10건 중 1건만 provider를 호출하고 9건이 그 결과에 합류했으며, 뒤따른 90건은 즉시
생성된 캐시를 사용했다. 결과적으로 **동일 키 실제 요청 100건에 provider 호출은 1회**였다.

따라서 운영 실측 문구에서는 로컬 실험의 "100건 동시 miss" 대신 "동일 키 100건 동시 요청"이라고
표현해야 한다.

## 2. TTL 15초, 20초 반복 조회

대상 Redis 키가 다시 만료된 것을 확인한 뒤, 같은 공개 API를 20초 동안 250ms 간격으로 호출했다.
k6의 도착률 경계 실행을 포함해 endpoint 요청은 총 81건이었다.

### k6 결과

| 항목 | 결과 |
|---|---:|
| 실제 버스 도착 요청 | 81건 |
| 200 + 동일 노선의 비어 있지 않은 JSON | 81건 |
| 오류 | 0건 |
| 평균 | 69.69ms |
| p95 | 132.70ms |
| p99 | 170.79ms |
| 최대 | 213.70ms |

### 서버 카운터 변화량

| 처리 경로 | 실행 전 | 실행 후 | 증가 |
|---|---:|---:|---:|
| cache hit | 90 | 169 | **79** |
| cache miss | 11 | 13 | 2 |
| Single-flight owner | 2 | 4 | **2** |
| Single-flight joiner | 9 | 9 | 0 |

실제 요청 81건 중 79건(97.5%)은 cache hit였고 provider 호출 owner는 2건이었다.
운영 캐시를 끄는 대조군은 실행하지 않았으므로 "81회 → 2회"의 앞 수치를 직접 측정값으로 쓰지는 않는다.
포트폴리오에는 **20초 실요청 81건에서 실제 provider 호출 2회**라고 기록하는 것이 정확하다.

## 포트폴리오 대체 문구

> 실제 VPS 공개 API에 k6로 동일 버스 키 100건을 동시에 요청해 전 건 200 응답을 확인했습니다.
> Prometheus 카운터 기준 최초 cache miss 경합은 Single-flight owner 1건·joiner 9건으로 병합되고,
> 뒤따른 90건은 cache hit로 처리되어 실제 외부 provider 호출은 1회였습니다
> (응답 p95 231ms, p99 277ms).
>
> 같은 키를 20초 동안 250ms 간격으로 조회한 운영 TTL 15초 실험에서는 실제 요청 81건 중
> cache hit 79건, provider 호출 2건을 확인했습니다(응답 p95 133ms, 오류 0건).

## PDF 수정 지시서

대상은 현재 포트폴리오 **2페이지**, `2. 외부 API 지연 격리 및 경로 검색 응답시간 단축`이다.
1페이지 Worker 성능 수치는 아직 로컬 통합 테스트 결과이므로 이번 운영 API 측정으로 교체하지 않는다.

### 1. 문제 및 원인 - 세 번째 문장

기존:

> Redis 캐시 만료 직후 동일한 버스 정보를 조회한 동시 요청 100건이 모두 Cache Miss로 처리되어
> 외부 버스 API 100회 호출

수정:

> 개선 전 로컬 재현에서 Redis 캐시 만료 직후 동일 키 100건을 동시에 실행하자 전부 Cache Miss로
> 처리되어 외부 버스 API가 100회 호출됨

`개선 전 로컬 재현`을 명시해 운영 장애처럼 읽히지 않게 한다.

### 2. 결과 - 두 번째 문장 교체

기존:

> 동일 키 동시 요청 100건, 외부 API Mock 지연 200ms 조건에서 외부 API 호출 100회 → 1회

교체:

> [운영 실측] 실제 VPS 공개 API에 동일 버스 키 100건을 동시에 요청해 전 건 200 응답을 확인하고,
> Prometheus 기준 provider 호출 1회(owner 1·joiner 9·cache hit 90), 응답 p95 231ms·p99 277ms 기록

운영 요청에서는 처음 10건만 cache miss 경합에 들어가고 나머지 90건은 즉시 생성된 캐시를 사용했다.
따라서 `100건 동시 miss`가 아니라 `동일 키 100건 동시 요청`이라고 표현한다.

### 3. 결과 - 세 번째 문장 교체

기존:

> 동일 키를 20초 동안 250ms 간격으로 조회한 TTL 15초 실험에서 외부 API 호출 79회 → 2회로 감소

교체:

> [운영 실측] 실제 VPS 공개 API에서 동일 키를 20초 동안 250ms 간격으로 조회해 요청 81건 중
> cache hit 79건·provider 호출 2건, 응답 p95 133ms·오류 0건 확인

운영 캐시를 끈 대조군은 실행하지 않았으므로 `81회 → 2회`라고 쓰지 않는다.

### 4. 결과 - 첫 번째·네 번째 문장

TMAP 5초 지연/ODsay 무응답과 TMAP 전용 풀 12건 실험은 이번 실측으로 대체하지 않는다.
두 문장 앞에는 `[로컬 제어 실험]`을 붙여 측정 환경을 구분한다.

첫 번째 문장은 동일 품질의 속도 개선으로 오해되지 않도록 아래처럼 표현한다.

> [로컬 제어 실험] TMAP 5초 지연 시 도보 상세를 제외한 기본 경로로 전환해 응답시간을
> 5,197ms → 438ms로 제한하고, ODSAY 무응답은 6,934ms → 805ms 내 504 응답으로 종료

네 번째 문장:

> [로컬 제어 실험] 전용 풀 2·대기열 2·TMAP Mock 1,000ms 조건에서 동시 요청 12건 중
> 4건만 실행하고 8건의 보강을 즉시 거절하되 기본 경로 12건은 모두 반환

### 5. 다이어그램 수정

현재 두 다이어그램의 `Spring Boot Application` 단일 박스는 배포 구조와 다르다.

- 1페이지 Worker 다이어그램: `API Container`와 `Worker Container`를 분리하고,
  Polling Scheduler·Batch Enqueue·Relay Loop·Worker Pool은 Worker 쪽에 둔다.
- 2페이지 경로 검색 다이어그램: 상단 박스를 `API Container`로 변경한다.
- 관측 근거를 드러내려면 API·Worker 옆에 `Prometheus / Grafana`를 두고 두 컨테이너을 각각
  scrape한다고 표시한다.

### 6. 페이지 하단에 넣을 측정 환경 한 줄

> 측정 환경: 장애·포화 조건은 Apple M3 Pro·MockWebServer·Testcontainers 기반 로컬 제어 실험,
> 캐시·Single-flight는 k6 2.1.0 → 공개 HTTPS → VPS API 실요청과 Prometheus 카운터로 검증

이 문장이 길면 `결과`의 각 항목에 붙인 `[로컬 제어 실험]`, `[운영 실측]` 표지만 유지한다.

## 원자료

- `20260729T093858Z-portfolio-stampede-stampede.summary.json`
- `20260729T093858Z-portfolio-stampede-stampede.console.log`
- `20260729T093858Z-portfolio-stampede-stampede.meta.txt`
- `20260729T093934Z-portfolio-ttl-ttl.summary.json`
- `20260729T093934Z-portfolio-ttl-ttl.console.log`
- `20260729T093934Z-portfolio-ttl-ttl.meta.txt`

원자료는 로컬 결과 디렉터리에 보존하며, 요약 문서만 Git 추적 대상으로 둔다.
