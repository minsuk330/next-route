# 앱 인계 — 지하철 링 예정 출처(PR-1)

서버가 스냅샷 `subwayGuidance` 와 Live Activity push `props` 에 같은 이름으로 두 칸을 더 싣는다. 둘 다 **모르는 앱은 무시해도 된다**(기존 동작 유지).

| 키 | 값 | 뜻 | 앱 동작 |
|---|---|---|---|
| `etaSource` | `LIVE` · `MEASURED` · `TYPICAL` · null | `estimatedNextStationAt` 의 출처. 예정이 없으면 null | `TYPICAL` 이면 다음 역 도착 **시각 표시를 숨긴다**(링은 그대로 그림) |
| `alightingEtaSource` | 같음 | `estimatedAlightingAt` 의 출처. 다음 역이 하차역이 아니면 남은 구간이 계획값이라 `TYPICAL` | `TYPICAL` 이면 하차 시각을 "약 N분" 같은 완곡 표기로 |

- PR-1 단계에서 서버가 실제로 내는 값은 `LIVE`(운행 중 남은 초)·`MEASURED`(앞 열차 실측)·null 이다. `TYPICAL` 예정(GTFS 추정 직선)은 PR-2 부터 나온다.
- `holdReason=PREV_STATION_STOPPED` 는 PR-3 부터 "열차가 전역에 서 있음(링은 느리게 진행)"으로 뜻이 바뀐다 — 문구 "○○ 정차 중" 은 그대로 두면 된다.
- 회색 "실시간 정보가 잠시 끊겼어요"는 `quality=STALE` 일 때만.
