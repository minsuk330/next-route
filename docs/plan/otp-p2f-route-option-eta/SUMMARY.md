# route-option 노선별 실주행 ETA (P2-F PR4c)

## 뭘 하나
주행 중 재탐색 "같은 정류장 다른 버스" 후보의 목적지 도착 계산에서, 타고 가는 시간(승차→하차)을 원래 노선 값이 아니라 **후보 노선의 시간표 소요(OTP)**로 바꾼다. 이게 채워지면 OTP 후보의 실제 제안 ON(shadow OFF)이 가능해진다.

## 왜
PR-4(#193)가 후보 노선을 넓혔지만 우열 ETA 가 노선별 소요를 반영 못 해 부정확했다(codex B2). PR4c 가 그 정확도를 채운다.

## 핵심 결정 (codex 리뷰 반영)
- **A안(경량)**: 타고가는 소요 한 조각만 노선별 실값 교체. 나머지 leg 은 상쇄되어 안 건드림. (B안=composer 통짜 재평가 기각)
- **데이터=OTP `Trip.stoptimes`(시간표값)**: alight 도착 − board 출발 = 노선 소요. 실주행 아님 → 명칭 정직화.
- **공정성 게이트**: baseline·후보 **둘 다 OTP 소요 OK 일 때만** 실제 제안 승격. 하나라도 폴백이면 shadow 만(불공정 비교 배제). 화면 confidence 재사용 금지.
- **baseline OTP id**: 저장 컬럼은 OTP gtfsId 아님(busLocalBlID). → board 정류장 재조회+resolveBus 로 되찾기(저장 의존 X). 온전 보존은 마이그레이션(별건 후속).
- **override 대상**: 대기 직후 정확한 `RIDE_BUS` leg 만("첫 non-wait"는 WALK 오대체 위험). 없으면 후보 폐기.
- **OTP 호출**: `pattern.trips` 확장 금지 → 대표 trip.gtfsId 얻어 ≤11개 `trip(id)` alias 일괄. 캐시 키=graphVersion+trip+serviceDate+board+alight.

## 건드는 곳
RouteRideDurationPort(신규)·OtpRouteRideDurationAdapter/Mapper(신규)·routeRideDuration.graphql(신규)·RouteOptionEtaCalculator·RouteOptionCandidateProvider.

## 리스크
- 예산 이중소비: predict+duration 이 같은 budgetMs 쓰면 총 대기가 예산 초과 → 단계별 남은 deadline 공유 or 예산계약 명시 변경(선행).
- baseline OTP 되찾기 실패율 = 승격 불가율. 실측 후 마이그레이션 여부 판단.
- OTP 호출 최대 11회 → deadline 안. trip batch 응답크기 fixture 측정.

## 검증법
컴파일 + 신규(mapper delta/어댑터 상태분류/EtaCalculator override 하위호환/provider comparable 게이트·폴백·공정성) 그린 + RouteOptionCandidateProviderTest·④·composer 무회귀 + RerouteAcceptIT 부팅.
