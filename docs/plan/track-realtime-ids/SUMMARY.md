# 단독 추적 조회에 실시간 조회 id 추가 (#313)

## 뭘 하나
`track/stops/near` 정류장에 버스 도착용 정류장 id·ARS, 지하철 역 도착용 역 id·호선을 싣는다.
`track/stops/{id}/patterns` 방향에는 버스 도착용 노선 id 를 싣는다. 못 풀면 null.

## 왜
앱 홈 근처 목록을 전국 GTFS 로 바꾸려는데, GTFS id 로는 도착·예측 등 실시간 기능을 부를 수 없다.

## 핵심 결정
- 버스 id 는 경로 검색과 같은 규칙(서울 DB 확인 / 경기 GBIS / 그 밖 TAGO 접두사) — 도착 API 가 모양으로 원천을 고른다
- 지하철 호선은 OTP 정류장의 노선 이름으로, 역은 #283 매처(이름 우선, 19노선 커버리지)로 — 좌표 50m 방식 기각
- 지하철 정류장을 지나는 호선이 둘이면 호선마다 한 줄
- 응답 필드 추가만(하위 호환), 예측 지원 여부는 범위 밖

## 건드는 곳
TransitPatternPort, OtpTransitPatternAdapter, trackPatterns.graphql, OtpSeoulIdentityResolver, TrackRealtimeIdResolver(신규), TrackSelectionQueryService, TrackViews, HANDOFF.md

## 리스크
- `track/*` 는 로그인 필요 + 운영 플래그 `JOURNEY_TRACK_ENABLED` off — 앱이 홈을 옮기기 전에 켜야 하고 익명 사용자 처리를 정해야 함
- 같은 물리 정류장이 서울·경기·인천 feed 로 여러 번 온다(기존 동작, 중복 제거 안 함)

## 검증법
버스 id 규칙(서울 있음/없음·GBIS·마을버스·TAGO·방향 접미)·지하철 호선 펼치기·미지원 노선 null 단위 테스트 + 응답 JSON 테스트 통과.
