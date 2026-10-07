# 지하철 남은 역 지연 대비 (#291)

## 뭘 하나
남은 역 API 의 노선 데이터 첫 적재에 5초 상한을 두고, 부팅 직후 미리 데우고, 역 연결 결과도 캐시한다.
도착 API(#277)는 손대지 않는다.

## 왜
신고된 27초·40초는 서버 밖(네트워크)이었지만, OTP 가 느려지면 첫 호출이 최대 45초까지 늘 수 있는 구조였다.

## 핵심 결정
- 노선 데이터 적재 전체 예산 5초 → 넘으면 낡은 값, 없으면 503(앱이 "다시 시도")
- 부팅 워밍업은 별도 스레드(healthcheck 를 늦추지 않게), api 에서만
- 역 연결 결과는 원본 패턴이 같을 때만 재사용 → 갱신되면 자동 재계산
- 도착 API 는 Redis 만 읽어 22ms — 코드 변경 없음(근거로 답변)

## 건드는 곳
SubwayRoutePatternPort, OtpSubwayRoutePatternAdapter, SubwayLinePatternService, SubwayLinePatternWarmup(신규), SubwayStationsAheadService

## 리스크
- 워밍업으로 api 힙이 늘어남(전 노선 패턴 상주) → 배포 후 힙 증가분 측정

## 검증법
예산·워밍업·매칭 캐시 단위 테스트 + 기존 회귀 통과. 배포 후 워밍업 완료 로그, 캐시 적중 응답 < 50ms, 힙 증가분 기록.
