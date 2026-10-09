# 지도 정류장 전국 타일 (#326)

## 뭘 하나
지도 정류장을 서울 DB 1.3만 개에서 전국 GTFS 20.6만 개로 바꾼다.
0.1°(약 11km) 타일로 나누고 타일마다 ETag 를 붙여, 앱은 보이는 타일만 받고 바뀐 타일만 다시 받는다.

## 왜
근처 목록·노선은 전국인데 지도는 서울뿐이라 서로 어긋나고, 서울 밖에서는 지도로 정류장을 고를 수 없다.

## 핵심 결정
- 타일 분할: 한 파일 2.8MB 대신 타일당 p50 1.5kB·최대 31kB, 서울 화면 하나 100kB 이하(한 파일은 기각)
- 새 v2 엔드포인트, 마커 키 gtfsStopId. v1 은 구버전 앱용으로 유지
- 같은 정류장은 #327 규칙으로 한 점, 대표는 서울 → 경기 → 인천(근처 목록과 같은 id)
- OTP 를 1° 블록 48칸으로 나눠 조회해 힙 피크를 줄인다. 부팅 시 + 매일 다시 만들고, 실패하면 1분마다 재시도한다. 결과가 비정상(15만 미만·이전의 80% 미만)이면 게시하지 않는다
- tileVersion 은 내용 해시다. manifest 는 매번 재검증하고, 타일 URL 에 버전을 넣어 1년 immutable 캐시한다(Opus 리뷰 반영)

## 건드는 곳
trackPatterns.graphql, TransitPatternPort, MapBusStopControllerTest, OtpTransitPatternAdapter, BusStopTiles(신규), NationalBusStopTileService(신규), NationalBusStopTileWarmup(신규), GetBusStopTilesUseCase(신규), MapBusStopController, HANDOFF.md

## 리스크
- api 힙: 빌드 중 피크 → 배포 후 Grafana 로 확인
- 지방 다른 피드끼리 겹치는 마커(시외·시내)는 범위 밖, 후속

## 검증법
타일 key·합치기·해시·컨트롤러(200/304/404/503) 테스트가 통과하고, 운영에서 타일 약 1,158개, 강남 타일에 신분당선강남역이 1점, 304 가 동작하면 완료.
