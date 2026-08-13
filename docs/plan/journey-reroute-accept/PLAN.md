# Phase 6 ⑤ — 재탐색 수락 (`journey-reroute-accept`)

- 설계 근거: [DESIGN.md](./DESIGN.md) — 결정의 *이유*는 전부 그쪽에 있다. 이 문서는 *무엇을 어떻게*만 적는다
- 상위: [PHASE-6.md §⑤](../journey-navigation/PHASE-6.md) · 선행 PR: ①(#135) ②(#133) ③(#136) ④(#139, #140)
- 마이그레이션: **없음** (기존 표만 쓴다)
- 작성일: 2026-08-13

## PR 두 개로 나눈다

| | 슬러그 | 티어 | 내용 | 선행 |
|---|---|---|---|---|
| **PR-1** | `feat/journey-geometry-revision-source` | **M** | geometry 원천을 `ACTIVE` 개정본 스냅샷으로 옮긴다(**동작 불변**) | ④(#140) |
| **PR-2** | `feat/journey-reroute-accept` | **L** | 수락 엔드포인트·트랜잭션·개정본 재구성 | PR-1 |

**PR-1 을 먼저 떼는 이유** — 원천 이동은 **이미 머지돼 화면이 쓰고 있는 코드**를 건드린다. 1번
개정본에서는 세션 스냅샷과 개정본 스냅샷이 **같은 값**이라 동작이 바뀌지 않아야 하고, 그것을 기존
geometry 시험이 무수정으로 증명한다. 수락과 같은 PR 에 넣으면 "지도가 이상하다"의 원인이 원천
이동인지 새 개정본인지 가릴 수 없다.

## 0. 이 PR 이 만드는 것

사용자가 고른 제안 하나를 받아 **안내 경로를 실제로 갈아끼운다.** 개정본·단계·노선 후보·환승·
geometry 다섯이 한 트랜잭션에서 함께 움직인다.

## 1. 확정 결정 (요약 — 근거는 DESIGN.md §1)

| # | 결정 |
|---|---|
| D1 | 전용 엔드포인트 `POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept` |
| D2 | 본문에 `candidateId` 필수. 없으면 400 |
| D3 | 새 개정본은 **전체 단계를 복제**한다(앞 구간은 값 보존, 뒤 구간은 새 경로) |
| D4 | `fromLegIndex < currentLegIndex` 면 409. 같으면 적용하고 `ACTIVE` 상태·`activatedAt` 을 잇는다 |
| D5 | 개정본 스냅샷은 **합성**한다 — 옛 스냅샷의 앞 구간 + 후보 경로 |
| D6 | geometry 원천은 `ACTIVE` `JourneyRevision.routeSnapshot` (PR-1) |
| D7 | 트랜잭션 안에서 넷을 재확인: 제안 `ACTIVE` · 만료 · `baseCommandVersion` · `routeRevision` |
| D8 | 동시 수락은 제안 상태 **조건부 update 한 문장**으로 한 번만 통과 |
| D9 | 같은 `(proposalId, candidateId)` 재전송은 200 + 최신 스냅샷. 다른 후보면 409 |
| D10 | `journey_revision.reason` = `ROUTE_OPTION_ACCEPTED` · `REMAINING_PATH_ACCEPTED` |
| D11 | 이전 개정본 pending 알림 취소 + 남은 제안 접기 + 즉시 재평가 예약 |
| D12 | 상태머신 `REROUTE_ACCEPTED` 는 계속 거절. 이력은 수락 트랜잭션이 직접 남긴다 |

## 2. 착수 시점의 실제 코드 상태 (2026-08-13 확인)

| 부품 | 상태 |
|---|---|
| `JourneyStateMachine` | `REROUTE_ACCEPTED -> rejected(NOT_SUPPORTED_YET)` (`:40`) |
| `JourneySession` | `bumpCommandVersion()`·`moveToLeg()`·`scheduleEvaluationAt()` 는 있고 **`routeRevision` 을 올리는 메서드는 없다** — 추가한다 |
| `JourneyRevision` | `supersede(at)` 있음. `reason` 칸은 비어 있고 어휘 미정 |
| `RerouteProposal` | `accept(now)` 이미 있음(`resolvedReason = "accepted"`). `baseCommandVersion`·`routeRevision`·`expiresAt` 보유 |
| `RerouteProposalRepository` | `supersedeActive`·`resolveStale`·`resolveActiveByJourneyId` 있음. **조건부 accept update 는 없다 — 추가한다** |
| `JourneyStartTxService` | 저장 순서(단계 → 노선 후보 → 환승)의 본보기. 선택 노선은 첫 후보(`saveRouteOptions:170`) |
| `JourneyNavigationGeometryService` | `recompile(journey.getRouteSnapshot())`(`:88`) — **세션 스냅샷**을 쓴다. PR-1 이 여기를 옮긴다 |
| `JourneyCompiler.compileDetailed` | `subPathIndexOf(legIndex)` 로 단계 → 원본 구간 번호를 준다. 합성 스냅샷의 자르는 위치가 이 값이다 |
| `JourneyCommandService.applyTransition` | 알림 취소·제안 접기·`commandVersion` 증가의 본보기(`:145-160`) |
| `JourneyNotificationRepository` | `cancelPendingByJourneyId(journeyId)` 있음 |
| `uq_journey_active_revision` | 여정당 `ACTIVE` 개정본 하나. **옛 개정본을 내리고 flush 한 뒤** 새 행을 넣어야 한다 |
| `uq_journey_leg_index` | `(journey_id, route_revision, leg_index)` — 개정본이 다르면 같은 번호가 공존한다 |
| `JourneyErrorCode` | `REROUTE_NOT_ELIGIBLE`·`STALE_ROUTE_REVISION`·`JOURNEY_NOT_ACTIVE` 있음. 수락 전용 코드는 없다 |
| `RerouteCandidateApply` | `RouteOptionApply(waitLegIndex, routeOptionIndex, routeId, directionKey)` · `RemainingPathApply(fromLegIndex, path)` |
| `JourneyTransitIdEnricher` | ④가 후보 경로에 이미 쓴다. 수락도 같은 단계를 거친다 |

### 참고할 형제 코드

- **저장 순서·flush 규칙**: `JourneyStartTxService.start`
- **알림 취소·제안 접기**: `JourneyCommandService.applyTransition`
- **조건부 update 관례**: `RerouteProposalRepository.supersedeActive`
- **통합 시험 관례**: `RerouteRouteOptionIT`(③) · `RerouteRemainingPathIT`(④)

---

## PR-1 — geometry 원천 이동 (M, 동작 불변)

### 3.1 수정: `JourneyNavigationGeometryService`

```java
// 지금
Snapshot snapshot = recompile(journey.getRouteSnapshot());

// 바꾼 뒤
Snapshot snapshot = recompile(revisionSnapshot(journey));
```

- `JourneyRevisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journeyId, revision)`
  로 **지금 그리는 개정본의 스냅샷**을 읽는다. `findByJourneyIdAndStatus…(ACTIVE)` 를 쓰지 않는다 —
  요청이 특정 개정본을 지목할 수 있고(`requestedRevision`), 그 값과 다른 스냅샷을 그리면 안 된다
  (조회 메서드는 ④가 이미 추가해 뒀다)
- **개정본 행이 없으면 세션 스냅샷으로 폴백하고 `log.warn`.** 시작 흐름이 1번 개정본을 반드시
  만들지만, 옛 데이터나 부분 실패로 없을 수 있고 그때 지도를 통째로 죽이는 것은 과하다
- 그 외에는 한 줄도 바꾸지 않는다

### 3.2 검증 (PR-1)

```bash
./gradlew test --tests '*JourneyNavigationGeometry*'
```

- [ ] 기존 geometry 시험이 **무수정 통과**. 1번 개정본에서 두 스냅샷이 같은 값이므로 결과가 같다
- [ ] 개정본 스냅샷을 일부러 다르게 심으면 **개정본 쪽이 그려진다**(원천이 옮겨졌다는 증거)
- [ ] 개정본 행이 없으면 세션 스냅샷으로 그린다(폴백)
- [ ] `./gradlew build` 전체 통과 → PR-1 머지

---

## PR-2 — 수락 본체 (L)

### 4.1 신규 `application/journey/service/JourneyRevisionComposer.java`

새 개정본의 **재료**를 만든다. DB 를 읽지도 쓰지도 않는 순수 변환이라 시험이 쉽다.

```java
@Component
public class JourneyRevisionComposer {

    /**
     * @param currentSnapshot 지금 개정본의 경로 원본
     * @param currentLegs     지금 개정본의 단계 전부(legIndex 오름차순)
     * @param apply           사용자가 고른 후보의 적용 재료
     * @return 새 개정본의 단계 초안 + 합성 스냅샷. 만들 수 없으면 빈 결과
     */
    public Optional<Composed> compose(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                                      RerouteCandidateApply apply);

    public record Composed(List<JourneyLegDraft> legs, List<TransferConnectionDraft> transfers,
                           PathResult snapshot, int fromLegIndex, String reason) {}
}
```

동작:

1. `fromLegIndex` 를 뽑는다 — `RemainingPathApply.fromLegIndex()` / `RouteOptionApply.waitLegIndex()`
2. **`ROUTE_OPTION`** 이면 구조가 안 바뀐다. 스냅샷도 단계 목록도 지금 것을 그대로 쓰고,
   바뀌는 것은 대기 단계의 **선택 노선**뿐이다(§4.3-b)
3. **`REMAINING_PATH`** 면
   - `compiler.compile(apply.path())` → `transitIdEnricher.enrich(plan)`
     (**보강을 빼먹으면 새 단계가 전부 미해석이 되어 그 뒤 평가가 통째로 죽는다**)
   - 새 단계 초안의 번호를 `fromLegIndex + i` 로 다시 매긴다. `pairedLegIndex` 도 같이 민다
   - 앞 구간 초안은 만들지 않는다 — **엔티티 복제**는 저장 쪽이 한다(§4.3)
   - 스냅샷 합성: `p = compileDetailed(currentSnapshot).subPathIndexOf(fromLegIndex)` 를 구해
     `currentSnapshot.subPaths[0..p)` + `apply.path().subPaths()` 를 이은 `PathResult`.
     `info`·`pathType`·`laneGraphics` 는 **후보 경로의 것을 그대로** 쓴다
   - `p` 가 null 이면(원본을 잃은 단계) 빈 결과 → 수락 거절
   - 환승 초안: 앞 구간의 기존 환승은 저장 쪽이 복제하고, 여기서는 **새 구간의 환승만** 낸다.
     `transferIndex` 는 앞 구간 환승 수부터 이어 붙이고 `fromLegIndex` 만큼 단계 번호를 민다

### 4.2 신규 `application/journey/service/RerouteAcceptService.java`

트랜잭션 **밖**에서 할 일만 한다 — 후보를 찾아 재료를 만든다. 그 다음 Tx 서비스를 부른다.

```java
public JourneySnapshot accept(Long userId, Long journeyId, Long proposalId, String candidateId);
```

- 제안·여정을 읽어 **소유자**를 확인한다(`userId` 는 JWT 에서만 온다)
- `candidateId` 로 후보를 고른다. 없으면 `REROUTE_CANDIDATE_NOT_FOUND`(404)
- 이미 `ACCEPTED` 이고 **같은 후보**면 그대로 최신 스냅샷을 돌려준다(D9)
- 재료를 만들어 `RerouteAcceptTxService.apply(...)` 로 넘긴다

### 4.3 신규 `application/journey/service/RerouteAcceptTxService.java`

한 트랜잭션에서 전부 한다. 순서가 계약이다.

```
1. userRepository.findByIdForUpdate(userId)                 사용자 행 잠금
2. proposalRepository.acceptIfActive(proposalId, now)       조건부 update — 1행이 아니면 409
3. 여정을 다시 읽어 넷을 확인 (D7·D4)                        어긋나면 409 + 최신 스냅샷
4. 옛 개정본 supersede + flush                              uq_journey_active_revision
5. 새 개정본 insert (routeRevision+1, reason, 합성 스냅샷)
6. 앞 구간 단계 복제 + 새 구간 단계 저장 → flush
7. 노선 후보 저장 + 선택 노선 지정 (시작 흐름과 같은 규칙)
8. 환승 저장 (앞 구간 복제 + 새 구간)
9. session: routeRevision+1 · commandVersion+1 · scheduleEvaluationAt(now)
10. 알림 취소(cancelPendingByJourneyId) · 남은 제안 접기(route-changed)
11. journey_event 에 REROUTE_ACCEPTED 기록
```

**앞 구간 복제(6)에서 옮기는 칸**은 손으로 나열하지 말고 **시험이 목록을 세게 한다**(§6 함정).
최소한 이것들: `type`·`state`·`startStopId`·`endStopId`·`sourceStartStopId`·`sourceEndStopId`·
`startName`·`endName`·`routeId`·`vehicleId`·`plannedDurationSec`·`predictedEndAt`·`remainingStops`·
`evidenceSource`·`evidenceObservedAt`·`evidenceQuality`·`lastProgressId`·`progress*`·
`remainingDistanceMeters`·`remainingDurationSeconds`·`activatedAt`·`completedAt`.

**`currentLegIndex == fromLegIndex`** 면 새 단계의 첫 칸이 `ACTIVE` 이고 `activatedAt` 은 옛
단계의 값을 잇는다(D4). 그 외에는 새 구간 전부 `PENDING`.

### 4.3-b `ROUTE_OPTION` 수락은 구조가 안 바뀐다

단계·환승·스냅샷은 그대로 복제하고, 대기 단계와 짝 주행 단계의 `selectedRouteOptionId`·`routeId` 만
고른 후보로 바꾼다. **개정본은 똑같이 오른다** — 앱이 "경로가 바뀌었다"를 개정본 번호로 판단하고,
알림·제안 정리도 개정본 기준이기 때문이다.

### 4.4 신규 저장소 메서드

`RerouteProposalRepository`

```java
/** ACTIVE 인 제안만 ACCEPTED 로. 동시 수락 중 한 번만 1을 돌려준다. */
@Modifying(clearAutomatically = true)
@Query("update RerouteProposal p set p.status = 'ACCEPTED', p.resolvedAt = :now, "
     + "p.resolvedReason = 'accepted' where p.id = :id and p.status = 'ACTIVE' "
     + "and p.expiresAt > :now")
int acceptIfActive(@Param("id") Long id, @Param("now") Instant now);
```

### 4.5 신규 API

`POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept`

```json
{ "candidateId": "0f9f…" }
```

- 응답은 **여정 스냅샷**(기존 `JourneySnapshotResponse` 재사용). 앱은 응답의 `routeRevision` 으로
  geometry 를 다시 받는다
- 오류: 404 `REROUTE_PROPOSAL_NOT_FOUND`·`REROUTE_CANDIDATE_NOT_FOUND` / 409
  `REROUTE_PROPOSAL_STALE`(만료·상태·버전·개정본·지나간 단계 전부 이 코드) / 400 `candidateId` 누락
- 409 응답에도 **최신 스냅샷을 함께 싣는다**. 앱이 다시 물어보지 않고 화면을 맞출 수 있어야 한다

### 4.6 수정 파일

| 경로 | 변경 |
|---|---|
| `domain/journey/entity/JourneySession.java` | `bumpRouteRevision()` 추가 |
| `domain/journey/entity/JourneyRevision.java` | `reason` 어휘 주석 확정(D10) |
| `domain/journey/repository/RerouteProposalRepository.java` | `acceptIfActive` |
| `global/error/code/JourneyErrorCode.java` | 코드 3개 추가 |
| `infrastructure/adapter/in/api/journey/JourneyRerouteProposalController.java` | accept 엔드포인트 |
| `application/journey/service/JourneyStateMachine.java` | 주석만 — 왜 계속 거절인지(D12) |
| `docs/plan/journey-navigation/PHASE-6.md` | ⑤ 서술 정정(§7) |
| `docs/plan/journey-navigation/SCOPE-CHANGES.md` | **SC-17** |

### 4.7 시험 파일

| 경로 | 내용 |
|---|---|
| `test/.../service/JourneyRevisionComposerTest.java` | 단위. **합성 스냅샷을 다시 쪼갠 결과 = 저장할 단계 목록**, 번호 밀기, 걷지 않는 환승이 있는 경로 |
| `test/.../journey/RerouteAcceptIT.java` | 통합. 수락 → 개정본·단계·환승·세션이 함께 바뀐다 |
| `test/.../journey/RerouteAcceptConflictIT.java` | 통합. 만료·버전 어긋남·지나간 단계·중복 수락·동시 수락 |

## 5. 검증

```bash
./gradlew test --tests '*RerouteAccept*' --tests '*JourneyRevisionComposer*' --tests '*Geometry*'
./gradlew build
```

### 통과해야 하는 것

- [ ] **(PR-1) 기존 geometry 시험이 무수정 통과**하고, 개정본 스냅샷을 다르게 심으면 그쪽이 그려진다
- [ ] 수락하면 `routeRevision`·`commandVersion` 이 **각각 1씩** 오른다
- [ ] 새 개정본에 **앞 구간 단계가 그대로 있다** — 상태·진행도·증거·시각까지
- [ ] **새 개정본 스냅샷을 다시 쪼갠 단계 목록이 저장된 단계와 번호·종류가 같다**(§DESIGN 3 의 계약)
- [ ] 수락 뒤 geometry 가 **새 경로**를 그린다
- [ ] `fromLegIndex < currentLegIndex` 면 409 이고 여정은 그대로다
- [ ] `currentLegIndex == fromLegIndex` 면 수락되고 새 첫 단계가 `ACTIVE` 이며 `activatedAt` 이 이어진다
- [ ] 만료된 제안·`baseCommandVersion` 불일치·`routeRevision` 불일치는 전부 409 + 최신 스냅샷
- [ ] 같은 `(proposalId, candidateId)` 재전송은 200 이고 개정본은 **한 번만** 오른다
- [ ] 다른 `candidateId` 로 다시 누르면 409
- [ ] 동시 수락 둘 중 **한 번만** 성공한다
- [ ] 이전 개정본의 pending 알림이 취소되고 남은 제안이 접힌다
- [ ] `journey_event` 에 `REROUTE_ACCEPTED` 가 남는다
- [ ] `ROUTE_OPTION` 수락도 개정본을 올리고 선택 노선이 바뀐다
- [ ] 후보 경로가 보강(`enrich`)을 거쳐 새 단계의 `startStopId`·`routeId` 가 채워진다
- [ ] 수락 뒤 평가가 즉시 예약되고, 다음 평가가 **새 개정본의 환승**을 본다
- [ ] 기존 여정 시작·진행·알림·③④ 시험 회귀 없음

### 배포 메모

마이그레이션이 없어 VPS 수동 배치가 없다. `journey.reroute.enabled` 는 **여전히 false** — 켜는 것은
⑥ 이후이고 PHASE-5 §1.1 절차를 함께 탄다.

## 6. 함정

- **합성 스냅샷의 자르는 위치를 손으로 세지 마라.** 걷지 않는 환승은 단계를 만들지 않아 원본 구간과
  단계가 1:1 이 아니다. `compileDetailed(...).subPathIndexOf(fromLegIndex)` 로 묻는다
- **앞 구간을 재컴파일로 다시 만들지 마라.** 진행도·증거·시각을 잃는다. 엔티티 값을 복제한다
- **복제에서 칸 하나를 빠뜨려도 시험이 통과한다** — 대부분 다음 평가에서 다시 채워지기 때문이다.
  그래서 복제 시험은 "값이 같다"가 아니라 **칸 목록을 세는** 방식으로 짠다
- **`uq_journey_active_revision` 은 여정당 하나다.** 새 행을 먼저 넣으면 자기 자신과 충돌한다.
  옛 개정본을 내리고 `flush()` 한 뒤 넣는다(여정 시작의 `replaceOrReject` 와 같은 이유)
- **`selectedRouteOptionId` 는 다른 개정본의 후보 행을 가리키면 안 된다.** 복제한 단계에는 복제한
  후보 행의 새 ID 를 넣는다. 그대로 두면 옛 개정본 행을 가리키는 유령 참조가 된다
- **보강을 빼먹지 마라.** 새 단계가 전부 미해석이면 그 뒤 평가·재탐색이 통째로 죽는다(④와 같은 함정)
- **`ACCEPTED` 로 바꾸는 update 를 조회 뒤 저장으로 하지 마라.** 동시 수락 둘이 모두 통과한다
- **409 를 던지기 전에 제안을 이미 `ACCEPTED` 로 바꿨는지 보라.** 조건부 update 를 통과한 뒤
  D7 검사에서 거절하면 제안만 소모되고 여정은 안 바뀐 상태가 남는다 — **검사를 update 앞으로**
  당기거나, 거절 시 트랜잭션을 통째로 롤백해야 한다(후자를 권한다)
- **`git add -A` 금지.** 경로를 지정해 커밋한다

## 7. 문서 변경 (SC-17)

| PHASE-6 서술 | 실제 |
|---|---|
| "지금 진행 중인 leg 가 어느 쪽이냐가 안 적혀 있다" | `fromLegIndex >= currentLegIndex` 면 수락, 아니면 409 |
| (없음) | 새 개정본 스냅샷은 **합성**한다 — 옛 앞 구간 + 후보 경로 |
| `journey_revision.reason` 미정 | `ROUTE_OPTION_ACCEPTED` · `REMAINING_PATH_ACCEPTED` |
