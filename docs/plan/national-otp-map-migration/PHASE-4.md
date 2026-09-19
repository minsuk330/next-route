# Phase 4 — 변환 회귀와 소수 경로 비교

## 작업 계약

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다.
선행: P2 서버 계약과 P3 최종 geometry/graph handoff.
목표: 실제 변환 결과의 차이·결함을 확인하고 P5에 사용할 재현 가능한 fixture를 만든다.
수정 범위: 비교 runner/fixture/보고서, 이 단계에서 발견한 변환 결함의 좁은 수정. 계약 변경은 P2/P3 handoff에도 반영한다.

## 수행 순서

1. 기존 검증 결과에서 같은 입력·버전·코드에 적용 가능한 증거를 재사용한다.
2. P0 대표 3~5개 OD를 P2 API에 보내 노선, 승하차, 환승, 이동/대기, geometry 출처와 품질을 기록한다.
3. 필요하고 ODsay 호출이 가능하면 공통 지원 OD 3~5건만 비교한다. ODsay 접근 불가/쿼터 부족이면 선택적 비교 미수행으로 기록하고 변환 회귀는 계속한다.
4. 같은 출발시각을 지원하지 않는 provider끼리 ETA 정확도 점수를 계산하지 않는다. 구조 비교와 정적/실시간 차이를 분리한다.
5. bbox 밖 요청, 잘못된 ID 매핑, 지난 편, shape 결측, 자정 이후 시각, graphVersion 변경의 오류 fixture를 확인한다.
6. 실패를 data/ID/time/geometry/engine/timeout으로 구분하고 재현 명령·수정 또는 노출 제외 사유를 기록한다.

## 산출물과 완료

`conversion-report.md`에 caseId, OD/출발시각, graph/code 버전, 결과 요약, 차이 원인, 판정, 재현 fixture 경로를 기록한다.
원본 사용자 위치·토큰은 저장하지 않고 시험용 좌표를 사용한다.

- [ ] 변경 구간의 필수 회귀가 통과함.
- [ ] 남은 결함과 영향 노선/기능이 명시됨.
- [ ] 결측 형상을 가짜 선으로 보완하지 않음.
- [ ] 소수 조회 지연을 p95/SLO나 전체 수도권 정확도로 일반화하지 않음.

상시 RouteShadowDispatcher, 표본 수집 서버, 100건 holdout, 장기 비교는 만들지 않는다.
`handoffs/PHASE-4.md`에 보고서와 P5 E2E fixture를 연결한다. 다음: [Phase 5](PHASE-5.md).
