# OTP 전용 검색 예산

## 뭘 하나
OTP 검색 호출에 전용 시간예산 `route.search.otp-ms=3500` 을 신설한다. 지금은 ODSAY용 2000ms를 빌려 써 OTP 왕복(~2.0~2.5s)이 매번 timeout → 502. api·worker 둘 다 이 전용 예산을 쓴다.

## 왜
2026-09-16 실 전환 시 검색 5/5 502(readTimeout). gate(PR-1)는 정상, 예산만 부족.

## 핵심 결정
- **decouple**: OTP 경로는 단일 단계라 자기 deadline(otpMs)만 씀. deadlineMs/odsayMs 불변 → ODSAY 회귀 0. (통짜 상향 기각)
- **otpMs=3500**: 실측 OTP p99 1.9s/max 2.04s + 앱 역직렬화 ~0.5s + tail 여유.
- **단일소스**: api·worker OTP 호출 예산을 otpMs 하나로 통일(D2-a).
- **reroute evaluationBudgetMs 3000→4500**: worker OTP 3.5s 검색 후 예측 조기컷 방지.
- OTP 메모리 무관 확인: 부하·버스트에도 1.69GiB 고정(57%), GC 없음.

## 건드는 곳
RouteSearchDeadlineProperties · RouteSearchService · RemainingRouteSearchService · JourneyRerouteProperties · (테스트 3종)

## 리스크
- 느린 tail 요청이 스레드 최대 3.5s 점유(필수 엔진, 의도된 tail).
- 실측은 저부하·5경로 표본 → 토글 후 실 p99 재관찰 필요, 이상 시 상향/롤백.
- reroute 여유 상향 = 주행중 재탐색 최대 대기 소폭 증가.

## 검증법
단위: OTP 경로 deadline=otpMs / ODSAY 경로 odsayMs 유지(회귀) / props 기본값. 배포(dormant)→토글 시 실검색 200 + p99 정상이면 완료.
