# 역 도착 응답 노선도 확장 (#317 + #318 일부)

## 뭘 하나
역 도착 API(`/api/subway/stations/{id}/arrivals`) 응답에 필드만 더한다. 열차마다 지금 역·상태·구간 출발/도착 예정을 싣고, 방향마다 오는 쪽 역 4개와 다음 역을 초 단위 운행 시간과 함께 싣는다. 위치 피드가 없는 노선도 "방금 떠난 열차"를 채운다.

## 왜
앱이 `currentMessage` 문자열을 파싱하고 `stations-ahead` 를 두 번 더 부르며 분 단위로 보간한다. 그래서 위치가 틀리거나 튀고, 반대 방향 열차가 섞여 들어온다.

## 핵심 결정
- 새 외부 호출 없음. 이미 있는 위치 로그(`subway:pos`), 구간 실측(#316), OTP 패턴(캐시만, 미스면 비우고 비동기 갱신)을 조인함
- 위치 상태 3(전역출발) = 그 역의 바로 전역을 떠남(사용자 확정). 노선 순서표로 전역을 찾아 "전역 출발"로 표시함. 위치 피드가 없으면 도착 행의 원천 필드(`arvlMsg3`·`statnFid`)를 스냅샷에 보존해 씀(worker 변경)
- 앵커는 `segment{departedAt, expectedAt}` 객체로 줌. 링 형식(`segmentProgress`)은 기각, `SubwayRingCalculator` 는 여정 전용이라 재사용 불가
- `expectedAt` 원천 순서는 오늘 실측 중앙값 → GTFS 계획 초. 어느 쪽인지 `source` 로 구분
- 걸러내기는 2단계. PR-1 은 반대 방향 열차만 빼고, 이미 지난 열차 판정은 역 순서가 생기는 PR-3 에서 함
- 위치 피드 없는 노선의 떠난 열차는 별도 Redis 키에 기록함. 출발 조회를 한 곳(`SubwayDepartureLookup`)으로 모아, 화면에 보인 떠난 열차는 출처와 무관하게 모두 고를 수 있게 함. M7 자동 판정은 그대로 둠
- PR 4개, 순서 ①→②→③, ①→④: ①위치+노선 순서표 ②노선도 역 목록 ③구간 앵커 ④떠난 열차 대체+선택 허용

## 건드는 곳
SubwayStationArrivalsResponse, SubwayStationArrivalQueryService, SubwayTrainPositionResolver(신규), SubwayTrainSegmentResolver(신규), StationsAheadCalculator, SubwayStationsAheadService, SubwayStationsAheadResponse, GetSubwayLineStripUseCase(신규), SubwayLineOrder(신규), SubwayTrainSelectionService, SubwayDepartureLookup(신규), SubwayTrainStatus(주석), RealtimeSubwayCollector, SubwayArrivalDepartureRecorder(신규)와 포트·Redis 어댑터

## 리스크
- 오래 정차한 열차는 위치 로그상 오래된 값으로 보임. 신선도 기준을 300초로 두고, 반대 방향 제거는 양쪽 시각을 비교해서만 함
- GTFS 가 분 단위면 초 단위 운행 시간은 거짓 정밀도가 됨. PR-2 에서 실측해 확인함
- 패턴 캐시가 만료된 직후 첫 호출은 노선도 필드가 비고, 전역 출발 열차 위치도 도착 행 기준으로 떨어짐
- 도착 피드 출처의 떠난 열차는 출발 시각이 덜 정확함. 고른 뒤 틀리면 기존 "이 열차 아님" 경로로 처리

## 검증법
PR마다 계약 테스트를 통과해야 함(필드 null 조건, 실패해도 도착 목록은 유지). 2호선 강남, 신도림 갈래, 수원 하행, 수인분당 1역을 로컬과 배포 후에 실제로 호출해 노트 `### 검증` 에 붙임.
