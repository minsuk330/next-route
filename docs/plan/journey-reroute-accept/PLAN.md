# Phase 6 ⑤ — 재탐색 수락 (`journey-reroute-accept`)

- 설계 근거: [DESIGN.md](./DESIGN.md) — 결정의 *이유*는 전부 그쪽에 있다. 이 문서는 *무엇을 어떻게*만 적는다
- 상위: [PHASE-6.md §⑤](../journey-navigation/PHASE-6.md) · 선행 PR: ①(#135) ②(#133) ③(#136) ④(#139, #140)
- 마이그레이션: **V43 한 건**(`reroute_proposal.accepted_candidate_id`)
- 작성일: 2026-08-13 · **codex 리뷰 반영: 2026-08-13**

## PR 두 개로 나눈다

| | 슬러그 | 티어 | 내용 | 선행 |
|---|---|---|---|---|
| **PR-1** | `feat/journey-geometry-revision-source` | **M** | geometry 원천을 그 개정본의 스냅샷으로 옮긴다(**동작 불변**) | 없음(④와 독립) |
| **PR-2** | `feat/journey-reroute-accept` | **L** | 수락 엔드포인트·트랜잭션·개정본 재구성 + progress 개정본 가드 | PR-1, ④(#140) |

**PR-1 을 먼저 떼는 이유** — 원천 이동은 **이미 머지돼 화면이 쓰고 있는 코드**를 건드린다. 1번
개정본에서는 세션 스냅샷과 개정본 스냅샷이 **같은 값**이라 동작이 바뀌지 않아야 하고, 그것을 기존
geometry 시험이 무수정으로 증명한다.

## 1. 확정 결정 (요약 — 근거는 DESIGN.md §1)

| # | 결정 |
|---|---|
| D1·D2 | 전용 엔드포인트 + `candidateId` 필수(없으면 400) |
| D3 | 새 개정본은 **전체 단계 복제**(앞은 값 보존, 뒤는 새 경로) |
| D4 | `fromLegIndex < currentLegIndex` 면 409. 같으면 §4.4 승계 규칙 |
| D5 | **스냅샷을 먼저 합성하고, 새 단계·환승은 그 합성본을 다시 쪼개서 얻는다** |
| D5-b | 합성본을 쪼갠 **앞 구간이 저장된 단계와 다르면 409**(`snapshot-mismatch`) |
| D6 | geometry 원천 = 그 개정본의 스냅샷. **폴백은 1번 개정본에만**, 그 뒤는 명시적 실패 |
| D7·D8 | **세션 행을 잠근다**(`findByIdAndUserIdForUpdate`). 순서는 세션 → 제안 |
| D9 | 재확인 실패는 **예외로 롤백**. 정상 return 409 금지 |
| D10·D11 | 수락 후보 ID 를 저장(V43). 같은 후보 재전송 200, 다른 후보 409 |
| D12 | `event_id` = `reroute-accept:{proposalId}:{candidateId}` (결정론) |
| D13 | 교체 지점 **이후**의 예측·증거·진행도·환승 판정·알림 표시는 **초기화** |
| D14 | 알림 취소 사유는 `route-changed` |
| D15 | progress 경로에 **개정본 가드** 추가 |
| D16 | `reason` = `ROUTE_OPTION_ACCEPTED` · `REMAINING_PATH_ACCEPTED` |
| D17 | 상태머신 `REROUTE_ACCEPTED` 는 계속 거절 |

## 2. 착수 시점의 실제 코드 상태 (2026-08-13 확인)

| 부품 | 상태 |
|---|---|
| `JourneyCommandService.apply` | `sessionRepository.findByIdAndUserIdForUpdate(...)` 로 **세션 행을 잠근다**(`:61-62`). 수락도 같은 것을 잠근다 |
| `JourneyProgressService.apply` | **잠그지 않는다.** 개정본을 한 번 확인하고(`:35-36`) 단계를 갱신한 뒤 세션을 다시 읽는다(`:74-78`) |
| `JourneyLeg` builder | `type`·`state`·정류장·이름·`plannedDurationSec`·`evidenceQuality`·`activatedAt` 만 받는다(`:143-165`). **진행도·예측·완료 시각은 넣을 방법이 없다 → 복제 API 를 만든다** |
| `JourneyCompiler` | 도보 종류가 앞뒤 교통수단으로 갈리고(`:122-150`), 같은 정류장 0거리 도보는 단계를 만들지 않는다(`:128-129`). 환승은 인접 교통수단으로 만든다(`:337-361`) |
| `JourneyStartTxService` | 저장 순서: 단계 저장·flush → 후보 저장·flush → 선택 지정 → flush(`:126-177`) |
| `JourneyNotificationRepository` | `cancelPendingByJourneyId` 는 사유가 **`journey-ended` 고정**(`:194-202`) → 사유 인자를 받는 메서드를 새로 만든다 |
| `RerouteProposalRepository` | `supersedeActive`·`resolveStale`·`resolveActiveByJourneyId` 있음. **조건부 accept 는 없다** |
| `RerouteProposal` / V42 | 수락한 후보 ID 를 담을 칸이 **없다** → V43 |
| `JourneyEvent` | `event_id` 필수·unique(`uq_journey_event_id`). 수락 요청에는 앱이 준 `eventId` 가 없다 |
| `journey_leg.selected_route_option_id` FK | **행 존재만** 검사한다 — 옛 개정본의 후보 행을 가리켜도 통과한다 |
| `uq_journey_active_revision` | 여정당 `ACTIVE` 하나. 옛 행을 내리고 **flush 뒤** 새 행 |
| `TransferRiskEvaluator` / `TransferRiskNotifier` | 떨림 방지와 `lastNotifiedStatus` 표시를 쓴다 — 복제로 남으면 **새 위험 알림이 막힌다** |

---

## PR-1 — geometry 원천 이동 (M, 동작 불변)

### 3.1 수정: `JourneyNavigationGeometryService`

```java
// 지금
Snapshot snapshot = recompile(journey.getRouteSnapshot());

// 바꾼 뒤
Snapshot snapshot = recompile(snapshotOf(journey, revision));
```

- `JourneyRevisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journeyId, revision)`
  로 **지금 그리는 개정본의** 스냅샷을 읽는다(④가 추가해 둔 조회다). `ACTIVE` 로 찾지 않는다 —
  요청이 개정본을 지목할 수 있다
- **폴백은 `revision == 1` 일 때만.** 그때만 세션 스냅샷과 같은 값이 보장된다.
  2 이상인데 개정본 행이 없으면 `log.error` + 예외(500) — 2번 개정본에서 세션 스냅샷을 그리면
  **반드시 옛 경로를 조용히 그린다**
- 그 외에는 한 줄도 바꾸지 않는다

### 3.2 검증 (PR-1)

```bash
./gradlew test --tests '*JourneyNavigationGeometry*'
```

- [ ] 기존 geometry 시험이 **무수정 통과**
- [ ] 개정본 스냅샷을 다르게 심으면 **개정본 쪽이 그려진다**
- [ ] 1번 개정본에 행이 없으면 세션 스냅샷으로 그린다(폴백)
- [ ] **2번 개정본에 행이 없으면 실패한다**(조용히 옛 경로를 그리지 않는다)
- [ ] `./gradlew build` 전체 통과 → PR-1 머지

---

## PR-2 — 수락 본체 (L)

### 4.1 마이그레이션 V43

```sql
ALTER TABLE reroute_proposal ADD COLUMN accepted_candidate_id varchar(36);
COMMENT ON COLUMN reroute_proposal.accepted_candidate_id IS
    '수락된 후보의 candidateId. 재전송(같은 후보 200)과 다른 후보(409)를 가른다';
```

`nullable` 이다 — 수락되지 않은 제안이 대부분이다. **VPS 배포 시 수동 배치 대상**(마이그레이션
convention).

### 4.2 신규 `application/journey/service/JourneyRevisionComposer.java`

DB 를 읽지도 쓰지도 않는 순수 변환. **순서가 계약이다 — 합성 먼저, 쪼개기 나중.**

```java
@Component
public class JourneyRevisionComposer {

    /**
     * @return 만들 수 없거나 앞 구간이 저장된 단계와 어긋나면 사유가 담긴 실패
     */
    public Result compose(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                          RerouteCandidateApply apply);

    public record Composed(PathResult snapshot, List<JourneyLegDraft> legs,
                           List<TransferConnectionDraft> transfers,
                           int fromLegIndex, String reason) {}
}
```

`REMAINING_PATH` 동작:

1. `fromLegIndex = apply.fromLegIndex()`
2. `p = compiler.compileDetailed(currentSnapshot).subPathIndexOf(fromLegIndex)`.
   **null 이면 실패**(`snapshot-unmappable`)
3. 합성: `subPaths = currentSnapshot.subPaths[0..p) + apply.path().subPaths()`.
   `pathType`·`info`·`laneGraphics` 는 **후보 경로의 것**을 그대로
4. **합성본을 `compiler.compile(...)` 로 쪼갠다.** 후보를 따로 쪼개 번호를 밀지 않는다 —
   도보 종류·`skippedWalk`·접합부 환승이 여기서만 올바르게 나온다
5. `transitIdEnricher.enrich(...)` 를 거친다(④와 같은 함정)
6. **앞 구간 검사(D5-b)**: `legIndex < fromLegIndex` 인 초안의 `type` 이 저장된 단계와 하나라도
   다르거나 개수가 다르면 실패(`snapshot-mismatch`)
7. 환승 초안은 **합성본이 낸 것 전부**를 쓴다. 앞 구간에 걸친 환승은 저장 쪽이 옛 판정값을 덮어
   씌운다(§4.3-c)

`ROUTE_OPTION` 동작: 스냅샷·단계·환승 구조는 그대로. `Composed.legs` 는 비우고 `fromLegIndex` 와
바꿀 후보 정보만 싣는다(§4.3-b).

### 4.3 신규 `application/journey/service/RerouteAcceptTxService.java`

```
 1. sessionRepository.findByIdAndUserIdForUpdate(journeyId, userId)     세션 행 잠금 (D7)
 2. 여정 ACTIVE 확인
 3. proposalRepository.acceptIfActive(proposalId, candidateId, now)     조건부 update (D8)
      - 0행이면: 제안을 다시 읽어 accepted_candidate_id 로 판정
        · 같은 후보 → 200 + 최신 스냅샷 (D11)
        · 그 외      → 409
 4. 넷 재확인 — 제안의 routeRevision == 세션, baseCommandVersion == 세션,
    expiresAt > now, fromLegIndex >= currentLegIndex
      - 하나라도 어긋나면 BusinessException → 트랜잭션 롤백 (D9)
 5. composer.compose(...) 실패면 BusinessException(snapshot-mismatch) → 롤백
 6. 옛 개정본 supersede + flush                                          uq_journey_active_revision
 7. 새 개정본 insert (routeRevision+1, reason, 합성 스냅샷)
 8. 앞 구간 단계 복제 + 새 구간 단계 저장 → flush
 9. 노선 후보 저장 → 선택 노선 지정(복제한 새 후보 행 ID) → flush
10. 환승 저장 (앞 구간은 판정값 복제, 교체 지점 이후는 UNKNOWN)
11. session: routeRevision+1 · commandVersion+1 · scheduleEvaluationAt(now)
12. 알림 취소(route-changed) · 남은 제안 resolveStale(새 개정본)
13. journey_event insert — event_id = "reroute-accept:{proposalId}:{candidateId}"
```

#### 4.3-a 단계 복제

`JourneyLeg` 에 **복제 전용 API 를 만든다.** 빌더로는 진행도·예측·완료 시각을 옮길 수 없다.

```java
/** 같은 단계를 새 개정본으로 옮긴다. 진행·증거·시각을 그대로 들고 간다. */
public JourneyLeg copyToRevision(int newRouteRevision);
```

- 복제 대상: `legIndex < fromLegIndex` 전부
- **`selectedRouteOptionId` 는 복제 직후 null 로 두고**, 새 후보 행을 저장한 뒤 새 ID 로 채운다.
  FK 가 행 존재만 검사하므로 옛 ID 를 그대로 두면 **다른 개정본을 가리키는 유령 참조**가 통과한다
- 짝이 되는 대기·주행 단계는 **같은 새 후보 행**을 가리켜야 한다(시작 흐름과 같은 규칙)

#### 4.3-b `ROUTE_OPTION` 수락

단계·환승·스냅샷은 전부 복제하고, 대기 단계와 짝 주행 단계의 `selectedRouteOptionId`·`routeId` 만
고른 후보로 바꾼다. **개정본은 똑같이 오른다.** 그리고 **그 환승과 단계의 판정값은 초기화한다**
(§4.3-c) — 구조가 같다고 옛 예측이 유효한 것이 아니다.

#### 4.3-c 무엇을 잇고 무엇을 버리는가 (D13)

| 대상 | 교체 지점 **앞** | 교체 지점 **이후** |
|---|---|---|
| 단계 `state`·`activatedAt`·`completedAt` | 복제 | 새로 만든 값(§4.4) |
| 단계 `progress*`·`lastProgressId`·`remaining*` | 복제 | **비운다** |
| 단계 `predictedEndAt`·`evidence*` | 복제 | **비운다** |
| 환승 `status`·`userReadyAt`·예측·`confidence` | 복제 | `UNKNOWN` + 전부 비움 |
| 환승 `lastNotifiedStatus` | 복제 | **비운다** — 남기면 새 위험을 알리지 못한다 |

`ROUTE_OPTION` 수락에서 **바뀐 노선의 환승**은 "이후"로 취급한다.

### 4.4 `currentLegIndex == fromLegIndex` 승계

| 값 | 규칙 |
|---|---|
| `state` | 옛 단계가 `ACTIVE` 면 새 첫 단계도 `ACTIVE` |
| `activatedAt` | **옛 단계와 종류(`type`)가 같을 때만** 승계. 다르면 `now` |
| `progress*`·`predictedEndAt`·`evidence*` | 승계하지 않는다 |

### 4.5 신규·수정 저장소 메서드

```java
// RerouteProposalRepository — 조건부 수락. candidateId 도 함께 남긴다 (D10)
@Modifying(clearAutomatically = false, flushAutomatically = true)
@Query("update RerouteProposal p set p.status = 'ACCEPTED', p.resolvedAt = :now, "
     + "p.resolvedReason = 'accepted', p.acceptedCandidateId = :candidateId "
     + "where p.id = :id and p.status = 'ACTIVE' and p.expiresAt > :now")
int acceptIfActive(Long id, String candidateId, Instant now);

// JourneyNotificationRepository — 사유를 받는다 (D14)
int cancelPendingByJourneyId(Long journeyId, String reason);
```

**`clearAutomatically = true` 를 쓰지 않는다.** 영속성 컨텍스트를 비우면 방금 잠근 세션 엔티티가
detach 되어 그 뒤 변경이 조용히 사라진다(기존 `cancelPendingByJourneyId` 주석이 같은 이유를 적어 뒀다).

### 4.6 progress 개정본 가드 (D15)

`JourneyProgressService` 는 여정 행을 잠그지 않는다. 수락이 그 사이 개정본을 올리면 **옛 단계**를
갱신하고 새 세션에 지하철 보호 시각을 씌운다.

- 단계 갱신·`bumpEvaluationVersion`·보호 시각 적용을 **개정본 조건부**로 바꾼다
- 다시 읽은 세션의 개정본이 요청과 다르면 **아무 것도 적용하지 않고** `STALE_ROUTE_REVISION`
- 시험: 수락과 progress 를 겹쳐 실행해 옛 개정본 갱신이 새 상태를 건드리지 않는지

### 4.7 API

`POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept` · 본문 `{ "candidateId": "…" }`

- 응답: 여정 스냅샷(기존 응답 재사용). 앱은 `routeRevision` 으로 geometry 를 다시 받는다
- 404 `REROUTE_PROPOSAL_NOT_FOUND`·`REROUTE_CANDIDATE_NOT_FOUND` / 409 `REROUTE_PROPOSAL_STALE`
  (만료·상태·버전·개정본·지나간 단계·`snapshot-mismatch`) / 400 `candidateId` 누락
- 409 에도 **최신 스냅샷을 싣는다**

### 4.8 수정 파일

| 경로 | 변경 |
|---|---|
| `domain/journey/entity/JourneySession.java` | `bumpRouteRevision()` |
| `domain/journey/entity/JourneyLeg.java` | `copyToRevision(newRevision)` · 판정값 초기화 API |
| `domain/journey/entity/TransferConnection.java` | 판정·알림 표시 초기화 API |
| `domain/journey/entity/JourneyRevision.java` | `reason` 어휘 확정(D16) |
| `domain/journey/repository/RerouteProposalRepository.java` | `acceptIfActive` |
| `domain/journey/repository/JourneyNotificationRepository.java` | 사유를 받는 취소 |
| `application/journey/service/JourneyProgressService.java` | 개정본 가드(D15) |
| `global/error/code/JourneyErrorCode.java` | 코드 3개 |
| `infrastructure/adapter/in/api/journey/JourneyRerouteProposalController.java` | accept |
| `application/journey/service/JourneyStateMachine.java` | 주석만(D17) |
| `docs/plan/journey-navigation/PHASE-6.md`·`SCOPE-CHANGES.md` | ⑤ 서술 정정 + **SC-17** |

### 4.9 시험 파일

| 경로 | 내용 |
|---|---|
| `test/.../service/JourneyRevisionComposerTest.java` | 단위. **합성본을 쪼갠 결과 = 저장할 단계**, 접합부 도보 종류·`skippedWalk`·접합부 환승, 앞 구간 불일치 시 실패 |
| `test/.../journey/RerouteAcceptIT.java` | 통합. 수락 → 개정본·단계·환승·세션·geometry |
| `test/.../journey/RerouteAcceptConflictIT.java` | 통합. 만료·버전·지나간 단계·중복 수락·동시 수락·progress 경합 |

## 5. 검증

```bash
./gradlew test --tests '*RerouteAccept*' --tests '*JourneyRevisionComposer*' --tests '*Geometry*' --tests '*Progress*'
./gradlew build
```

### 통과해야 하는 것

- [ ] **(PR-1) 기존 geometry 시험 무수정 통과**, 개정본 스냅샷이 우선, 2번 개정본 결측은 실패
- [ ] **합성본을 다시 쪼갠 단계 목록 = 저장된 단계**(번호·종류)
- [ ] **접합부에서 도보 종류가 바뀌는 후보는 409**(`snapshot-mismatch`)이고 여정은 그대로다
- [ ] 접합부 환승이 새 개정본에 생긴다(`toLegIndex`·`walkLegIndex` 포함)
- [ ] 수락하면 `routeRevision`·`commandVersion` 이 각각 1씩 오른다
- [ ] 앞 구간 단계가 **진행도·증거·시각까지** 그대로 남는다
- [ ] 복제한 단계의 `selectedRouteOptionId` 가 **새 개정본의 후보 행**을 가리킨다
- [ ] 교체 지점 이후 단계·환승의 예측·증거·`lastNotifiedStatus` 가 **비어 있다**
- [ ] `ROUTE_OPTION` 수락도 개정본을 올리고 그 환승의 판정값을 초기화한다
- [ ] 재확인 실패 시 **제안이 다시 `ACTIVE`** 다(롤백)
- [ ] 같은 후보 재전송은 200, 다른 후보는 409, 개정본은 **한 번만** 오른다
- [ ] 동시 수락 둘 중 한 번만 성공한다
- [ ] `journey_event` 에 `reroute-accept:{proposalId}:{candidateId}` 가 **한 행만** 남는다
- [ ] 알림이 **`route-changed`** 사유로 취소된다(`journey-ended` 가 아니다)
- [ ] 수락 직후 들어온 옛 개정본 progress 가 새 상태를 건드리지 않는다
- [ ] 수락 뒤 geometry 가 새 경로를 그린다
- [ ] 기존 여정 시작·진행·알림·③④ 시험 회귀 없음

### 배포 메모

**V43 은 VPS 수동 배치 대상**이다. `journey.reroute.enabled` 는 여전히 false — 켜는 것은 ⑥ 이후다.

## 6. 함정

- **후보를 따로 쪼개지 마라.** 쪼개기는 앞뒤 구간을 함께 본다 — 도보 종류가 바뀌고 0거리 도보가
  사라져 번호가 밀린다. 합성 먼저, 쪼개기 나중
- **자르는 위치를 손으로 세지 마라.** `compileDetailed(...).subPathIndexOf(fromLegIndex)` 로 묻는다
- **세션 행을 잠가라.** 사용자 행을 잠그면 단계 전이와 직렬화되지 않는다. 순서는 세션 → 제안
- **조건부 update 뒤의 거절은 롤백이다.** 정상 return 409 면 제안만 소모된다
- **`clearAutomatically = true` 금지.** 잠근 세션이 detach 된다
- **`JourneyLeg` 빌더로 복제하지 마라.** 진행도·예측·완료 시각이 통째로 null 이 되는데, 다음 평가가
  대부분 다시 채워 시험에서도 안 보인다
- **복제한 단계의 `selectedRouteOptionId` 를 그대로 두지 마라.** FK 는 행 존재만 보므로 옛 개정본
  후보를 가리키는 유령 참조가 통과한다
- **`lastNotifiedStatus` 를 남기지 마라.** 새 위험 알림이 조용히 막힌다
- **알림 취소 사유를 `journey-ended` 로 두지 마라.** 끝나지도 않은 여정이 그 지표를 오염시킨다
- **`event_id` 는 결정론으로.** 앱이 주지 않으므로 서버가 만들고, unique 가 멱등을 대신한다
- **`git add -A` 금지.** 경로를 지정해 커밋한다

## 7. 문서 변경 (SC-17)

| PHASE-6 서술 | 실제 |
|---|---|
| "지금 진행 중인 leg 가 어느 쪽이냐가 안 적혀 있다" | `fromLegIndex >= currentLegIndex` 면 수락, 아니면 409 |
| (없음) | 새 개정본 스냅샷은 **합성**하고, 단계는 **합성본을 다시 쪼개** 얻는다 |
| (없음) | 마이그레이션 **V43**(`accepted_candidate_id`) 한 건이 생긴다 |
| `journey_revision.reason` 미정 | `ROUTE_OPTION_ACCEPTED` · `REMAINING_PATH_ACCEPTED` |
