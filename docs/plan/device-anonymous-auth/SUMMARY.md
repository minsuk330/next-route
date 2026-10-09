# 로그인 제거 → 기기 익명 계정

## 뭘 하나
독립 앱에서 로그인 화면을 없앤다. 앱이 처음 실행될 때 만든 비밀값으로 `POST /api/auth/device` 를 부르면 서버가 계정과 JWT 를 준다. 앱인토스(토스 로그인)는 그대로다.

## 왜
로그인이 막는 기능은 "데이터 주인·푸시 대상" 구분용이라 기기 단위로 충분하다. 애플의 강제 로그인 거절·애플 로그인 의무·계정삭제 의무가 같이 사라진다.

## 핵심 결정
- users·JWT·user_id 테이블은 그대로, `auth_identity` 에 provider `DEVICE` 만 추가(V62) — 컨트롤러 무수정 (users 컬럼 부활 기각)
- 서버는 비밀값의 sha256 해시만 저장 — IDFV 는 비밀이 아니라 신원 기각
- 업데이트한 애플·카카오 사용자만 Bearer 로 기존 계정에 기기 연결(최대 3대, 토스 전용 계정은 거절) — 데이터 보존용 한시 통로
- 새 계정 생성만 IP 기준 시간당 60건 제한, Redis 장애 시엔 통과(신규 사용자 차단보다 낫다)
- 애플·카카오 로그인 코드는 구버전 앱용으로 당분간 유지, 연결 통로와 함께 후속 PR 에서 삭제

## 건드는 곳
V62 마이그레이션, AuthProvider, AuthIdentity, DeviceLoginService, DeviceAuthController, RedisDeviceSignupLimitAdapter, MeResponse, application.yaml, AuthIdentityConstraintIT·신규 테스트, HANDOFF.md, implementation_note.md

## 리스크
- 기기 교체·안드로이드 재설치 시 데이터 유실(수용함)
- 업데이트 전에 기존 토큰이 확정 만료된 사용자는 새 계정이 된다
- V62 VPS 수동배치를 빠뜨리면 기기 가입이 DB 제약에 막힌다

## 검증법
분기표·연결 조건·400/403/409/429·경합 단위 테스트, DB 제약 IT, 끝단 IT(발급→즐겨찾기→초기화→재발급 시 새 계정)가 통과하고 기존 auth 테스트에 회귀가 없으면 완료다.
