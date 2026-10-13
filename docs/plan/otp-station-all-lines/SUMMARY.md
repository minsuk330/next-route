# 지하철 고정 벌점 제거 + 노선 없는 역 이름 = 모든 노선 역

## 뭘 하나
OTP 가 지하철 승강장 출입마다 붙이던 2분(환승 4분) 고정 벌점을 없앤다. "서울역"처럼 노선 없는 역 이름은 그 역 모든 노선 승강장에서 0분으로 출발·도착하게 한다.

## 왜
강남 → 서울역 검색에 지하철이 0개(네이버·ODsay 1위 = 2호선>4호선 35분). 같은 경로가 우리 엔진에서 49분으로 계산됐다.

## 핵심 결정
- build-config `subwayAccessTime: 0` — 기각: MinimumTransferTimeIsDefinitive(대기 -1분 경로)
- 승강장 여러 개 역마다 `VSA_` 가상 역(승강장·출구 복제) — 실제 승강장은 이미 VST 자식이라 복제
- 노선 있는 이름("잠실역 8호선")은 지금처럼 그 노선 VST — 기각: 모든 이름에 VSA(노선 지정 무시)
- 일반철도(KTX) 승강장도 VSA 에 포함 — "서울역" 장소가 KTX 역사라서
- 버스 시간 낙관은 별건

## 건드는 곳
config/otp-national/build-config.json, add_station_entrances.py(+테스트), OtpStationResolver, OtpRoutingAdapterHttpTest, release-manifest·coverage-index, 운영 그래프·api·worker

## 리스크
- 좌표 검색 전체에서 지하철이 조금 유리해짐(의도) — 버스가 맞는 구간 회귀는 코퍼스로 확인
- 승강장 복제와 실제 승강장 사이 거리 링크가 엇갈리면 도착 0분이 안 될 수 있음 — 로컬 실측

## 검증법
로컬 그래프로 19구간 코퍼스: "서울역" 지하철 1위, 버스 구간 회귀 없음. 운영 배포 후 같은 코퍼스.
