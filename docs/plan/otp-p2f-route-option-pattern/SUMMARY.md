# route-option 대안을 OTP pattern 에서 생성 (P2-F PR4b)

## 뭘 하나
주행 중 재탐색의 "같은 정류장 다른 노선" 대안을, journey 시작 때 저장해 둔 목록 대신 OTP 에 실시간으로 물어 만든다. 승차정류장을 지나는 모든 노선 중 하차정류장에도 정차하고 방향이 맞는 것을 후보로 올린다.

## 왜
저장된 대안은 시작 시점에 고정되고 원래 검색이 준 것만 담겨, 같은 정류장의 다른 노선을 다 못 볼 수 있다. OTP pattern 에서 뽑으면 그 정류장을 지나는 노선을 빠짐없이 본다.

## 핵심 결정 (codex 리뷰 반영)
- 방향은 OTP pattern 정차 순서(승차<하차)로 판정 — direction_id 가 feed 에 없어(전 노선 -1) 이것만 가능.
- 🔴 **순수 대체 아님, Stored ∪ OTP union** — resolver 미매핑(방향 suffix `_D/_U` 등) 후보 감소 방지.
- 🔴 **동적 후보 수락 계약 먼저(PR-3)** — 현 수락 로직이 저장 후보를 optionIndex 로 고름 → OTP 후보 수락 시 엉뚱한 노선 선택. OTP 소스(PR-4) 전에 해결.
- 🔴 **PR4c 전 OTP 후보 실제 제안 ON 금지** — ETA 가 노선별 실주행시간 미반영(공통 plannedDurationSec). PR4b 는 후보 집합 확장까지만.
- boardingSeq = bus_route_stop distinct seq 정확히 1개일 때만(저장과 동일 규칙), 반복 정류장 제외.
- source 는 outcome 타입(SUCCESS/장애 구분)+deadline, N+1 방지 bulk 조회, 전용 게이트(RoutingProviderSelector 재사용 금지).

## 건드는 곳
StopPatternPort · OtpStopPatternAdapter · OtpStopPatternMapper · stopPatterns.graphql · RerouteRouteOptionSource(Stored/OtpPattern) · RouteOptionCandidateProvider · OtpSeoulIdentityResolver(재사용) · BusRouteStopRepository(재사용)

## 리스크
- 3단 매핑(OTP route→서울 route_id→boardingSeq) 실패 시 후보가 저장값보다 적어질 수 있음 — PR-3 실데이터 커버리지 측정.
- 순환/지선 pattern 방향 판정 실측 필요.
- ⚠ OTP off 면 이 경로 미동작(폴백). 실제 제안 ON(shadow OFF)은 PR4c 후.

## 검증법
컴파일 + 기존 RouteOptionCandidateProviderTest 무회귀(PR-2 소스 리팩터 후, optionIndex 보존) + 동적 후보 수락 IT(PR-3) + 신규 계약테스트(방향 정차순서/미매핑·미정차·승차금지 제외/union/OTP off·장애 outcome 구분). 티어 L, **4 stacked PR**(PR-1 port → PR-2 Stored seam → PR-3 수락계약 → PR-4 OTP union+게이트). codex 리뷰 완료(2026-09-09).
