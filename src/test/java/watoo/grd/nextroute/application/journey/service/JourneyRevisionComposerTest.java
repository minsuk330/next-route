package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/**
 * 새 개정본의 재료 만들기.
 *
 * <p>여기서 못박는 것 하나가 ⑤ 전체를 떠받친다 — <b>합성한 스냅샷을 다시 쪼갠 결과가 저장할 단계
 * 목록과 같다.</b> 지도(geometry)와 ④의 좌표 찾기가 그 계약 위에 서 있고, 깨져도 오류는 나지 않는다.
 *
 * <p>그래서 "후보를 따로 쪼개 번호만 민다"가 왜 틀리는지도 함께 못박는다: 쪼개기는 앞뒤 구간을
 * 함께 보므로 경계에서 도보 종류가 바뀌고, 접합부 환승은 후보 안에서 보이지 않는다.
 */
@DisplayName("새 개정본 재료 합성")
class JourneyRevisionComposerTest {

    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());
    private final JourneyTransitIdEnricher enricher = mock(JourneyTransitIdEnricher.class);
    private final JourneyRevisionComposer composer = new JourneyRevisionComposer(compiler, enricher);

    JourneyRevisionComposerTest() {
        // 보강은 정적 노선표를 보는 일이라 여기서는 통과만 시킨다. 거치는지 자체는 IT 가 본다.
        given(enricher.enrich(any(JourneyPlan.class))).willAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("합성본을 다시 쪼갠 결과가 곧 새 단계 목록이다")
    void recompiledSnapshotMatchesLegs() {
        PathResult current = twoTransitPath();
        List<JourneyLeg> currentLegs = legsOf(current);
        // 교체 시작점은 언제나 환승이 가리키는 대기 단계다(④가 그렇게 만든다).
        int fromLegIndex = 4;

        JourneyRevisionComposer.Composed composed = composer.compose(
                current, currentLegs, remainingPath(fromLegIndex)).composed();

        List<JourneyLegDraft> recompiled = compiler.compile(composed.snapshot()).legs();
        assertThat(composed.legs()).extracting(JourneyLegDraft::legIndex)
                .isEqualTo(recompiled.stream().map(JourneyLegDraft::legIndex).toList());
        assertThat(composed.legs()).extracting(JourneyLegDraft::type)
                .isEqualTo(recompiled.stream().map(JourneyLegDraft::type).toList());
    }

    @Test
    @DisplayName("앞 구간은 그대로 남고 교체 지점부터 새 경로다")
    void keepsPrefixAndReplacesTail() {
        PathResult current = twoTransitPath();
        List<JourneyLeg> currentLegs = legsOf(current);

        JourneyRevisionComposer.Composed composed = composer.compose(
                current, currentLegs, remainingPath(4)).composed();

        // 앞 구간은 저장된 것과 종류가 같아야 한다.
        assertThat(composed.legs().stream().limit(4).map(JourneyLegDraft::type))
                .isEqualTo(currentLegs.stream().limit(4).map(JourneyLeg::getType).toList());
        // 교체 지점 뒤는 후보 경로의 노선이다.
        assertThat(composed.legs().stream()
                .filter(leg -> leg.legIndex() >= 4)
                .flatMap(leg -> leg.routeOptions().stream())
                .map(option -> option.sourceRouteId()))
                .contains("BL-360");
        assertThat(composed.reason()).isEqualTo("REMAINING_PATH_ACCEPTED");
    }

    @Test
    @DisplayName("접합부 환승이 새로 생긴다 — 후보 경로 안에서는 보이지 않는 연결이다")
    void createsJunctionTransfer() {
        PathResult current = twoTransitPath();

        JourneyRevisionComposer.Composed composed = composer.compose(
                current, legsOf(current), remainingPath(4)).composed();

        // 후보 경로만 홀로 쪼개면 환승이 0개다. 합성본을 쪼개야 앞 구간과의 연결이 나온다.
        assertThat(compiler.compile(candidatePath()).transfers()).isEmpty();
        assertThat(composed.transfers()).isNotEmpty();
    }

    @Test
    @DisplayName("합성으로 앞 구간 종류가 바뀌면 거절한다 — 지나온 길을 바꾸지 않는다")
    void rejectsWhenPrefixTypeChanges() {
        PathResult current = twoTransitPath();
        List<JourneyLeg> currentLegs = legsOf(current);
        // 걷기만으로 목적지에 닿는 후보. 앞 도보의 "뒤에 교통수단이 있다"가 거짓이 되어
        // TRANSFER_WALK 가 WALK 로 재판정된다.
        RerouteCandidateApply apply = new RerouteCandidateApply.RemainingPathApply(4,
                new PathResult(1, null, List.of(
                        walk(20).names("정류장B", "목적지").coords(127.2, 37.2, 127.5, 37.5)
                                .distance(1500.0).build()),
                        List.of()));

        JourneyRevisionComposer.Result result = composer.compose(current, currentLegs, apply);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isEqualTo("snapshot-mismatch");
    }

    @Test
    @DisplayName("교체 시작점이 구간 한가운데면 거절한다 — 짝 대기 단계만 앞에 남는다")
    void rejectsWhenCutFallsInsideTransit() {
        PathResult current = twoTransitPath();

        // legIndex=5 는 두 번째 버스의 <b>주행</b> 단계다. 그 앞의 대기 단계(4)만 남으면
        // 종류는 그대로여도 다른 노선을 가리키게 된다.
        JourneyRevisionComposer.Result result =
                composer.compose(current, legsOf(current), remainingPath(5));

        assertThat(result.failureReason()).isEqualTo("snapshot-unmappable");
    }

    @Test
    @DisplayName("교체 시작 단계의 원본 구간을 못 찾으면 거절한다")
    void rejectsWhenCutPointIsUnmappable() {
        PathResult current = twoTransitPath();

        JourneyRevisionComposer.Result result =
                composer.compose(current, legsOf(current), remainingPath(99));

        assertThat(result.failureReason()).isEqualTo("snapshot-unmappable");
    }

    @Test
    @DisplayName("노선만 바꾸는 수락은 구조를 건드리지 않는다")
    void routeOptionKeepsStructure() {
        PathResult current = twoTransitPath();

        JourneyRevisionComposer.Composed composed = composer.compose(current, legsOf(current),
                new RerouteCandidateApply.RouteOptionApply(4, 1, "BL-999", "UP")).composed();

        assertThat(composed.keepsStructure()).isTrue();
        assertThat(composed.snapshot()).isSameAs(current);
        assertThat(composed.fromLegIndex()).isEqualTo(4);
        assertThat(composed.reason()).isEqualTo("ROUTE_OPTION_ACCEPTED");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteCandidateApply remainingPath(int fromLegIndex) {
        return new RerouteCandidateApply.RemainingPathApply(fromLegIndex, candidatePath());
    }

    /** 재검색이 준 다른 경로. 환승 정류장에서 목적지까지 버스 하나다. */
    private PathResult candidatePath() {
        return new PathResult(1, null, List.of(
                bus(15).names("정류장B", "목적지").coords(127.2, 37.2, 127.5, 37.5)
                        .busStops("ST-B", "ST-Z").lanes(busLane("360", "BL-360")).build()),
                List.of());
    }

    /** 도보 → 버스 → 도보 → 버스. 두 번째 대기 단계가 교체 시작점(legIndex=2)이 되도록 짰다. */
    private PathResult twoTransitPath() {
        return new PathResult(1, null, List.of(
                walk(5).names("출발지", "정류장A").coords(127.0, 37.0, 127.1, 37.1)
                        .distance(300.0).build(),
                bus(20).names("정류장A", "정류장B").coords(127.1, 37.1, 127.2, 37.2)
                        .busStops("ST-A", "ST-B").lanes(busLane("721", "BL-721")).build(),
                walk(3).names("정류장B", "정류장C").coords(127.2, 37.2, 127.25, 37.25)
                        .distance(150.0).build(),
                bus(25).names("정류장C", "목적지").coords(127.25, 37.25, 127.5, 37.5)
                        .busStops("ST-C", "ST-Z").lanes(busLane("802", "BL-802")).build()),
                List.of());
    }

    /** 저장된 단계는 같은 계산기가 만든 것과 같다. 그것이 시작 흐름의 계약이다. */
    private List<JourneyLeg> legsOf(PathResult path) {
        return compiler.compile(path).legs().stream()
                .map(draft -> {
                    JourneyLeg leg = JourneyLeg.builder()
                            .journeyId(7L)
                            .routeRevision(1)
                            .legIndex(draft.legIndex())
                            .type(draft.type())
                            .state(JourneyLegState.PENDING)
                            .startName(draft.startName())
                            .endName(draft.endName())
                            .plannedDurationSec(draft.plannedDurationSec())
                            .build();
                    ReflectionTestUtils.setField(leg, "id", 100L + draft.legIndex());
                    return leg;
                })
                .toList();
    }

    /** 쪼개기 결과의 종류를 눈으로 확인할 때 쓴다. */
    @SuppressWarnings("unused")
    private List<JourneyLegType> typesOf(PathResult path) {
        return compiler.compile(path).legs().stream().map(JourneyLegDraft::type).toList();
    }
}
