# 검색 응답에 버스 도착예측 싣기 (계약 되돌림)

## 뭘 하나
OTP 길찾기 검색 응답에 버스 구간 도착예측을 서버가 채워 내린다(`transferArrivals`).
ODsay 시절과 같은 모양 — 앱이 예측을 따로 부르지 않아도 된다.

## 왜
9/9에 "검색은 정적, 예측은 프론트가 따로"로 정했던 계약을 사용자가 취소했다. 한 번에 내려주는 쪽으로 되돌린다.

## 핵심 결정
- **기존 `TransferArrivalEnricher` 를 그대로 재배선** — 새로 만들면 ODsay 분기와 값이 갈린다
- **버스만** — 지하철 예측 모델은 없다. 없는 걸 지어내지 않는다
- **순서는 그대로** — 값만 채운다. 재정렬·강등은 여전히 금지(취소된 건 "싣지 마라"까지)
- **검색 전체 예산 3초 → 4.5초** — OTP 0.9~2초에 보강 1.5초를 더하면 3초 안에서 상시로 잘려 빈 값이 나간다
- **알림용 `searchWithoutRealtime` 은 안 건드린다** — 그쪽은 정적이 맞다
- **모바일 직접호출 HANDOFF 는 폐기** — 통로가 둘이면 어느 쪽을 따를지 갈린다

## 건드는 곳
`RouteSearchService.runOtpSearch`, `application.yaml`(deadline-ms), `RouteSearchServiceOtpWiringTest`, `docs/plan/transfer-predict-mobile/HANDOFF.md`(삭제)

## 리스크
- **검색이 느려진다** — 지금 0.88초 → 최대 2.4초. 예산 상향으로 받지만 체감은 늘어난다
- 외부 호출이 검색 1건당 최대 12회(기존 상한) 늘어 — 사용자 수에 비례. 상한·동시성 가드는 그대로 쓴다
- 보강이 예산에 잘리면 `transferArrivals=null` + `meta.degraded=[bus_realtime]` 로 조용히 빈다. 상시로 뜨면 예산 재조정 필요

## 검증법
잠실역 8호선 → 청계천 응답의 303번 구간에 `transferArrivals` 가 non-null 로 뜨고, 검색 지연·`meta.degraded`·외부 호출 수를 배포 전후로 비교해 노트에 남긴다. 계약 테스트 3개(실시간 켬/끔/보강 실패) 통과.
