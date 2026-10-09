# 역 도착 응답 노선도 확장 — 앱 인계 (#317)

`GET /api/subway/stations/{stationId}/arrivals` 응답에 필드가 **추가만** 된다. 기존 필드 의미는 그대로다.
PR 이 머지될 때마다 이 문서에 이어 적는다.

## PR-1 — 열차 위치 (`directions[].trains[]`)

| 필드 | 타입 | 뜻 |
|---|---|---|
| `currentStationId` | string \| null | 열차가 지금 있는(또는 막 떠난) 역 ID. 이름만 알면 null 일 수 있다 |
| `currentStationName` | string \| null | 그 역 이름 |
| `status` | `APPROACHING` \| `ARRIVED` \| `DEPARTED` \| null | 그 역 기준 상태. `DEPARTED` = 그 역을 떠나 **이 방향 다음 역으로 가는 중** |
| `observedAt` | ISO-8601(+09:00) \| null | 그 위치·상태가 **확인된** 원천 시각 |
| `positionSource` | `POSITION` \| `ARRIVAL` \| null | 원천. 위치 API(`POSITION`)가 도착 API 의 역 정보(`ARRIVAL`)보다 정확하다 |

- 모르면 키는 오고 값이 null 이다.
- **`observedAt` 을 보간 앵커로 쓰지 말 것.** 사건(진입·출발) 시각이 아니다. `ARRIVAL` 원천은 상태가 그대로여도 계속 갱신된다. 구간 보간 앵커는 PR-3 의 `segment.departedAt` 이다.
- `status=null` 이고 역만 있으면 "그 근처 운행 중"이다. 도착 API 99(운행중) 열차의 현재 역 이름에서 온 값이다.
- 위치 API 의 전역출발(3)은 서버가 "(전역, DEPARTED)"로 바꿔 준다. 앱에서 따로 처리할 것은 없다.
- `ARRIVAL` 의 한계: 도착 API 의 직전 역(`statnFid`)은 합류역 바로 다음 역에서 다른 갈래의 전역을 가리킬 수 있다.
- **반대 방향 열차는 서버가 뺀다.** 위치 API 가 반대 방향으로 더 최근에 본 열차를 말한다(예: 수원 하행 목록의 "병점행 · 진위"). 위치 API 가 없는 노선은 빼지 못한다.

앱 반영: `currentMessage` 정규식 파싱(`trainStationName`)을 걷어내고 `currentStationName`+`status` 를 쓴다. 남은 초로 구간 안을 보간하는 방식은 PR-3 전까지 그대로 둔다.
