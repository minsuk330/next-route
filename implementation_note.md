# 구현 노트

구현 중 발견한 사항을 append-only 로 누적하는 실측 로그. 규칙은 `CLAUDE.md` 의 "구현 노트" 절 참고.

- PLAN.md = 무엇을 할지(승인 산출물) / 이 파일 = 실제로 뭐가 튀어나왔는지(실측)
- 틀린 항목은 삭제하지 말고 `~~취소~~ 이유` 로 덮어쓴다
- 컨텍스트 압축 직후 첫 행동으로 이 파일을 읽는다

## 템플릿

```markdown
## YYYY-MM-DD <작업 slug>
### 확정
- 코드만 봐선 안 나오는 결정·계약
### 함정
- <증상> ← <원인> / <가드>
### 좌표
- path/To/File.java:123 — 무엇의 진입점인지
### 미결
- 결정 안 된 것 + 왜 미뤘는지
### 배포
- 마이그레이션 번호 / 플래그 기본값 / 설정 키
### 검증
- 실제로 돌린 명령과 결과
```

---

## 2026-08-13 journey-reroute-accept (Phase 6 ⑤ PR-1·PR-2)

### 확정
- **합성 순서 뒤집기가 옳았다**(codex 지적). 시험이 추가 결함 하나를 더 잡음: 교체 시작점이 원본
  구간 **한가운데**(RIDE 단계)면 짝 대기 단계만 앞에 남아 종류는 같은 채 다른 노선을 가리킨다 →
  `snapshot-unmappable` 로 거절. 가드: `cut == subPathIndexOf(fromLegIndex - 1)`
- `journey_event.eventId` 는 **UUID 타입**이라 문자열 결정론 키를 못 쓴다 →
  `UUID.nameUUIDFromBytes("reroute-accept:{proposalId}:{candidateId}")`
- **마이그레이션은 .gitignore 화이트리스트**에 넣어야 커밋된다(`!src/.../V43__*.sql`). 안 넣으면
  조용히 untracked 로 남아 CI·다른 개발자 환경에서 컬럼이 없다
- Testcontainers IT(`AbstractJourneyOutboxPostgresIT` 계열)는 **flyway 를 끄고 `@Sql` 로 마이그레이션을
  나열**한다. 새 마이그레이션은 그 목록 3곳(StoreIT·ExpiryIT·SchemaIT)에도 넣어야 한다

### 함정
- `journey_notification` 에는 `available_at` 칸이 없다(`scheduled_at`·`expires_at`·`logical_checkpoint_key`·
  `delivery_key` 가 필수) — IT 픽스처 작성 시 주의
- 새 포트를 컨트롤러에 주입하면 기존 `@WebMvcTest` 가 빈 부족으로 전부 깨진다 → `@MockBean` 추가

### 좌표
- application/journey/service/JourneyRevisionComposer.java:75 — 합성→재컴파일→앞 구간 검사
- application/journey/service/RerouteAcceptTxService.java:90 — 세션 잠금→조건부 update→재확인→반영
- domain/journey/entity/JourneyLeg.java:copyToRevision — 진행도·증거·시각 복제(빌더로는 불가)

### 배포
- **V43 수동 배치 대상**. `journey.reroute.enabled` 계속 false

### 검증
- `./gradlew test --tests '*Reroute*' --tests '*Journey*'` 통과(composer 6 + acceptIT 9 포함)
## 2026-08-13 journey-subway-boarding-evidence (M7 PR-3)

### 확정
- 마이그레이션 번호 **V44/V45** 확정 (V43 은 `add_reroute_proposal_accepted_candidate` 로 이미 사용 중).
  PLAN 이 예측한 번호와 일치
- **고정은 `RIDE_SUBWAY` leg 이 소유한다.** 노선 후보(`journey_leg_route_option`)는 `WAIT_*` 가
  소유하므로 축을 나눈다. 앱이 대기 단계 번호로 보내도 서버가 짝 RIDE 로 옮겨 적고,
  응답에 `legIndex` 를 돌려줘 앱이 다음 요청을 제대로 보내게 한다
- **정차 관측이 먼저 와도 binding 을 연다.** 이동 시작을 놓쳤다고 그 뒤를 통째로 버리면 판정
  기회가 사라진다. 단 그때는 `boardedDetectedAt`·`source` 를 비운다 — 정차만으로 지어내지 않는다
- **기기 시각이 범위 밖이어도 관측을 버리지 않는다.** `detail.clockSuspect=true` 로 표시만 한다.
  버리면 시계가 틀어진 기기는 영영 안내를 못 받고 그 사실을 아무도 모른다
- **`ABANDONED` 단계에는 새 관측을 얹지 않는다.** 사용자가 "안 탔다"고 한 뒤 늦게 온 관측이
  그 결정을 뒤집으면 안 된다
- `USER_RESET` 감사행에 **거부한 열차 identity 를 남긴다**. 이게 없으면 판정기(PR-4)가 같은
  관측을 다시 읽어 방금 거부한 열차를 그대로 고른다
- reset 은 행을 지우지 않고 `PENDING` + `version++` + `resetGeneration++` + 순번 비우기.
  지우면 version 이 0 으로 돌아가 늦게 온 CAS 가 되살아난다

### 함정
- `record` 컴포넌트와 같은 이름의 메서드를 얹으면 컴파일 에러 ← PR-2b 에서 이미 겪음 /
  가드: DTO 검증 메서드는 `isXxx()`·`hasXxx()` 로 이름을 달리한다
- 감사행 unique 에서 `event_type` 을 빼면 `MOVEMENT_START` 와 `STATION_STOP_EPISODE` 의
  1번이 충돌해 한쪽이 조용히 사라진다 ← 둘 다 `kind='EVIDENCE'` /
  가드: IT `movementAndEpisodeShareTheSameSequenceSpace`

### 좌표
- resources/db/migration/V44__create_subway_leg_binding.sql — 고정 상태(1:1, 삭제 안 함)
- resources/db/migration/V45__create_subway_binding_event.sql — 관측·판단 로그 + 후보 원본
- domain/journey/entity/SubwayLegBinding.java — resetByUser()/abandon()/advance*Seq()
- application/journey/service/SubwayBoardingEvidenceService.java — 수신·보정 본체
- application/journey/service/SubwayBoardingEvidenceService.java:rideLegOf — WAIT→RIDE 정규화
- infrastructure/adapter/in/api/journey/JourneySubwayBindingController.java — 3개 엔드포인트

### 배포
- **V44·V45 는 VPS 수동배치 필요** (V18+ untracked convention). 누락 시 프로덕션 깨짐
- 신규 설정 `journey.subway.binding.*`, 기본 off. 지금은 `enabled` 를 아무도 안 읽는다 —
  판정기(PR-4)가 붙을 때 쓴다
- **이 PR 만으로는 상태가 영원히 PENDING** 이다. 판정기가 없어서다. 의도한 기본 상태

### 검증
- `./gradlew test --tests '*SubwayBoardingEvidenceServiceTest'` → XML 확인 17건, 실패 0
- `./gradlew test --tests '*SubwayBindingSchemaIT'` → 12건, 실패 0 (마이그레이션 실제 적용 확인)
- `./gradlew build` 전체 → 1,689건, 실패 0, 오류 0
