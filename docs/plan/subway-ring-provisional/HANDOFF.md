# 모바일 인계 — 잠정 고정에도 진행 링이 온다

## 한 줄
`subwayGuidance.bindingStatus` 가 **`PROVISIONAL` 이어도 `CONFIRMED` 와 똑같이 그린다.** "확인 중", "추정", "확실하지 않음" 같은 문구나 표시를 **넣지 않는다.**

## 무엇이 바뀌나 (서버, 2026-09-29)
| 상황 | 전 | 후 |
|---|---|---|
| `bindingStatus=PROVISIONAL` | `nextStationName`·`segmentProgress` 등 링 값이 **null** | 링 값이 **채워져 온다**(확정과 같은 모양) |
| 잠정 상태의 하차역 접근 | 하차 단계로 안 넘어감 | `phase=SUBWAY_ALIGHT` 로 넘어감 — 확정과 동일 |
| 잠정이 8분 넘게 확정 안 됨 | `ABANDONED` 로 바뀌며 링 사라짐 | **계속 `PROVISIONAL`**, 링 유지 |
| 잠금화면 Live Activity | 확정 뒤부터 갱신 | **잠정부터** 갱신. payload(`{name, props}`)는 변경 없음 — 원래 상태 필드가 없다 |

## 앱이 할 일
1. `bindingStatus` 가 `PROVISIONAL` 또는 `CONFIRMED` 이면 링을 그린다. 두 값에 **다른 UI 를 쓰지 않는다.**
2. `PENDING`·`ABANDONED` 는 지금처럼 링 없음.
3. **교정**: 잠정 열차가 다음 역에서 다른 열차로 바뀌면(출근길 드묾) 진행률·다음 역·도착 예정이 **한 번 튄다.** 애니메이션으로 되감기지 않아도 된다 — 새 값으로 바로 교체.
4. "이 열차 아니에요"(`POST /api/journeys/{id}/subway/binding/reset`)는 지금과 같다. 잠정 링이 틀렸을 때 사용자가 벗어나는 **유일한 길**이므로 링 화면에서 닿기 쉬워야 한다.

## 서버에는 사실이 남는다
API·서버 로그·메트릭(`journey.subway.ring.updated{binding=provisional|confirmed}`)은 잠정/확정을 구분해 기록한다. 화면에서만 구분하지 않는다.
