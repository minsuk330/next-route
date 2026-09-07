# OTP ↔ ODsay(legacy DTO) 필드 호환성 실측 (P2 착수 전 가능성 확인)

작성 2026-09-07. 방법: P1 수도권 graph(OTP 2.9.0) 에 planConnection 실제 쿼리(청계천→사당) 후, legacy `SubPathResult`/`PathInfo`/`PathResult` 필드마다 OTP 응답으로 채울 수 있는지 대사. 근거 fixture: `tools/transit-routing-benchmark/results/otp-seoul-p1/field-probe-cheonggye-sadang.json`.

## 결론
**매핑 가능(결정 A 성립).** legacy 표시 필드 대부분은 OTP 로 채워진다. 다만 **채울 수 없는 6개 갭**이 있고, 성격이 3종이다 — ①피드 영구한계(방향/급행/색상) ②ODsay 전용기능(지하철 출구) ③매핑 필요(실시간 ID). 이들은 매핑 아키텍처를 막지 않고 neutral 모델의 coverage/quality/`STATIC_SCHEDULE` 상태로 표현된다. OTP 는 fare 를 주지 않으므로 payment=unknown 확정(0 금지).

## OTP 가 실제로 채우는 것 (검증됨, gqlErr 0)
| OTP 필드 | 값 예시 |
|---|---|
| `mode` | WALK / BUS (SUBWAY/RAIL/TRAM 존재) |
| `duration`,`distance` | 936s, 7056.7m |
| `realtimeState` | SCHEDULED |
| `intermediatePlaces[]` | 정차역 name+lat/lon+`stop.gtfsId` (버스 leg당 7~9개) |
| `steps[]` | 도보 turn(distance/relativeDirection/streetName) — **TMAP walkSteps 대체** |
| `route` | `gtfsId=1:BR_TAGO_SEB100100022`, `shortName=143`, `type=3` |
| `trip.gtfsId` | `1:BR_TAGO_SEB100100022_Ord039` |
| `legGeometry.points` | encoded polyline (leg별) |
| `startTime`/`endTime` | leg 승하차 시각 |

## legacy SubPathResult 필드별 판정
| legacy 필드 | OTP 소스 | 판정 |
|---|---|---|
| trafficType(1지하철/2버스/3도보) | `mode` 매핑 (+RAIL 신규) | ✅ 파생 |
| sectionTime | `duration` | ✅ |
| distance | `distance` | ✅ |
| stations[] (정차역) | `intermediatePlaces` + from/to | ✅ |
| startName/endName | `from.name`/`to.name` | ✅ |
| startX/Y,endX/Y | `from/to.lat/lon` | ✅ |
| polyline | `legGeometry.points` decode | ✅ (P1=street 형상, P3=OSM relation 로 교체) |
| walkSteps | `steps` (도보 leg) | ✅ OTP 네이티브 (TMAP 불필요) |
| walkTotalTimeSeconds | `duration` (도보) | ✅ |
| **trainType(급행 등)** | 없음 (KTDB feed) | ❌ 갭 — 피드에 급행/방향 정보 없음 |
| **payment** | `fareProducts=[]` | ❌ unknown — 0 생성 금지, v2 nullable |
| startId (지하철 ODsay id) | `from.stop.gtfsId` (GTFS id) | ⚠️ namespace 다름 → P2-D 매핑 |
| **way/wayCode (지하철 방면)** | `trip.directionId=null`,`headsign=null` | ❌ 갭 — 피드에 방향 없음 |
| **exit(startExitNo/X/Y 등)** | 없음 | ❌ 갭 — OTP 에 지하철 출구 데이터 없음(ODsay 전용) |
| startLocalStationID/ArsID/endID (버스 서울/ODsay id) | `from.stop.gtfsId` | ⚠️ 매핑 필요 → 실시간·ML 붙이려면 P2-D |
| transferArrivals | 엔진 아님 → P2-E 보강 | n/a |
| lanes / laneGraphics | leg당 단일 route; ODsay polyline | ⚠️ routeOptions=P2-F, 형상=P3 |

## legacy PathInfo 필드별 판정
| 필드 | OTP | 판정 |
|---|---|---|
| totalTime | itinerary `duration` | ✅ |
| totalWalk | 도보 leg distance 합 | ✅ |
| transferCount | `numberOfTransfers` | ✅ |
| firstStartStation/lastEndStation | 첫/끝 stop name | ✅ |
| **payment** | fare 없음 | ❌ unknown |
| **mapObj** (ODsay 인코딩 폴리라인 참조) | 없음 | ❌ ODsay 전용 — OTP 는 leg별 legGeometry 로 대체(구조 다름) |

## 갭 6종과 처리
1. **지하철 출구(exit)** — OTP 영구 부재(ODsay 전용). → 수도권 전철 출구 안내는 OTP 검색에서 사라짐. neutral 에 coverage 로 표기, 표시 제외. 필요 시 별도 출구 데이터 소스는 P8 후속.
2. **방향/급행(way/wayCode/trainType/directionId/headsign)** — KTDB 202503 feed 에 없음(설명서: 방향/헤드사인 미제공). 지하철 상·하행, 버스 방면 표시 불가 → STATIC 표기. GTFS 보강(shapes/direction) 또는 별도 소스는 후속.
3. **요금(payment)** — fareProducts 빈 값. unknown 확정, v2 nullable amount+availability. legacy int 는 sentinel(예: -1 아님 → null 불가한 primitive 라 legacy 응답 범위 결정 필요, PLAN 에서 확정).
4. **실시간 ID(ArsID/localStationID/지하철 station id)** — GTFS stop id ↔ 서울 API id 매핑 전엔 STATIC. **P2-D 핵심 작업**. 매핑된 leg 만 실시간/ML.
5. **노선 색상** — KTDB 없음(color=null). → P3 OSM relation `colour`.
6. **mapObj** — ODsay 전용. OTP 는 leg별 legGeometry 사용 → 저장/전달 구조를 leg 단위로 바꿔야 함(snapshot/geometry, P2-G/P3).

## 시사점(플랜 반영)
- 결정 A 유효. neutral `RoutingResult`/`RoutingLeg` 에 mode/route/trip/stop occurrence/geometry(source·quality)/coverage 를 담고, legacy mapper 가 위 ✅ 필드를 채운다.
- ❌ 갭은 "매핑 불가"가 아니라 "원천 부재" → **가짜값 생성 금지**, coverage/`STATIC_SCHEDULE`/quality 로 정직하게 노출(intent §의 정책 그대로).
- ⚠️ 항목(실시간 ID)은 P2-D 매핑에 의존. 매핑 전 P2 는 STATIC 으로 동작해야 하며 실시간 보존은 매핑된 leg 에 한정.
- payment primitive int(legacy) 를 어떻게 둘지는 PLAN 의 명시 결정사항.
