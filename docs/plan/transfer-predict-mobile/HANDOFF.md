# 모바일 전달물 — 버스 탑승 예측 `/api/transfer/predict`

작성 2026-09-20. 대상 = 모바일. **서버는 이미 배포돼 있다**(이미지 `nextroute:20260920-173303-27cab2bf`). 이 문서대로 부르면 바로 값이 나온다.

## 0. 한 줄

길찾기 검색 결과의 **버스 구간**에 대해 "내가 그 정류장에 도착할 때 버스가 언제 오는지"를 묻는 공개 API.
검색 응답은 정적이라 이 값을 **싣지 않는다**(`transferArrivals` 는 항상 `null`). 필요하면 앱이 따로 부른다.

```
GET https://api.refit-100.site/api/transfer/predict
    ?stopId={정류장id}&routeId={노선id}&userArrivalAt={ISO8601}
```
인증 불필요(공개).

## 1. 이미 있는 실시간 API 와 무엇이 다른가

| | `/api/arrivals/bus/{stopId}/{routeId}` | `/api/transfer/predict` (이 문서) |
|---|---|---|
| 답하는 것 | **지금** 접근 중인 버스 1~2대 | **내가 도착할 시각**에 탈 수 있는 버스 |
| 근거 | 실시간 도착정보만 | 실시간 우선, 안 되면 **ML 모델** |
| 쓰는 자리 | 정류장 화면, 즉시 출발 | 경로 결과의 환승·승차 구간(몇 분 뒤 도착) |

지금 정류장에 서 있으면 앞엣것으로 충분하다. **"10분 뒤 저 정류장에서 303번을 탄다"는 앞엣것이 답을 못 준다** — 그 시각에 올 버스는 아직 도착 목록에 없다. 이 API 는 그 자리를 채운다.

## 2. 요청 파라미터

| 이름 | 필수 | 뜻 | 검색 응답 어디서 꺼내나 |
|---|---|---|---|
| `stopId` | O | 서울 정류장 id | `paths[].subPaths[].startLocalStationID` |
| `routeId` | O | 서울 노선 id | `paths[].subPaths[].lanes[0].busLocalBlID` |
| `userArrivalAt` | O | **내가 그 정류장에 서는 시각**(ISO8601, `2026-09-20T08:51:08Z`) | 아래 §3 계산 |
| `seq` | X | 노선상 정류장 순번 | **보내지 마라.** 생략하면 서버가 풀어서 응답에 실어 준다 |

- `trafficType == 2`(버스) 구간에서만 쓴다. 지하철(`1`)·도보(`3`)는 대상이 아니다.
- 두 id 중 하나라도 `null` 이면 그 구간은 **호출하지 않는다**(수도권 밖·미매핑 구간에서 null 이 될 수 있다).
- `lanes` 가 여러 개면 **첫 번째가 선택 노선**이다. 나머지까지 부르면 호출 수가 배로 는다 — 기본은 첫 번째만.

## 3. `userArrivalAt` 계산

검색 응답에는 절대 시각이 없고 구간별 소요(`sectionTime`, **분**)만 있다.

```
userArrivalAt = 지금 + Σ(그 버스 구간 앞에 있는 모든 subPath 의 sectionTime 분)
```

예: `[도보 8분, 버스 14분, ...]` 이면 버스 구간의 `userArrivalAt` = 지금 + 8분.
사용자가 결과 화면에서 머무는 동안에도 값은 흐른다 — **다시 부를 때 지금 시각으로 다시 계산**한다.

**상한이 있다**: `userArrivalAt` 은 지금 기준 **+40분** 이내, 과거는 **60초**까지만 허용. 넘기면 `400`.
40분 뒤 환승은 이 API 로 답할 수 없다 — 호출하지 말고 화면에서도 비워 둔다.

## 4. 응답

```json
{
  "stopId": "123000579",
  "routeId": "100100053",
  "seq": 35,
  "source": "MODEL",
  "status": "AVAILABLE",
  "calculatedAt": "2026-09-20T08:42:08.606017377Z",
  "userArrivalAt": "2026-09-20T08:51:08Z",
  "predictedArrivalAt": "2026-09-20T08:57:51Z",
  "waitSeconds": 403,
  "boardable": true,
  "vehicleId": "107017514",
  "modelVersion": "lgbm_2026-06-12_to_2026-08-06_corrected_20260816T060405Z"
}
```

| 필드 | 뜻 |
|---|---|
| `source` | `REALTIME` 실제 차량을 잡음 · `MODEL` 모델 추정 · `NONE` 못 구함 |
| `status` | `AVAILABLE` 이면 값이 있다. 그 밖은 전부 "예측 없음"(§5) |
| `predictedArrivalAt` | 버스가 그 정류장에 올 시각 |
| `waitSeconds` | `predictedArrivalAt − userArrivalAt`. **부호 있음 — 음수면 내가 가기 전에 지나간다** |
| `boardable` | `waitSeconds >= 0` 과 같은 뜻. 탈 수 있는 차인지 |
| `calculatedAt` | 서버가 계산한 시각. 신선도 판단용 |
| `vehicleId` / `modelVersion` | 디버깅용. 화면에 쓰지 않는다 |

**`status != "AVAILABLE"` 이면 `predictedArrivalAt`·`waitSeconds`·`boardable` 은 전부 `null` 이다.** 항상 `status` 를 먼저 보라.

## 5. `status` 표 — 화면에서 어떻게 다루나

| status | 뜻 | 화면 |
|---|---|---|
| `AVAILABLE` | 값 있음 | 표시 |
| `NO_VEHICLE` | 그 노선에 지금 운행 중인 차가 없다 | "정보 없음" |
| `UNSUPPORTED_ROUTE` | 모델이 학습하지 않은 노선 | "정보 없음" |
| `STOP_MAPPING_FAILED` | 정류장·노선 조합이 안 풀린다 | "정보 없음" (재시도 무의미) |
| `LIMITED` | 서버 시간 예산 초과 | **한 번 재시도해도 된다**(§7) |
| `BLOCKED` | 상류 API 차단 상태 | "정보 없음", 그 화면에서 재시도 금지 |
| `MODEL_UNAVAILABLE` / `UPSTREAM_UNAVAILABLE` / `ERROR` | 일시 장애 | "정보 없음" |
| `DISABLED` | 기능 스위치 꺼짐 | "정보 없음" |
| `ARRIVAL_TIME_APPROXIMATE` | 실시간이지만 도착시각이 근사 | 표시하되 신뢰도를 낮춰 취급 |

**원칙: 모르면 비운다.** 그럴듯하게 지어낸 시각은 사용자를 엉뚱한 때에 움직이게 한다. 시간표로 대신 채우지 않는다.

`boardable == false` 도 오류가 아니다. "이 차는 내가 가기 전에 지나간다"는 **정상 정보**이므로, 숨기지 말고 그렇게 보여 주는 편이 낫다(다음 차를 기다려야 한다는 뜻).

## 6. HTTP 오류

| 코드 | 언제 |
|---|---|
| `400` | `stopId`/`routeId` 공백, `seq <= 0`, `userArrivalAt` 이 과거 60초 밖 또는 미래 40분 밖 |
| `200` | 그 밖 전부 — **예측 실패도 200 이다.** `status` 로 판단한다 |

## 7. 호출량·재시도

- **화면에 보이는 구간만** 부른다. 경로 5개 × 버스 구간마다 전부 부르면 호출이 곱으로 는다. 펼쳐진 경로(또는 첫 경로)의 버스 구간만 권장.
- 서버는 버스 실시간을 **15초** 캐시한다. 같은 `stopId+routeId` 를 그보다 자주 불러도 더 새로운 값은 안 나온다. **폴링은 15초 이상**.
- 한 요청의 서버 예산은 **2.5초**다. 넘으면 `LIMITED`.
- **앱·서버 재시작 직후 첫 호출은 `LIMITED` 가 날 수 있다**(캐시 콜드). 실측: 1회차 `LIMITED` → 2회차부터 `0.19s → 0.02s`. `LIMITED` 는 **1회만** 재시도하고, 또 실패하면 "정보 없음".
- 사용자가 화면을 다시 볼 때는 `userArrivalAt` 을 다시 계산해서 새로 부른다(값이 흐른다).

## 8. 실전 예시 (2026-09-20 프로덕션 실측)

검색: 잠실역 8호선 → 청계천. `paths[2]` 에 303번 버스 구간이 있고 —
```
subPaths[1].startLocalStationID = "123000579"   (잠실역.롯데월드몰)
subPaths[1].lanes[0].busLocalBlID = "100100053" (303번)
subPaths[0].sectionTime = 8분(도보)  → userArrivalAt = 지금 + 8분
```
```bash
curl "https://api.refit-100.site/api/transfer/predict\
?stopId=123000579&routeId=100100053&userArrivalAt=2026-09-20T08:51:08Z"
```
```
source=MODEL status=AVAILABLE waitSeconds=403 boardable=true seq=35
```
같은 경로의 다음 버스 구간(`stopId=104000001`)은 `source=REALTIME waitSeconds=451` 이 나왔다.
**두 경우 모두 앱이 할 일은 같다** — `source` 는 참고용이고, 판단 기준은 `status` 와 `waitSeconds` 다.

## 9. 범위 밖 (지금은 안 된다)

- **지하철**: 이 API 는 버스 전용이다. 지하철 실시간은 기존 좌표 기반 `GET /api/arrivals/subway?lat&lon&line_id` 를 쓴다.
- **40분 초과 환승**: 모델 horizon 밖이라 `400`.
- **수도권 밖**: 서울 id 가 안 붙으므로 두 파라미터가 `null` → 호출 대상 아님.

## 10. 서버 쪽 후속 (모바일 작업 아님)

- 검색 응답에 `seq` 를 실어 줄지 — 지금은 서버가 매번 푼다. 호출당 DB 조회 1회라 필요해지면 그때.
- 여정(journey) 진행 중 대기 단계 ETA 는 **다른 경로**다(`/api/journeys/**` 의 `eta.currentLeg`). 그쪽은 평가기가 값을 저장해야 내려가는데 지금 비어 있는 건이 따로 추적 중이다 — 이 문서와 무관.
