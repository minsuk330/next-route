# 수도권 OTP 전환 — 에이전트 작업 배정표

[AGENT-GUIDE.md](AGENT-GUIDE.md)의 공통 실행 계약과 [intent.md](intent.md)를 모든 Phase에 적용한다.
각 Phase 문서는 에이전트에게 직접 전달할 작업 명세다. 계획 체크박스는 완료 증거가 아니며 `handoffs/PHASE-N.md`와 실제 코드를 함께 확인한다.

## 배정 순서

| 배정 | 시작에 필요한 입력 | 다음 에이전트에 전달할 결과 |
|---|---|---|
| [Phase 0](PHASE-0.md) | 기존 실험·현재 코드 | 입력/범위 manifest 초안, 제한 목록, 재사용 증거 |
| [Phase 1](PHASE-1.md) | P0 입력 경로/범위 | 정규화 GTFS, 기본 graph, 재생성 명령 |
| [Phase 2](PHASE-2.md) | P1 query 가능한 graph | API 계약·fixture·서버 구현, 정책 변경 목록 |
| [Phase 3](PHASE-3.md) | P1 데이터 + P2-B/D 계약 | relation 형상·최종 graph·지도 fixture |
| [Phase 4](PHASE-4.md) | P2/P3 결과 | 변환 회귀·선택적 비교 보고 |
| [Phase 5](PHASE-5.md) | P2 API + P3 형상 + P4 보고 | 앱/서버 E2E 결과와 배포 조합 |
| [Phase 6](PHASE-6.md) | P5 검증 조합 | 배포·rollback 명령과 배정 환경 실행 결과 |
| [Phase 7](PHASE-7.md) | P6 복구 가능 상태 | OTP 기본 전환·ODsay 검색 의존 정리 |
| [Phase 8](PHASE-8.md) | P2 시간/ID 계약 + 후속 공급자 배정 | 해당 공급자의 검증된 실시간 연결 |

기본은 순서대로 배정한다. Phase 2는 A→B→C/D→E/F→G→H로 진행한다. Phase 3는 P2-B/D 계약 확정 후 시작할 수 있으나 같은 파일을 동시에 수정하지 않도록 작업 범위를 나눈다. P3 형상을 graph에 넣을 때 P1의 build 도구를 재사용하며 P1 전체를 다시 수행하지 않는다.

Phase 3의 모바일 표시와 Phase 5의 앱 여정 통합은 모바일 저장소 접근이 필요하다. 사용자가 말한 `nextroute-mobile`을 시작점으로 실제 저장소를 확인한다. 이전 문서의 `nextroute-app` 경로를 사실로 가정하지 않는다.

## 그대로 전달할 지시문

```text
docs/plan/national-otp-map-migration/PHASE-N.md를 수행해.
먼저 AGENT-GUIDE.md, intent.md, PLAN.md와 선행 Phase handoff를 읽고,
현재 코드에서 이미 구현된 부분을 확인한 뒤 남은 작업을 구현해.
수도권 bbox·202503 GTFS·OSM relation 형상·Naver 우선 결정을 지켜.
기존 실험은 재사용하고 변경 구간을 검증해.
완료 후 handoffs/PHASE-N.md에 변경 계약, 검증 명령/결과,
다음 Phase 입력과 미완료 항목을 남겨. 배정받지 않은 다음 Phase로 넘어가지 마.
```

Phase 6/7에 실제 배포까지 배정할 때는 대상 환경·서비스·사용할 artifact를 함께 지정한다. Phase 8은 공급자를 정해 별도 배정한다.

## 완료 판정

코드 구현, 필수 검증, 산출물/재현 명령, 후속 계약이 모두 있어야 COMPLETE다. 예컨대 서버만 완료하고 모바일 실기기 확인이 남으면 PARTIAL로 보고한다. 새 문서 작성, graph 파일 존재, HTTP 200만으로 전체 전환을 완료로 판정하지 않는다.
