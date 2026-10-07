# 모바일 인계 — 지하철 링 버전 (#307, #306)

## 한 줄
스냅샷에 `guidanceVersion` 이 생겼다. 조건부 조회에 `afterGuidanceVersion` 으로 같이 보내면 링이 바뀐 응답을 받고, 늦게 온 옛 스냅샷은 이 값으로 버린다.

## 계약
| 위치 | 칸 | 뜻 |
|---|---|---|
| 스냅샷 최상위 | `guidanceVersion` (number, 항상 있음) | 링 화면이 마지막으로 바뀐 시각(epoch millis). 링이 없으면 `0` |
| `subwayGuidance` | `version` (number \| null) | 같은 값. 링이 없으면(`pending`) `null` |
| `GET /api/journeys/{id}` | `afterGuidanceVersion` (선택) | 들고 있는 `guidanceVersion`. 세 값(`afterCommandVersion`·`afterEvaluationVersion`·`afterGuidanceVersion`) 모두 서버 값 이하일 때만 **204** |

- "바뀜" 기준은 Live Activity push 와 같다: 다음 역·phase·quality·도착·앵커(진행 0.05 눈금, ETA 30초 눈금). 같은 화면이면 5초 tick 이 돌아도 안 오른다 → 대부분 204 유지.
- 여정 안에서 **단조 증가**한다(재탐색·고정 리셋 뒤에도 시각이라 커진다).
- 파라미터를 안 보내면 예전 규칙 그대로(두 버전만 비교).
- ETA(`eta.currentLeg`·`alighting`)는 이 버전에 **안 들어간다**(조회 시점 계산값).

## 앱이 할 일
1. 15초 조건부 조회에 `afterGuidanceVersion=<마지막으로 받은 guidanceVersion>` 추가.
2. **지하철 구간 전체 조회 임시 조치 제거**(이제 링이 생기거나 바뀌면 200 이 온다).
3. **옛 스냅샷 버리기(#306)**: 같은 `journeyId`·`routeRevision` 에서 받은 스냅샷의 `guidanceVersion` 이 지금 들고 있는 값보다 **작으면** `subwayGuidance` 를 덮어쓰지 않는다. 재시작 복원본도 같은 규칙. (`0` 은 "링 없음"이므로 비교에서 빼고, 단계 전이는 `commandVersion` 으로 판단)
4. 첫 링: 열차 선택 직후 스냅샷은 `pending`(`guidanceVersion=0`). 다음 조건부 조회(서버 링 tick 5초 뒤)에 200 으로 링이 온다.

## 같이 고친 서버 버그 — 링이 0 으로 되감기던 것
열차가 다음 역의 전역에 있는 동안(`arvlCd 3/4/5`) 서버가 관측마다 `segmentProgress=0` 으로 다시 앵커해서, 앱 링이 15~30초마다 0 으로 돌아갔다(journey 158 실측). 이제 같은 구간이면 **그때까지의 화면 값에서 이어 그리고**(`segmentProgress` 가 줄지 않음), 남은 초가 없으면 멈춘다(STALE). 앱 보간 공식(`progressBaseAt` 의 `segmentProgress` → `estimatedNextStationAt` 의 `progressTarget`)은 그대로.
