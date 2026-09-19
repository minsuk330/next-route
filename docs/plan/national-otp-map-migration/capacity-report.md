# OTP 용량 보고서 — 전국 비교 실험과 수도권 운영 기준 (P0-B 산출물)

> 이 문서의 전국 수치는 용량·대안 엔진을 확인하기 위한 역사적 비교 실험이다. 현재 운영 전환 범위는 수도권이며, 전국 graph를 운영 산출물이나 배포 목표로 사용하지 않는다.

작성일: 2026-09-07. 결론: **전국 통짜 OTP 는 단일 검색은 serve 가능하지만, 4GiB heap에서 동시 4건은 지연·GC 기준으로 serve 불가다.**

## 결론 한 줄
전국 GTFS(202503) + 전국 OSM 을 하나의 OTP 2.9.0 graph 로 만들고, 3.5GiB heap에서 단일 검색까지는 된다. 다만 이는 **동시 검색 용량을 뜻하지 않는다.** 같은 graph에서 동시 2건은 3.5GiB에서 90초 timeout, heap 4GiB에서 동시 4건은 모두 HTTP 200이지만 20.7~44.8초 및 Full GC 반복이 발생했다. 따라서 현 시점에는 `serve ≤4G` 또는 `VPS 8G에서 동시 serve 가능`을 결론으로 쓸 수 없다.

## 입력
| 입력 | 규모 |
|---|---|
| OSM `south-korea-260903.osm.pbf` | 273M, 전국 |
| GTFS `202503_GTFS_DataSet` 정규화본 | routes 26,363 / trips 325,889 / stop_times 18,888,182 / stops 215,396 / transfers 342 (항공 36노선 제외) |
| OTP | `opentripplanner/opentripplanner:2.9.0` (이미지 내 Java 25) |

## 결과 graph
|V|=3,545,880 |E|=8,917,468, Stops 215,396, Patterns 31,112. graph.obj **797M**.

## serve 실측 (docker, `-XX:+UseStringDeduplication`)
| heap | RSS idle | 검색 4건 | RSS 후 | 판정 |
|---|---|---|---|---|
| 2.5G | — | — | — | 로드 중 사망 |
| 3.0G | 3.40G | 전부 OOM | — | 로드만 됨, 서빙 불가 |
| 3.5G | 3.82G | 순차 4건 성공 | 3.87G | 단일·순차 검색만 확인 |
| 4.0G | 4.35G | 순차 4건 성공 | 4.45G | 단일·순차 검색만 확인 |

검색 4건: 서울시청→부산역(KTX, mode=RAIL), 청계천→사당(버스+4호선), 대구→광주, 인천→춘천. 기동 ~70초.

## 동시 검색 재측정 (2026-09-07)

동일한 797MiB graph, 같은 출발 시각(`2026-09-07T17:00:00+09:00`), `planConnection(first: 3)`을 barrier로 동시에 시작했다. 메모리는 `docker stats`의 컨테이너 RSS라 JVM heap과 같지 않다. 각 동시성은 **같은 4GiB JVM을 이어서 사용**했으므로, 아래 peak는 독립 cold-run peak가 아니라 실제 연속 운영 중의 관측값이다.

| JVM heap | 동시 검색 | 응답 | 지연 (min / max / mean) | RSS peak | GC 판정 |
|---|---:|---|---|---:|---|
| 3.5GiB | 2 | 0/2 성공 (두 요청 90초 timeout) | 90.0s / 90.0s / 90.0s | 3.877GiB | Full GC 반복, heap 포화 |
| 4.0GiB | 1 | 1/1, itinerary 3건 | 4.62s / 4.62s / 4.62s | 4.401GiB | 정상 완료 |
| 4.0GiB | 2 | 2/2, itinerary 각 3건 | 4.49s / 6.01s / 5.25s | 4.463GiB | 정상 완료 |
| 4.0GiB | 4 | 4/4 HTTP 200; 1건 itinerary 0건 | 20.66s / 44.85s / 36.99s | 4.496GiB | Full GC 41회, 총 정지 38.83s, 최대 1.84s |

4GiB·동시 4건은 응답 코드만 보면 성공이지만, Full GC 정지 시간이 사실상 요청 시간을 지배하고 청계천→사당 결과가 빈 itinerary가 됐다. 그러므로 **성공으로 분류하지 않는다.** 다음 결정은 메모리를 줄이는 것이 아니라, 먼저 VPS 실제 heap(예: 5/6GiB)과 in-flight 상한별로 같은 측정을 반복해 안전한 동시성 한계를 정하는 것이다.

## 명시적 `-Xmx` 제거 실험 (2026-09-07)

Docker memory limit을 두지 않고 같은 graph에서 JVM heap 설정만 비교했다. Java 25는 `-Xmx`가 없어도 기본 `MaxRAMPercentage=25%`를 적용하므로, 이것은 완전 무제한 heap이 아니다.

| 조건 | JVM heap 상한 | 기동 RSS | 동시성 | 결과 지연 | 검색 후 RSS | 판정 |
|---|---:|---:|---:|---|---:|---|
| `-Xmx` 없음, JVM 기본값 | 3.47GiB | 3.795GiB | 1 | 8.75s | 3.845GiB | 정상 |
| 동일 | 3.47GiB | — | 2 | 54.0~56.2s | 3.886GiB | 매우 느림 |
| 동일 | 3.47GiB | — | 4 | 26.4~90.0s | 3.921GiB | 1건 timeout, 2건 itinerary 0건 |
| `MaxRAMPercentage=75` | 10.66GiB | 8.057GiB | 1 | 9.28s | 8.483GiB | 정상 |
| 동일 | 10.66GiB | — | 2 | 5.90~8.56s | 8.884GiB | 정상 |
| 동일 | 10.66GiB | — | 4 | 12.6~21.5s | 9.393GiB | 4/4 정상 |

heap 75% 조건의 cgroup peak는 약 **10.18GiB**였다. 따라서 전국 graph를 메모리 제한 없이 운영하면 동시 4건 품질은 개선되지만, GraphHopper의 5.4GiB RSS보다 훨씬 크고 VPS 8GiB에서는 실행할 수 없다. `-Xmx`를 제거하는 것만으로는 heap 상한이 늘지 않으며, 실제 운영값은 JVM heap·native memory·동시 요청 수를 함께 정해야 한다.

## 기존 수도권 graph 재측정 (2026-09-07)

이전에 생성한 `tools/transit-routing-benchmark/data/otp/graph.obj`(217MiB)를 별도 컨테이너로 로드했다. graph 내부 규모는 `|V|=720,338`, `|E|=1,889,812`, Stops 13,994, Patterns 1,254이다. Docker memory limit은 없고 Java 기본 heap 상한(25%, 약 3.47GiB)을 사용했다.

청계천→사당 PT 검색 결과:

| 동시 검색 | 지연 | itinerary | RSS |
|---:|---|---:|---:|
| 기동 직후 | — | — | 2.227GiB |
| 1건 | 3.16s | 3 | 2.476GiB |
| 2건 | 3.21~3.22s | 각 3 | 3.064GiB |
| 4건 | 2.80~2.83s | 각 3 | 3.241GiB |

수도권 graph는 동시 4건까지 모두 정상 응답했고, 전국 graph(동일 기본 heap에서 동시 4건 처리 불가)보다 RSS와 지연이 크게 낮다. 다만 이 graph는 수도권 입력만 포함하므로 전국 서비스의 대안 graph로 사용할 수는 없다.

## 왜 처음엔 8G 로도 안 떴나 (진범)
raptor 가 **패턴 × 서비스날짜** 마다 객체를 만든다. KTDB calendar 는 2017~2030 매일이고 OTP 기본 서비스창은 -1년~+3년(약 1,460일) → 31,112 × 1,460 ≈ 4,500만 객체. `transitServiceStart/End` 로 창을 2개월로 줄이자 4G 안에 들어왔다. KTDB 는 날짜별 시간표 변화가 없어서(평일 1일 스냅샷) 정보 손실은 없다.

## 반드시 지킬 build-config / otp-config
`tools/transit-routing-benchmark/config/otp-national/` 에 보존.
- build: `transitServiceStart:"-P1D"`, `transitServiceEnd:"P2M"`, `maxTransferDuration:"PT10M"`, `transferRequests:[{modes:"WALK"}]`, `staticParkAndRide:false`, `staticBikeParkAndRide:false`
- serve: `OnDemandRaptorTransfer:true`, 미사용 feature off(Flex/DataOverlay/CarPooling/Emission/EmpiricalDelay/DebugUi/ReportApi/Ojp/Trias/Sandbox*/Transmodel/TransferAnalyzer)
- JVM: `-Xmx3500m -XX:+UseStringDeduplication`

## 운영 계약
- 서비스창이 2개월이므로 **graph 를 최소 월 1회 로컬에서 다시 만들어 VPS 에 올린다.** 창을 넘긴 날짜 검색은 무경로.
- build 는 로컬(Mac 18G, Docker VM 14G, `-Xmx11g`, 약 14분). VPS 는 build 하지 않고 graph.obj(797M)만 로드.
- VPS 여유 5.1G 중 3.9G 사용, 헤드룸 약 1.2G. **blue/green 동시 로딩은 불가.** 교체는 구 컨테이너 정지 → 신 기동(약 70초 공백) 또는 그 사이 ODsay 임시 폴백.

## 데이터 함정 (설명서 202503 기준)
- route_type 이 국제표준과 다름: 0 시내/마을/농어촌버스→3, 1 도시철도→1, 2 해운→4, 3 시외버스→3, 4 일반철도→2, 5 공항리무진→3, 6 고속철도→2, **7 항공→제외**. `prepare_gtfs.py` 가 처리.
- calendar 단일 서비스, 전요일. 요일/휴일 구분 없음. calendar_dates·shapes 없음. transfers 는 철도 역간만(버스 환승은 OTP 도보로).
- stop_times 가 1.4GB 라 정규화는 스트리밍(peak RSS 198MB).

## 미측정
- VPS 실제 heap(5/6GiB 등)에서 동시 요청 부하(p95)와 RSS. 서비스창 P1M/P3M 에서의 RSS 차이. OSM 도로필터로 street graph 를 더 줄일 여지.
