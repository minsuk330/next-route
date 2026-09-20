# 모바일 인계 — OTP 전환 후 지하철 실시간 도착 호출법

**한 줄**: 지하철 실시간 도착은 **좌표로 부른다**. OTP 전환(#230, OTP 가 기본 provider)으로
검색 응답의 지하철 **id 칸이 비었지만**, 그 API 는 원래 좌표 기반이라 `lat`/`lon` 만으로 동작한다.
노선을 특정하려면 아래 매핑표로 `line_id` 를, 방향은 `subPath.wayCode` 를 그대로 붙인다.

---

## 1. 무엇이 바뀌었나

OTP 로 검색한 지하철 구간(`subPath.trafficType == 1`)에서 **아래 칸이 항상 `null`** 이다.
ODSAY 가 주던 값이라 OTP 에는 대응물이 없거나, 있어도 **주소 체계가 달라 일부러 안 채운다**
(가짜 id 를 채우면 실시간 조회가 엉뚱한 역을 가리킨다).

| 칸 | 지금 | 비고 |
|---|---|---|
| `startID` / `endID` | **null** | ODSAY 역 id. OTP 는 gtfsId 라 실시간 API 와 체계가 다르다 |
| `wayCode` | **채워짐**(이 PR 부터) | 1=상행 · 2=하행, 순환선(2호선) 0=내선 · 1=외선. 아래 4절 |
| `way` | **null** | 방면 텍스트. OTP 에 대응물 없음 |
| `lanes[].subwayCode` | **null** | ODSAY 호선 코드 |
| `startExitNo` / `startExitX` / `startExitY` / `endExit*` | **null** | 출구 정보는 OTP 에 없다 |

## 2. 남아 있는 것 (이걸로 호출한다)

```jsonc
{
  "trafficType": 1,
  "startName": "강남",              // 승차역 이름(OTP)
  "endName": "역삼",
  "startX": 127.02818, "startY": 37.49677,   // ← 승차역 좌표. 이게 호출 키다
  "endX": 127.03646,  "endY": 37.50075,
  "lanes": [
    { "name": "서울2호선", "routeDisplayColor": "#00A23F" }   // ← 노선명(OTP shortName)
  ],
  "stations": [
    { "index": 0, "stationID": "…", "stationName": "강남", "x": 127.02818, "y": 37.49677 }
  ],
  "polyline": [ /* … */ ]
}
```

- **`startX`/`startY` 가 곧 승차역 좌표**다(OTP 정차 좌표). 실시간 도착은 이 좌표로 부른다.
- `lanes[0].name` 은 OTP `route.shortName` 이고 값은 **23종**으로 고정돼 있다(3절 표).
- `stations[].stationID` 는 OTP gtfsId 다. **실시간 API 에 넣지 말 것** — 체계가 다르다.

---

## 3. 호출

```
GET /api/arrivals/subway?lat={startY}&lon={startX}
GET /api/arrivals/subway?lat={startY}&lon={startX}&line_id=1002        ← 환승역에서 권장
```

| 파라미터 | 필수 | 뜻 |
|---|---|---|
| `lat` | ✅ | 승차역 위도 = `subPath.startY` |
| `lon` | ✅ | 승차역 경도 = `subPath.startX` |
| `line_id` | ❌ | 내부 호선 id. **환승역에서 다른 호선이 잡히는 것을 막는다** |
| `wayCode` | ❌ | 방향 필터. **`subPath.wayCode` 를 그대로 넘기면 된다**(4절) |

응답(배열):

```json
[{
  "lineId": "1002", "direction": "내선",
  "arrivalSeconds": 180,          // null 이 흔하다 — 없다고 열차를 감추지 말 것
  "stationsAway": 2,              // 서버가 확정해 준다. currentMessage 를 파싱하지 말 것
  "currentMessage": "[2]번째 전역",
  "destinationName": "성수행", "trainType": null, "arrivalCode": "3"
}]
```

- **역을 못 찾으면 빈 배열**이다(오류 아님). 서버가 50m → 300m 까지 반경을 넓혀 찾는다 —
  역 좌표(역사 중심)와 경로 좌표(승강장)의 기준이 달라서다.
- `line_id` 를 주면 반경 안 후보 중 그 호선을 고르고, **못 맞히면 최근접 역으로 폴백**한다.

### 노선명 → `line_id` 매핑

`lanes[0].name` 이 그대로 열쇠다. **숫자만 뽑아 `1000 + n` 하지 말 것** — 인천1호선이
서울1호선(1001)으로 둔갑한다.

| OTP `lanes[0].name` | `line_id` | | OTP `lanes[0].name` | `line_id` |
|---|---|---|---|---|
| 서울1호선 | `1001` | | GTX-A | `1032` |
| 서울2호선 | `1002` | | 경의중앙선 | `1063` |
| 서울3호선 | `1003` | | 공항철도 | `1065` |
| 서울4호선 | `1004` | | 경춘선 | `1067` |
| 서울5호선 | `1005` | | 수인분당선 | `1075` |
| 서울6호선 | `1006` | | 신분당선 | `1077` |
| 서울7호선 | `1007` | | 우이신설경전철 | `1092` |
| 서울8호선 | `1008` | | 서해선 | `1093` |
| 서울9호선 | `1009` | | 신림선 | `1094` |

**실시간이 없는 노선** — `인천1호선` · `인천2호선` · `김포도시철도` · `용인경전철` ·
`의정부경전철`. 역 표(`subway_station`)에 없어 조회하면 **빈 배열**이다. `line_id` 를 지어내
붙이면 엉뚱한 호선 열차가 온다. 이 노선은 **실시간 UI 를 아예 그리지 않는 편이 맞다**.

> **일반철도(교외선 `[일반]교외서울의정부`)는 `lanes` 자체가 없다.** 표시색 매핑이 도시철도만
> 있어서다(legacy 경계가 색이 있을 때만 `lanes` 를 만든다). 노선명이 필요하면 `startName` ·
> `endName` 으로 그릴 것. 실시간도 없다.

---

## 4. 방향(`wayCode`) — 이제 내려간다

검색 응답의 `subPath.wayCode` 를 **그대로** 실시간 도착 호출에 넘기면 된다. ODSAY 때와 값 공간이
같아서 앱 코드는 바뀌지 않는다.

| 값 | 뜻 | 쓰는 노선 |
|---|---|---|
| `1` | 상행 | 순환선 아닌 전 노선 |
| `2` | 하행 | 〃 |
| `0` | 내선 | 서울2호선 |
| `1` | 외선 | 서울2호선 |

- `0`/`1` 과 `1`/`2` 가 **노선에 따라 뜻이 다르다**. 앱이 해석할 필요는 없다 — 받은 값을 그대로
  돌려주면 서버가 노선을 알고 되돌린다.
- **`null` 일 수 있다.** feed 가 방향을 안 적는 노선이 있다(실측: 서울6호선). 그때는 파라미터를
  빼고 부르면 양방향이 내려온다.

<details>
<summary>어디서 오는 값인가</summary>

KTDB feed 는 `direction_id` 를 안 주지만(전 노선 -1) 노선 이름에 방향을 적는다 —
`서울2호선(본선)<내선>`, `신림선<상행>`, 교외선은 이름 자리에 `상행` 만. 서버가 그것을 읽어
`wayCode` 로 옮긴다. **feed 의 61개 노선 전부**에 대해 읽히는지 테스트로 고정해 뒀다
(`GtfsRailDirectionCoverageTest`) — 표기가 바뀌면 앱이 아니라 그 테스트가 먼저 깨진다.

</details>

## 5. 참고 — 열차 위치 API

도착이 아니라 "열차가 지금 어디"가 필요하면 별도 엔드포인트다.

```
GET /api/realtime/subway?lineId=1002&stationNames=강남,역삼&direction=내선
```

- 셋 다 선택. `lineId` 는 **위 표와 같은 내부 id**, `stationNames` 는 CSV, `direction` 은
  `상행`/`하행`/`내선`/`외선`.
- 수집기가 쉬는 시간대·오류여도 **200** 이다(상태가 본문에 담긴다). Redis 자체가 죽으면 503.

---

## 6. 요약 체크리스트

- [ ] 지하철 도착 호출을 `startID`/`subwayCode` 기반 → **`startY`/`startX` 좌표 기반**으로 교체
- [ ] 환승역 정확도가 필요하면 `lanes[0].name` → 3절 표로 `line_id` 부착
- [ ] `wayCode` 는 `subPath.wayCode` 를 그대로 전달(값이 null 이면 파라미터 생략)
- [ ] 인천·김포·경전철은 실시간 UI 숨김
- [ ] `arrivalSeconds == null` 이어도 `stationsAway`/`currentMessage` 로 열차를 계속 표시
