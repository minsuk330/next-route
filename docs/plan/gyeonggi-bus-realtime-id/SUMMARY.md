# 경기 버스 실시간 도착 연결 (GBIS)

## 뭘 하나
길찾기 응답의 경기 노선 버스 구간(예: 5600)에 정류장 ID와 노선 ID를 채운다. 기존 도착 API(`/api/arrivals/bus/{stopId}/{routeId}`)는 서울 데이터에 없는 조합이 들어오면 경기도 버스도착 API로 조회해, 같은 응답 형식으로 돌려준다.

## 왜
지금은 서울 안에서 버스를 타는 지점의 23%가 실시간 도착을 못 띄우고, 그중 20%p가 경기 노선이다.

## 핵심 결정
- 엔드포인트와 응답 형식은 그대로 둔다. 서버가 서울 순번이 있으면 서울 API, 없으면 경기 API로 보낸다. 프론트 수정 없음(provider 필드 추가안 기각)
- 경기 API는 OTP ID 뒤 9자리를 그대로 받고 순번이 필요 없다(실측). 경기 노선 DB 적재 안 함
- 서울 식별 타입과 섞지 않고 `GyeonggiBusIdentity`를 따로 둔다. 노선 ID의 `_U`/`_D` 접미사는 제거
- 경기 값을 서울 의미로 변환한다: 분→초, 도착 메시지 생성, 혼잡도 1~4→3~6
- 15초 메모리 캐시 + 한도 초과 시 자정까지 차단. 키는 env(`GYEONGGI_BUS_API`)로만 넣는다

## 건드는 곳
OtpSeoulIdentityResolver, GyeonggiBusIdentity(신규), LegacyRouteResponseMapper, GyeonggiBusArrivalPort·GyeonggiBusArrival·GyeonggiBusArrivalAdapter(신규), BusArrivalQueryService, application.yaml, VPS env

## 리스크
- 일일 한도 10,000회. 한 정류장·노선 조합을 하루 종일 폴링하면 5,760회라 사용자가 늘면 부족할 수 있음
- 여정(journey)에 경기 구간 ID가 처음 들어간다. 노선은 지금처럼 해석 실패, 정류장은 서울 DB에 있으면 해석됨
- 인천 노선과 검색 응답의 `transferArrivals`는 이번 범위 밖

## 검증법
식별·매퍼·어댑터·서비스 단위 테스트 16개가 통과하고, 기존 서울 경로와 여정 테스트가 그대로 통과하면 완료. 배포 후 `/api/arrivals/bus/123000001/228000184`가 5600 도착 정보를 돌려주는지 확인.
