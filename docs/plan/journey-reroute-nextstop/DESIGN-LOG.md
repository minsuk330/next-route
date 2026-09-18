# 주행 재탐색 통일(다음정류장 발) — 설계 결정 로그 (append-only, 논의 중)

세션 지속 논의. 확정 전까지 여기 누적. 확정되면 /plan-split L 로 정식화.

## 확정된 방향 (2026-09-11)
- **③(동일정류장 다른버스 특례) 폐기.** 재탐색을 하나의 로직으로 통일 = 통짜 OTP 재plan(④ RemainingPathCandidateProvider 재정의).
- **원점 = 다음 정류장 발** (현재 계획 환승점 고정 아님). 좌표 없음 → 탄 차 실시간 위치 + progress engine "다음 정차" 로 판정.
- **출력 = 후보 경로 리스트 → 사용자 선택.** (수락 흐름 project-journey-reroute-accept 재사용)
- **트리거 = 환승 위험 발생 + 시스템 내부 재탐색 결과 더 빠른 경로 있을 때만 알림.**
- **PR4c(#194) 파킹**(닫음, 브랜치 보존). ③ 전용(RouteRideDurationPort/EtaCalculator override/공정성게이트)이라 통짜 재plan(planConnection 절대시각)에선 불필요=대체됨.

## 근거
- OTP 자가호스팅 = 호출당 비용 0 → ③ 좁힌 이유(ODsay 아낌) 소멸.
- planConnection 은 itinerary 를 절대시각 포함 반환 → 목적지 ETA 직접. rideSec override 트릭 불필요.
- feedback-server-static-search-front-realtime 와 정합(실시간 판정은 주행 재탐색에서만, 검색#1 무관).

## 미결 (논의 중)
### 1. "다음 정류장" 정의 — 리드타임 문제
- 문제: 바로 다음 정차면 급박(알림 읽고 어디 갈지 확인할 시간 부족).
- 후보: (a) N정류장 뒤 고정 (b) 리드타임 T초 이상 첫 정류장 동적 (c) 하이브리드(동적+리스트 다중 하차점).
- 상태: 미정.
### 2. 트리거 — 세부
- 확정: 환승위험 + 내부재탐색 더 빠른 경로. 
- 미결: (a) 자동 알림 = 기존 "제안=사용자요청만" 계약(project-journey-navigation) 과 충돌 → 계약 수정? (b) "더 빠름" 임계값(현 minimumEtaGainSeconds=300 재사용?) (c) cooldown/재알림 억제.
### 3. "현행 유지" 표현
- 다음정류장 발 재plan 이 현재 버스보다 나은 게 없으면? 빈 리스트=알림 안 뜸(트리거가 이미 거름) vs 리스트에 "유지" 포함.
### 4. 지나감 가드
- 선택 시점 재확인: 고른 하차 정류장이 아직 ≥리드타임 남았나(지나갔으면 무효). 기존 함정.
### 5. 리스트 개수/정렬 — 목적지 ETA 순, 현행 대비 개선폭 표시.

## 권장안 v1 (2026-09-11, 확인 대기) — 문서: https://claude.ai/code/artifact/60cc3419-0b36-49f4-8fca-99c40c0849c2
- 서버 원칙: 함수 3개 고정 `replan(원점,출발시각)` / `validate(항목,지금)` / `rank(항목들,현행ETA)`. 알림·목록·수락 전부 재사용.
- **1 원점**: 리드타임 창(차 ETA ≥ T_min=90s ~ 계획 하차점) + 원점 K=3(첫 실행가능·계획하차·중간). 계획 하차점 항상 포함 = 같은 replan 으로 나온 기준선("계속 타면") → 현행유지 표현 불필요(미결3 해소). 표시 3개, 하차점은 정류장수+남은분 병기, 비슷하면 늦은 쪽 "여유" 배지. 탈락: 다음1개고정(급박), N고정(간격 불균일), 사용자 하차점 선선택(2단계).
- **2 알림**: push=한 줄 신호("더 빠른 경로 · 약 N분 단축")만, 서버 스냅샷(TTL 5m) 저장, 사용자가 열 때 validate 로 지나간 항목 제거(전멸 시 1회 재계산), 선택=기존 수락흐름. 임계 300s 재사용, cooldown=(여정,위험에피소드)당 1회. 탈락: 수동만(가치상실), push에 목록(stale), 자동적용(주도권).
- **계약 수정**: "제안=사용자요청만" → "알림은 서버가 먼저, 변경은 사용자만". 자동 적용 없음.
- 리스크: 현행ETA(실시간지연) vs replan(OTP 시간표) 출처 다름 → OTP 낙관 가능. 완화=임계값+추후 OTP 실시간 피드.
- 기본값(전부 설정): T_min 90s / 하차버퍼 30s / K 3 / 표시 3 / 임계 300s / TTL 5m.

## 확정 (2026-09-11) — 실시간 overlay (리스크 "OTP 시간표 낙관" 해소)
- 함수 4번째 `overlay(경로)`: 탑승점마다(원점 첫 탑승+이후 환승 전부) REALTIME → 없으면 ML 예측 → 둘 다 없으면 **경로 통째 제외**. 탑승 확정 후 하류 시각 재계산→다음 환승 anchor 갱신(경로 내 순차, 경로 간 병렬). 지하철 탑승도 동일(barvlDt).
- 재료: PredictTransferUseCase(REALTIME→ML 내장) + TransferPredictionInterpreter + **RouteTimelineComposer**(#2 존치 이유). 현행 계획 ETA 도 같은 predict → 출처 통일(공정성 회복).
- 비용 2단: 시간표 rank 상위 5 → overlay → 최종 rank 3. RoutingDeadline 하나 공유. 예산 소진으로 overlay 못 한 경로 = 미확인 = 제외.
- **신규 리스크: 커버리지**. 실시간=서울 매핑 노선만(경기 없음), ML=146 노선만, 실시간 horizon 1~2대라 먼 환승은 ML 폴백. 비지원 노선 섞이면 제외 폭주→빈 리스트 가능(광역). 제외율 지표로 실측 후 "시간표만·미확인" 배지 여부 재논의. 현재 결정=제외 유지.

## 확정 (2026-09-11) — 경로 선별 = 정류장 축
- **리스트 1축 = 하차 정류장**(창 안 upcoming), 경로는 그 결과. 각 정류장 섹션 헤더 "N분 절감". 사용자는 내릴 곳을 고름.
- 중복 제거 불필요(같은 노선이 여러 정류장에 떠도 정상 — 축이 정류장). 노력 다양화 자동(위=급함/큰절감, 아래=여유). 리드타임 "몇정류장 고정" 논쟁 소멸.
- 유지: 리드타임 하한 90초(창 시작), overlay(정류장당 최선경로 1개에만 실시간·ML), miss-risk 가드(빡빡한 환승 후보 강등/제외), 계획하차점=기준선(절감0, 맨아래).
- 정렬 = overlay 후 목적지 ETA. 동점=환승수→도보→여유.
- **M 캡 = 6** (창 길면 솎음: 첫실행가능·계획하차·사이 균등). **0/음수 절감 정류장 = 숨김**(이득 정류장 + 계획하차만).
- 비용: replan=정류장마다(OTP 요금0, 레이턴시만) / overlay=정류장당 최선1개(외부호출 ≤M).
- 절감 = 현재계획ETA − 그 정류장 최선ETA (둘 다 overlay 후).

## 확정 (2026-09-11) — apply 계약
- 선택=(하차정류장 X, 경로 Y). 적용=현재 RIDE 를 X 절단 + X부터 Y 이식, 새 revision 발행.
- 얼린 과거(X 앞 전부 불변, X는 항상 미래) / 현재 RIDE 하차점 교체 / X 이후 전부 Y로 교체.
- 기존 ⑤ 파이프 재사용(합성→재컴파일→행잠금→조건부update→재확인→반영). 유일 차이=절단 지점이 미래 WAIT 아니라 **진행중 RIDE**.
- 반영 직전 재확인: X 리드 ≥ 30s / X서 Y 첫탑승 boardable / 세션 행잠금+조건부 update / 실패=롤백.
- **새로움/리스크**: 진행중 RIDE 의 alight 주행 중 교체를 progress engine 이 수용해야 함. 플랜 때 코드 확인(copyToRevision 선례).
- 결정: **원탭 대신 확인화면 1장**, **탭~커밋 사이 X 지나감=거절+목록 새로고침**.
- 미결(apply 마지막): 원래 트리거 위험 수락 시 clear+cooldown / 거절 시 재알림 정책.

## 확정 (2026-09-11) — 알림 응답 3갈래 + 재알림
- **수락**: 그 여정 재탐색 완전 종료(cooldown 아님, off). 한 여정 1회. (엣지: 새 경로가 나중에 또 위험해도 안 알림 — 피로 택함, 기록만.)
- **거절**(능동적 닫음): 거절창에 "계속 알림 받기 ☐" 체크. 켜면 이후 계속, 끄면 그 여정 종료. **기본=꺼짐**.
- **무시**(무응답): 서버가 자제. ①같은 위험 재알림 X(TTL 5분 만료) ②새 위험 에피소드(다른 환승/크게 악화)만, 개선폭 지난번보다 유의미 클 때 ③여정당 무응답 알림 **최대 2회** ④알림 사이 **cooldown 5분**.
- 첫 알림은 항상 1회(위험+더빠른경로). 기존 cooldown 포트 재사용.

## 확정 (2026-09-11, brainstorming) — 구조 발견 + overlay 깊이
- **④ RemainingPathCandidateProvider 가 뼈대**: 환승정류장 발 OTP 통짜 재plan→경로→baseline 재측정→평가→후보 이미 존재. 우리 설계 = ④ 일반화(원점 1개→창 K, 정류장축 리스트, 알림계약, apply 진행중RIDE 절단) + ③ 제거. 대공사 아님.
- **overlay 깊이 = A 채택**(전부 strict): 모든 탑승점(첫+이후 환승 전부) REALTIME→ML→둘 다 없으면 경로 제외. ④ 현재는 첫 탑승만 real(이후 시간표+대기0=낙관) → **RemainingPathEtaComposer 를 체인 방식으로 재작성 필요**(각 WAIT 에서 running clock 으로 boarding estimate, 실패=후보 제외, 이후 대기 0 규칙 폐기).
- 대가: 먼 2·3환승은 실시간 horizon 밖→ML→커버 안되면 제외 → 다중환승·광역 경로 빈 리스트 가능. **제외율 지표 실측**. 사용자가 정확성>커버리지 택함.
- 재료 존재 확인: RemainingRouteSearchService(OTP search), RemainingPathEtaComposer(prepare/compose), BoardingTimeEstimator(모드별 predict=realtime→ML), JourneyWaypointResolver, JourneyCompiler(itinerary→leg), JourneyRevisionComposer(⑤ 합성), RerouteAcceptTxService(행잠금), JourneyProgressService(진행판정).

## 확정 (2026-09-15, 리뷰 2라운드 후 A′)
- **OTP 응답에 정류장별 데이터 있음 확인**(schema 실측): Leg.stopCalls[].{stopLocation gtfsId/lat/lon, schedule.time=ArrivalDepartureTime 시간표 도착/출발, realTime.arrival/departure=EstimatedTime} + legGeometry.points. 우리 planConnection.graphql 은 stopLocation 만 뽑아 버림 → 보존만 하면 됨. 블로커1 = "데이터 없음"(X) → "우리 DTO가 버림"(O).
- **A′ 채택**(OTP 전환 진행중·근시일): OTP-네이티브 정류장별 경로 모델(PR-A, OTP 전환 모델과 조율) + 원점 de-transferIndex(PR-B, 34파일+공개 API+proposal 스키마) + composer 중간절단(PR-C). B(leg-경계 축소) 기각.
- PLAN rev3 = 8 PR(A·B·C·1·2·3·4·5). 마이그레이션 2(proposal origin, journey 컬럼).
- ① 애매 해소: PR-2 시간표 rank = 표준 rank() 재사용(입력만 시간표 ETA). 거절+미체크 = reroute_terminated=true(수락과 동일 종료), 거절+체크 = alert_opt_in=true.
- 리스크: 전환 전 시작 여정은 정류장별 데이터 없어 중간하차 불가 → 원점 leg-경계 폴백.

## 확정 (2026-09-15, codex 논의 후 rev4)
- **geometry 게이트·투영 폐기**(사용자): truncated ride 지도선 = board→X 정류장 좌표 이어그림. 시각분할은 stopCall 시각 정확. PR-0/PR-A 단순화.
- codex 반영 rev4: PR-2 의존에 PR-A 추가 / PR-4 의존에 PR-3 추가 / 재탐색 미래 busEtaAt 검색(현 clock.instant 고정) 별도작업 / rank()=PR-1 산출물(ETA→환승수→도보→여유+시간표 정적변형) / PR-A=national OTP 마이그 P2-B/P2-G 합류(중립 StopCall 모델, legacy 경계 유실 해결, SubPathResult 확장 금지) / PR-B=expand→backfill→dual→cutover 5단계, ACTIVE unique=여정당 / candidate 봉투 버전(RemainingPathApply origin) / progress=현재까지 보존(초기화 아님)+X 미지난정차 재확인 / stop식별=(trip,sourceStopSequence,occurrenceIndex) / 알림 reject·dismiss 엔드포인트+멱등+무응답 durable 집계 / RIDE_INTERIOR 초기 버스만.
- 규모 = PR-0 스파이크 + 8 PR.
