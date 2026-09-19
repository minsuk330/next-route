# Phase 5 — 모바일·Journey 통합 검증

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다.
선행: P2 api-contract.md, P3 최종 graph/geometry/지도 fixture, P4 conversion-report.md.
수정 범위: 배정된 모바일 저장소 API 타입·화면·로컬 store와 서버 통합 결함의 최소 수정. 실제 모바일 경로를 확인한다.
다음: [Phase 6](PHASE-6.md). 목적: 사용자가 선택한 OTP 경로가 여정 종료까지 유지됨을 검증한다.

## P5-A — 앱 계약과 화면

- [ ] 별도 모바일 저장소의 API 타입에 provider/version/mode/geometry quality/coverage/actualSource/fare availability를 추가한다.
- [ ] 정적 시간표와 실시간/ML을 구분하고, 지원 범위와 실제 예측 출처를 혼동하지 않는다.
- [ ] bbox 내 버스/도시철도라도 네비 미지원이면 시작을 제한한다. bbox 밖과 일반/고속철도는 이번 검색 노출에서 제외하고 서버에서도 검증한다.
- [ ] unknown 요금을 0원으로 그리지 않는다. 편의 출발/도착·대기·승차시간을 분리해서 표시한다.
- [ ] geometry 품질 저하 메시지와 Naver tile 장애 메시지를 구분한다.
- [ ] 구형 앱 capability 부재는 legacy로 취급한다. 새 수단 enum을 강제로 보내지 않는다.

## P5-B — 여정과 geometry 교체

검증 순서:

1. 검색 결과 선택 → 서버 snapshot으로 Journey 시작.
2. 선택 경로 전체 geometry 조회 → (journeyId, routeRevision, geometryVersion) 기준 로컬 저장.
3. 현재 위치/leg 강조 및 진행 보고. 표시 단순화로 progress segment 순번을 바꾸지 않는다.
4. 환승 대안 검색 → 새 후보 geometry/version 표시 → 수락.
5. 새 revision snapshot/geometry를 모두 준비한 후 앱 상태를 원자 교체한다. 옛 revision 응답은 폐기한다.
6. 종료/취소 후 기존 알림·binding·로컬 진행 상태가 정리된다.

- [ ] 서버는 graph가 바뀌어도 기존 Journey geometry를 현재 graph로 재생성하지 않는다.
- [ ] 새 schema snapshot의 WAIT/RIDE와 현재 ETA composer가 대기를 중복 합산하지 않는다.
- [ ] 재탐색 수락과 progress/알림 발송 경합에서도 이전 revision 알림이 노출되지 않는다.
- [ ] 네트워크 끊김·앱 복귀·서버 재시작 후 snapshot으로 복원한다. 앱 강제종료 정책은 기존 Journey 계약을 따른다.
- [ ] 서울 버스 하차 local/환승 FCM 등 기존 알림 정책을 provider 전환으로 변경하지 않는다.

## 통합 시험표

| 사례 | 기대 |
|---|---|
| 서울 검증 버스+도시철도 | ML/실시간 지원 유지, 정상 시작/진행 |
| 미매핑 서울/비서울 버스 | 정적 표시, capability 충족 여부로 시작 판정 |
| 범위 밖/일반·고속철도 요청·위조 결과 | 검색 노출 제외, 서버 시작/수락 거부 |
| 지연으로 다음 열차 놓침 | 재선택 또는 후보 제외, 지난 편 추천 없음 |
| 신규 graph 배포 중 활성 OTP Journey | 기존 버전 조회·진행·종료 유지 |
| provider ODsay rollback | 새 검색 전환, 활성 OTP Journey 유지 |
| 구형 앱/구형 snapshot | legacy 호환, 신규 수단 오분류 없음 |
| stale geometry 응답/수락 경합 | 현재 revision과 일치하는 데이터만 반영 |

## 성능과 완료

- [ ] 기존 실기기 증거를 재사용하고 변경된 API/geometry와 지도·복귀를 확인한다. 접근 가능한 기기/플랫폼과 미검증 항목을 기록한다.
- [ ] GPS 1초 갱신 중 geometry를 매초 decode/render 전체 재생성하지 않는지 확인한다.
- [ ] 위치 처리/렌더링 변경이 있으면 기존 baseline과 비교한다. 무관한 장시간 배터리 시험을 새로 요구하지 않는다.
- [ ] 앱 버전 + 서버 image + graphVersion + 데이터 승인 범위를 release evidence에 기록한다.
- [ ] 시간 불변식과 핵심 E2E를 통과한 앱/서버/graph 조합을 P6에 인계한다.

초기 성능 목표는 전체 API p95 3초, 지도/overlay p95 1.5초다. API 시간은 응답 수신까지, overlay 시간은 geometry 사용 가능 시점부터 표시 완료까지 구분해 측정한다. 네트워크·타일 수신 시간도 따로 보고해 목표를 유리하게 해석하지 않는다.


## 완료와 인계

`handoffs/PHASE-5.md`에 실제 모바일 저장소/파일, 앱·서버·graph 버전, E2E별 결과와 미검증 플랫폼을 기록한다. 서버만 배정되거나 모바일 접근이 없으면 서버 회귀와 앱 전달 계약을 완료하고 전체 상태는 PARTIAL로 남긴다. 다음: Phase 6.
