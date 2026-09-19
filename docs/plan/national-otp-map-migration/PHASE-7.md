# Phase 7 — OTP 기본 전환과 ODsay 검색 의존 정리

## 작업 계약

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다.
선행: P6 복구 가능한 배포 구성·검증 결과. 실제 운영 의존 삭제는 전환 상태와 활성 여정 참조를 확인한 뒤 수행한다.
목표: 수도권 최초 검색과 남은 경로 검색이 ODsay 호출량에 의존하지 않도록 완료한다.
수정 범위: provider 기본 설정, route search 의존·사용하지 않는 job/env/cache, 배포 문서.

## 수행 순서

1. P6 결과에서 배포 준비와 실제 전환 여부를 구분한다. 전환 전이라도 OTP 기본 설정용 코드/테스트는 준비할 수 있다.
2. `OdSayApiPort` 및 관련 호출을 검색해 라우팅/형상/장소/요금/legacy reader/active Journey/rollback 사용처 표를 만든다.
3. 최초 검색과 RemainingRouteSearchService가 OTP로 동작하도록 기본값과 provider 정책을 정리한다.
4. ODsay 라우팅 호출을 사용하지 않는지 테스트와 실제 배정 환경의 호출 기록으로 확인한다. 인증 실패만으로 호출 제거를 입증하지 않는다.
5. P0 policy-inventory의 비용 절감용 제한이 해제/복원됐는지 P2 결과와 대조한다. 누락은 수정한다.
6. 활성 snapshot reader·geometry·mapping과 복구에 필요한 의존은 보존한다. 미참조 job/env/cache만 제거한다.
7. 원본 데이터·기존 section 테이블·배포된 migration을 일괄 삭제하지 않는다. DB 정리가 필요하면 별도 신규 migration과 참조 검증을 준비한다.

## 필수 확인

- [ ] ODsay routing 실패/차단 조건에서도 OTP 최초 검색·재탐색 성공.
- [ ] 기존 여정과 새 OTP 여정 조회·진행·수락·종료 유지.
- [ ] bbox 밖 검색 거부, 미지원 수단 오분류 없음.
- [ ] ODsay 비용만을 이유로 막힌 기능 복원 확인.
- [ ] 불필요한 의존 제거 목록과 보존 이유 기록.

7일 shadow나 전체 재부하 시험을 추가하지 않는다. 배정된 배포가 미실행이면 코드 완료와 운영 전환 완료를 구분한다.

## 최종 인계

`handoffs/PHASE-7.md`에 변경 파일, 제거/보존 사용처, 회귀 결과, 실제 배포 상태, 남은 한계를 남긴다.
1차 완료는 bbox 내 검색·재탐색과 검증된 여정의 OTP 전환이다. [Phase 8](PHASE-8.md)은 별도 후속 배정이다.
