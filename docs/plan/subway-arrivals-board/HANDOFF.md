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

## PR-2 — 노선도 역 목록 (`directions[]`, #323)

| 필드 | 타입 | 뜻 |
|---|---|---|
| `stationsBehind` | StripStation[] | 이 방향 열차가 오는 쪽 역, **먼 역 → 가까운 역**(마지막 = 직전 역), 최대 4. 모르면 `[]` |
| `nextStation` | StripStation \| null | 이 역 다음 역. 종착·분기점·모름이면 null |
| `boardDwellSeconds` | int \| null | 이 역 정차 계획 초 |
| `behindEnd` | `FULL` \| `ORIGIN` \| `BRANCH` \| `UNKNOWN` | 목록이 왜 거기서 끝났나. `BRANCH` = 그 앞은 갈래마다 달라 합류점에서 끊음 |

`StripStation{stationId, stationName, secondsToHere, runSeconds, dwellSeconds}` — 초는 GTFS **계획**(초 단위, 최다 trip 패턴).
- `secondsToHere`: behind = 그 역 출발 → 이 역 도착, next = 이 역 출발 → 그 역 도착
- `runSeconds`: 진행 순서로 직전 역 출발 → 그 역 도착. 직전 역→이 역 운행 초 = `stationsBehind` 마지막의 `secondsToHere`
- `stations-ahead` 각 역에도 `secondsFromBoard`·`runSeconds`·`dwellSeconds` 가 붙는다(#318)
- 패턴 캐시가 비면(서버 재시작 직후 등) 노선도만 비어서 온다 — 다음 호출에 채워진다
- `currentStationId` 가 노선도에 없는 열차(갈래 위·더 먼 곳)는 `stationsAway`/`arrivalSeconds` 로 왼쪽 끝 바깥 '더 먼 곳' 칸에 둔다

앱 반영: 방향마다 `stations-ahead` 두 번 더 부르던 것 제거.

## PR-3 — 열차별 구간 앵커 (`trains[].segment`)

| 필드 | 뜻 |
|---|---|
| `fromStationId/Name` | 떠난 역 |
| `toStationId/Name` | 향하는 다음 역 |
| `departedAt` | from 을 떠난 시각 |
| `expectedAt` | to 도착 예정. **지금보다 과거일 수 있다**(늦는 중). 모르면 null |
| `source` | `MEASURED`(오늘 같은 구간을 지난 최근 열차 실측 중앙값) \| `SCHEDULED`(실측 없을 때 GTFS 계획) \| null |

- `segment` 는 **달리는 중(`status=DEPARTED`)일 때만** 온다. 정차·진입 중이면 null → 역에 둔다
- 보간: `departedAt → expectedAt` 사이 선형. `expectedAt` 을 넘기면 to 직전에서 멈추고 다음 응답을 기다린다. **화면 역행 금지는 앱 몫**
- 우선순위: `segment` → 방향별 `runSeconds`(PR-2) → 역 단위 배치. 열차 속도 추정·2분 고정은 제거
- 서버가 이 역을 이미 지난 열차를 뺀다(위치 API 가 이 역 출발을 봤거나, 현재 역이 이 역 하류 3역 안)

## PR-4 — 떠난 열차 (`directions[].departed[]`, #324)

- 위치 API 가 없는 노선(수인분당·경의중앙·경춘·경강·서해 등)도 `departed` 가 채워진다
- `source`: `POSITION` | `ARRIVAL` — 정보용. **둘 다 열차 선택 가능**(서버가 선택 시 같은 목록을 본다)
- `ARRIVAL` 은 `currentStationName` 이 대개 null 이다
