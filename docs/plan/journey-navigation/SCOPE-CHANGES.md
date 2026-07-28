# 범위 변경 기록

REQUIREMENTS.md §8 규칙에 따라 LOCKED 요구사항의 변경을 기록한다. 변경마다 이유·영향 ID·인수 기준 변경을 남긴다.

---

## SC-1 — 다중 기기 개념 제거 (2026-07-28)

### 결정

`deviceId` · `activeDeviceId` · 기기 세션 · device handoff 를 **1차 범위에서 제거**한다. Journey 는 `userId` 단위로만 식별하고, 어느 기기가 안내 중인지 추적하지 않는다.

### 이유

사용자가 여러 기기로 하나의 여정을 관리하지 않는다. 기기 추적을 위해 필요한 것들 — 앱 설치 식별자(`installId`), `user_device` 테이블, JWT `did` claim, `@CurrentDevice` resolver, handoff API — 이 전부 쓰이지 않을 기능을 위한 비용이 된다.

### 영향 받는 ID

| 문서 | 항목 | 변경 |
|---|---|---|
| REQUIREMENTS §2 | 적용 원칙 2줄 | `deviceId` 관련 원칙 삭제 |
| REQUIREMENTS §4.9 | **C4** 활성 안내 기기가 아닌 기기의 상태 변경 차단 | **삭제** |
| PLAN §2 | 인증 선행 계약 | 기기 등록·`deviceId` 판별 항목 삭제 |
| PLAN §4.1 | `JourneySession.activeDeviceId`, `startedByDeviceId` | **컬럼 삭제** |
| PLAN §4.1 | 다른 기기 조회·handoff 규칙 2줄 | 삭제 |
| PLAN §4.4 | `JourneyEvent.actorDeviceId` | **컬럼 삭제** |
| PLAN §4.4 | heartbeat "인증된 activeDevice의" | "인증된 사용자의" 로 완화 |
| PLAN §4.5 | `device_push_token` 의 `device` 필드 | `user_push_token(user, token, ...)` 으로 변경 |
| PLAN §5.1 | 최초 `activeDeviceId` 기록 | 삭제 |
| PLAN §5.2 | `POST /api/journeys/{id}/device-handoff` | **엔드포인트 삭제** |
| PLAN §5.3 | `PUT/DELETE /api/me/device/push-token` | `/api/me/push-token` 으로 변경 |
| PLAN §7 Phase 1 | device handoff 처리 | 삭제 |
| PLAN §7 Phase 2 | 비활성 기기 이벤트·handoff 경쟁 테스트 | 삭제 |
| Phase 1 PR | `journey-auth-device` (기기 세션) | **PR 전체 삭제**. Phase 1 은 4개 → 3개 PR |

### 인수 기준 변경

- **삭제**: C4 관련 테스트("비활성 기기의 상태 변경이 거절된다")
- **유지**: C2 `baseCommandVersion` 낙관적 락. 같은 사용자가 두 곳에서 동시에 조작해도 lost update 는 여전히 막는다
- **완화**: 여정 푸시는 사용자의 등록된 모든 FCM token 으로 발송한다. "안내 실행 기기에만 발송"은 더 이상 인수 기준이 아니다

### 수용하는 리스크

| 리스크 | 판단 |
|---|---|
| 사용자가 두 기기에 로그인하면 여정 푸시가 양쪽에 간다 | 수용. 단일 기기 사용 전제 |
| 두 기기가 상충하는 상태 이벤트를 보내면 어느 쪽이 맞는지 서버가 모른다 | 수용. `baseCommandVersion` 충돌(409)로 lost update 만 방어 |
| 기기를 교체하면 이전 폰의 FCM token 이 남는다 | Phase 5 에서 provider 의 `UNREGISTERED` 응답으로 비활성화. 기기 테이블 없이도 처리 가능 |

### 되돌리는 비용

나중에 다중 기기가 필요해지면 `user_device` 테이블 + JWT `did` claim + `activeDeviceId` 컬럼을 추가하는 마이그레이션 한 번이면 된다. 지금 만들어 두는 것과 비교해 구조적 손해가 크지 않다.
