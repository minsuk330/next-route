# 수도권 OTP 길찾기 전환 계획

기준일: 2026-09-07. 최신 의도: [intent.md](intent.md).
에이전트 실행 방법: [AGENT-GUIDE.md](AGENT-GUIDE.md). 단계별 작업: [IMPLEMENTATION.md](IMPLEMENTATION.md).

## 1. 목표와 범위

ODsay 호출 제한에 의존하는 최초 검색·남은 경로 재탐색을 OSM + OTP로 전환한다. 기존 호출량 절감용 기능 제한도 원인을 조사해 해제한다.

운영 범위는 기존 bbox `126.5,37.2,127.4,38.0` 안에서 검증된 버스·도시철도다. “수도권”은 이 범위의 이름이며 서울·경기·인천 전역을 뜻하지 않는다. 범위 밖 요청과 경로는 OUT_OF_SCOPE로 처리한다. 일반/고속철도는 이번 검색 노출 대상에서 제외하고, 원천 타입은 보존해 버스로 잘못 변환하지 않는다.

입력은 `202503_GTFS_DataSet`과 수도권 OSM이다. 전국 graph 및 2024 benchmark는 기존 비교 증거로만 보존한다. 용량 기록은 [capacity-report.md](capacity-report.md)를 재사용한다.

## 2. 공통 계약

| 항목 | 구현 결정 |
|---|---|
| 라우팅 | 내부 OTP를 공통 RoutingPort 뒤에 연결. 모바일은 NexRoute API 호출 |
| 지도 | Naver 우선, 실제 호환성 부족 시 OSM 기반 지도 전환 |
| 대중교통 형상 | OSM route relation을 검증·조립해 사용. 정류장 직선/도보 우회 형상 금지 |
| 도보 형상 | OTP OSM 도보 경로 |
| 시간 | offset 있는 departureAt을 한 번 확정. 내부 Instant/초, feed 시간대로 serviceDate 해석 |
| 서비스 기간 | 원본 calendar는 보존하고 OTP build 창만 `-P1D`~`P2M`으로 제한. 창 만료 전 graph 재생성 |
| 대기 | userReadyAt 이후 탈 수 있는 편의 출발시각 차이. 정적 대기와 실시간 대기를 중복 합산하지 않음 |
| 실시간·ML | 기존 지원 기능 보존. GTFS↔공급자 ID/방향/정류장 순번이 검증된 대상에 적용 |
| 미매핑 | 수도권 내 정적 검색 유지, STATIC_SCHEDULE과 사유 표시 |
| 요금 | 원천에 없으면 unknown. 0원으로 생성하지 않음 |
| 네비 지원 | 수단·형상·ID·진행 계약을 충족한 경로만 서버 시작/수락 허용 |
| 캐시 | provider + graphVersion + schema/options/capability + OD + departureAt. 지난 편 재검증 |
| 여정 | 기존 snapshot reader 유지. 새 경로는 수락 시 새 revision으로 원자 교체 |
| 배포 | immutable graph와 실제 버전 확인, 새 검색 rollback과 활성 여정 복원 구분 |

원천 ID는 문자열로 보존하며 feed/agency namespace를 포함한다. GTFS stop_sequence를 서울 API 순번이나 sectionId로 간주하지 않는다. 번호만 같은 버스를 매칭하지 않는다. coverage(지원 능력)와 actualSource(이번 계산 출처)를 구분한다. Phase 8의 신규 enum은 기존 계약 확인 후 확정하며 불필요한 이름 변경을 하지 않는다.

## 3. 데이터와 형상 경계

P1은 bbox 안의 온전한 trip을 선별한다. 경계 밖 stop을 지워 우회·정차 생략 경로를 만들지 않는다. 참조 폐쇄를 유지하고 제외 trip/route/agency와 사유를 보고한다. 포함 노선의 실제 커버리지는 산출물에 기록한다.

P3는 relation의 way 연결, 방향, GTFS 정류장 occurrence 순서, 승하차 anchor, gap을 확인한다. 성공한 형상만 네비에 사용한다. relation 결측은 빈 선/품질 사유로 표시하며 선을 만들어 채우지 않는다. 표시 단순화와 진행 원본을 분리하고 인접 section은 절단 anchor를 공유한다.

P1 기본 graph → P2 adapter/매핑 → P3 relation 형상 생성 → P1 build 명령 재사용으로 최종 graph 생성 순서다. P1에서 형상 없는 graph를 만들 수 있으나 P3 완료 전 형상·네비 완료로 표시하지 않는다. OSM relation 수집을 위해 원본 PBF를 읽어도 운영 bbox를 확장하지 않는다.

## 4. ETA와 재탐색

출발 → 도보 → userReadyAt → 탑승 가능한 편 선택 → 대기 + 승차 → 환승 도보 → 다음 편 순서로 계산한다. 최초 대기도 총시간에 포함한다. 실시간 지연으로 이후 편을 놓치면 변경된 준비시각으로 남은 경로를 재검색한다.

동일 승하차·방향의 routeOptions를 먼저 평가하고, 없으면 남은 경로 검색을 수행한다. 각 후보 자신의 승차시간과 이후 환승을 계산한다. 개선폭은 같은 calculatedAt 기준의 최종 도착시각으로 비교한다. 저장 전 ACTIVE/current revision을 재확인한다.

기존 제한은 정책 목록에 코드 위치·목적·영향·변경을 기록한다. ODsay 비용 때문에 막힌 후보 평가·재탐색은 복원한다. 전체 deadline, 동시성 제한, 중복 알림 억제는 별도로 설정하고 재귀적 무한 재검색을 막는다.

## 5. 단계와 완료 조건

| Phase | 담당 결과 | 필수 확인 |
|---|---|---|
| 0 | 기존 증거·202503 입력·범위·제한 목록 | 다음 작업의 실제 입력 경로와 누락 항목 |
| 1 | 수도권 정규화·graph build/load | 참조·시각·범위 무결성, 소수 검색 smoke |
| 2 | 검색/재탐색·ID/시간/cache/snapshot 전환 | 계약·시간축·활성 여정 회귀 |
| 3 | OSM relation 형상·Naver 연결 | 연속성/순서/표시, 결측 처리 |
| 4 | 변환 검증 보고 | 기존 증거 + 필요 시 ODsay 3~5건 비교 |
| 5 | 모바일·Journey 통합 | 시작→진행→재탐색→수락→종료/복원 |
| 6 | 배포·복구 절차 및 배정 환경 전환 | 실제 설정 반영과 rollback 재현 |
| 7 | OTP 기본값·ODsay 검색 의존 정리 | 최초/남은 검색 ODsay 비의존, 구 여정 유지 |
| 8 | 후속 수도권 실시간 공급자 | 별도 배정된 공급자 매핑/시간/장애 검증 |

## 6. 검증·배포 원칙

기존 테스트를 활용하고 변환으로 달라진 부분만 검증한다. 상시 shadow dispatcher, 100건 holdout, 7일 비교, 1→10→50→100% 확대를 이번 전환의 필수 작업으로 요구하지 않는다. 소수 smoke로 p95/SLO 달성을 주장하지 않는다.

배포 전 확인할 것은 graph load·실제 query, 지원 범위, 대기 중복/탈 수 없는 편, 새 구 snapshot 호환, 모바일 계약이다. 초기 지연 목표 API p95 3초와 overlay p95 1.5초는 측정 목표이며 표본·조건 없이 충족으로 쓰지 않는다.

탈 수 없는 편 추천, 시간 역전, 지역 ID 오매핑, 범위 밖 노출, 미지원 Journey 시작, 기존 여정 손상은 발견 시 영향 범위 전환을 중단한다. 설정 또는 재배포를 통한 기존 provider/graph 복구 명령을 준비한다. 활성 OTP 여정의 reader·geometry·mapping은 rollback 후에도 유지한다.

실제 배포 여부·검증 누락·남은 데이터 최신성 한계는 handoff에 명시한다. 신규 입력의 서비스 날짜를 임의로 연장하거나 다운로드 날짜를 시간표 최신성 증거로 쓰지 않는다.
