# 서울·경기 밖 버스 실시간 도착 연결 (TAGO)

## 뭘 하나
부산·대구·인천 등 서울·경기 밖 38개 지역 버스 구간에 정류장 ID와 노선 ID를 채운다. 기존 도착 API가 이 ID를 받으면 국토교통부 TAGO API로 조회해, 같은 응답 형식으로 돌려준다.

## 왜
전국 버스 탑승 지점의 41%(확정 38개 지역)가 지금 실시간 도착을 전혀 못 띄운다.

## 핵심 결정
- 엔드포인트와 응답 형식은 그대로 둔다. ID에 영문 접두사가 붙어 있으면(`BSB167850202`) TAGO, 숫자만 있으면 기존 서울·경기 흐름으로 보낸다. DB 조회 없음
- TAGO는 OTP ID를 접두사까지 그대로 받는다(실측). 필요한 도시코드는 접두사→코드 표 38개로 정한다. 정류장마다 138개 코드를 넣어 보고 실측으로 확정
- 표에 없는 지역(농어촌 TSB, 미확정 8개)은 ID를 비워 둔다. 별도 실측 배치로 후속 처리
- 같은 키를 쓰는 API끼리 동시 세션 30개 한도를 나눠 쓰므로 TAGO 호출은 동시 8개로 제한하고 15초 캐시를 둔다
- PR #251(경기) 머지 후 새 브랜치에서 시작한다. 같은 파일을 건드려 스택이 되는 것을 피하기 위해서

## 건드는 곳
TagoCityCodes·TagoBusIdentity(신규), OtpSeoulIdentityResolver, LegacyRouteResponseMapper, TagoBusArrivalPort·TagoBusArrival·TagoBusArrivalAdapter·TagoArrivalMapper(신규), BusArrivalQueryService, application.yaml, VPS env

## 리스크
- 프론트가 ID를 숫자로 가정하는지 확인되지 않았다. 영문 접두사가 들어가므로 모바일에 알려야 한다
- 동시 세션 30개를 경기·TAGO 지하철과 공유한다. 사용자가 늘면 일시 오류가 늘 수 있다

## 검증법
식별·매퍼·어댑터·서비스 단위 테스트와 기존 테스트가 통과하면 완료. 배포 후 `/api/arrivals/bus/BSB167850202/BSB5200186000`(부산 186)이 도착 정보를 돌려주는지 확인.
