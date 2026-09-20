package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
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
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subway;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subwayLane;
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

    // ── 중간 하차 절단 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("중간 하차는 앞 절반을 남긴다 — [앞 구간] + [승차→X] + [후보]")
    void truncatesRideAtMidStop() {
        PathResult current = twoTransitPathWithStops();

        JourneyRevisionComposer.Composed composed = composer.compose(current, legsOf(current),
                midRideApply(), new JourneyRevisionComposer.RideTruncation(2, 480)).composed();

        // 두 번째 버스 구간이 통째로 사라지지 않았다. 승차→X 로 잘려 남아 있다.
        SubPathResult truncated = composed.snapshot().subPaths().get(3);
        assertThat(truncated.trafficType()).isEqualTo(2);
        assertThat(truncated.startName()).isEqualTo("정류장C");
        assertThat(truncated.endName()).isEqualTo("정류장E");
        assertThat(truncated.endX()).isEqualTo(127.35);
        assertThat(truncated.endY()).isEqualTo(37.35);
        assertThat(truncated.stations()).extracting(StationResult::stationID)
                .containsExactly("OTP:C", "OTP:D", "OTP:E");
        // 소요는 부르는 쪽이 준 실측이다(8분). 원래 구간의 25분이 아니다.
        assertThat(truncated.sectionTime()).isEqualTo(8);
        // 하차 정류장 id 는 원점의 서울 id 다 — 스냅샷 정류장 id(검색 id)와 주소 체계가 다르다.
        assertThat(truncated.endLocalStationID()).isEqualTo("ST-E-SEOUL");
        assertThat(truncated.startLocalStationID()).isEqualTo("ST-C");
        // 잘린 구간의 거리·요금·도착 정보는 알 수 없다. 원래 값을 남기면 "그만큼 더 간다"가 된다.
        assertThat(truncated.distance()).isNull();
        assertThat(truncated.payment()).isNull();
        assertThat(truncated.endArsID()).isNull();

        // 그 뒤가 후보 경로다.
        assertThat(composed.snapshot().subPaths()).hasSize(5);
        assertThat(composed.snapshot().subPaths().get(4).endName()).isEqualTo("목적지");
        assertThat(composed.reason()).isEqualTo("REMAINING_PATH_MID_RIDE_ACCEPTED");
        assertThat(composed.truncatedLegIndex()).isEqualTo(5);
        assertThat(composed.fromLegIndex()).isEqualTo(5);
    }

    @Test
    @DisplayName("지도선은 남은 정류장 좌표를 이은 선이다 — 투영하지 않는다")
    void truncatedRideDrawsStopLine() {
        PathResult current = twoTransitPathWithStops();

        JourneyRevisionComposer.Composed composed = composer.compose(current, legsOf(current),
                midRideApply(), new JourneyRevisionComposer.RideTruncation(2, 480)).composed();

        assertThat(composed.snapshot().subPaths().get(3).polyline())
                .containsExactly(new CoordPoint(127.25, 37.25), new CoordPoint(127.3, 37.3),
                        new CoordPoint(127.35, 37.35));
    }

    @Test
    @DisplayName("합성본을 다시 쪼갠 결과가 곧 새 단계 목록이다 — 절단해도 같다")
    void truncatedSnapshotRecompilesToSameLegs() {
        PathResult current = twoTransitPathWithStops();
        List<JourneyLeg> currentLegs = legsOf(current);

        JourneyRevisionComposer.Composed composed = composer.compose(current, currentLegs,
                midRideApply(), new JourneyRevisionComposer.RideTruncation(2, 480)).composed();

        List<JourneyLegDraft> recompiled = compiler.compile(composed.snapshot()).legs();
        assertThat(composed.legs()).extracting(JourneyLegDraft::type)
                .isEqualTo(recompiled.stream().map(JourneyLegDraft::type).toList());
        // 앞 구간(대기 단계 포함)은 저장된 것과 종류가 같다.
        assertThat(composed.legs().stream().limit(5).map(JourneyLegDraft::type))
                .isEqualTo(currentLegs.stream().limit(5).map(JourneyLeg::getType).toList());
        // 짝 대기 단계는 여전히 <b>타고 있는 그 노선</b>을 가리킨다. 이것이 오염 가드가 지키던 것이다.
        assertThat(composed.legs().get(4).routeOptions())
                .extracting(option -> option.sourceRouteId()).containsExactly("BL-802");
        // 잘린 주행 단계의 계획 소요도 실측을 따른다.
        assertThat(composed.legs().get(5).plannedDurationSec()).isEqualTo(480);
        assertThat(composed.legs().get(5).endName()).isEqualTo("정류장E");
    }

    @Test
    @DisplayName("명시 원점 없는 절단은 거절한다 — 오염 가드를 무르는 장치가 아니다")
    void rejectsTruncationWithoutExplicitOrigin() {
        PathResult current = twoTransitPathWithStops();
        JourneyRevisionComposer.RideTruncation truncation =
                new JourneyRevisionComposer.RideTruncation(2, 480);

        // 원점이 아예 없다(옛 후보).
        assertThat(composer.compose(current, legsOf(current), remainingPath(5), truncation)
                .failureReason()).isEqualTo("truncation-unexpected");
        // 원점이 환승 대기를 가리킨다 — 주행 중간이 아니다.
        assertThat(composer.compose(current, legsOf(current),
                new RerouteCandidateApply.RemainingPathApply(5, candidateFromMidStop(),
                        new RerouteOrigin(5, "ST-E-SEOUL", "BL-802", 12,
                                RerouteOriginKind.TRANSFER_WAIT)),
                truncation).failureReason()).isEqualTo("truncation-unexpected");
        // 원점이 다른 단계를 가리킨다.
        assertThat(composer.compose(current, legsOf(current),
                new RerouteCandidateApply.RemainingPathApply(5, candidateFromMidStop(),
                        new RerouteOrigin(2, "ST-E-SEOUL", "BL-802", 12,
                                RerouteOriginKind.RIDE_INTERIOR)),
                truncation).failureReason()).isEqualTo("truncation-unexpected");
    }

    @Test
    @DisplayName("주행 단계가 아닌 자리는 자르지 않는다")
    void rejectsTruncationOutsideRide() {
        PathResult current = twoTransitPathWithStops();

        // legIndex=4 는 대기 단계다. 자를 주행 구간이 아니다.
        JourneyRevisionComposer.Result result = composer.compose(current, legsOf(current),
                new RerouteCandidateApply.RemainingPathApply(4, candidateFromMidStop(),
                        new RerouteOrigin(4, "ST-E-SEOUL", "BL-802", 12,
                                RerouteOriginKind.RIDE_INTERIOR)),
                new JourneyRevisionComposer.RideTruncation(2, 480));

        assertThat(result.failureReason()).isEqualTo("truncation-unexpected");
    }

    @Test
    @DisplayName("노선만 바꾸는 수락에는 자를 자리가 없다")
    void rejectsTruncationOnRouteOption() {
        PathResult current = twoTransitPathWithStops();

        JourneyRevisionComposer.Result result = composer.compose(current, legsOf(current),
                new RerouteCandidateApply.RouteOptionApply(4, 1, "BL-999", "UP"),
                new JourneyRevisionComposer.RideTruncation(2, 480));

        assertThat(result.failureReason()).isEqualTo("truncation-unexpected");
    }

    @Test
    @DisplayName("승차 정류장이거나 목록 밖이면 자를 수 없다")
    void rejectsUnmappableAlightStop() {
        PathResult current = twoTransitPathWithStops();

        assertThat(composer.compose(current, legsOf(current), midRideApply(),
                new JourneyRevisionComposer.RideTruncation(0, 480)).failureReason())
                .isEqualTo("truncation-unmappable");
        assertThat(composer.compose(current, legsOf(current), midRideApply(),
                new JourneyRevisionComposer.RideTruncation(9, 480)).failureReason())
                .isEqualTo("truncation-unmappable");
    }

    @Test
    @DisplayName("그 자리의 정류장 이름이 다르면 자르지 않는다 — 두 정류장 목록이 어긋난 것이다")
    void rejectsWhenStopNameDoesNotMatch() {
        PathResult current = twoTransitPathWithStops();

        // 부르는 쪽은 서울 노선 순번의 차이로 자리를 찾는다(PR-4). 스냅샷 목록이 한 칸 어긋나 있으면
        // 그 자리에는 다른 정류장이 있고, 그대로 자르면 엉뚱한 곳에서 내리라는 안내가 된다.
        JourneyRevisionComposer.Result result = composer.compose(current, legsOf(current),
                midRideApply(),
                new JourneyRevisionComposer.RideTruncation(2, 480, "정류장D"));

        assertThat(result.failureReason()).isEqualTo("truncation-unmappable");
    }

    @Test
    @DisplayName("이름이 띄어쓰기만 다르면 같은 정류장으로 본다")
    void acceptsStopNameDifferingOnlyBySpacing() {
        PathResult current = twoTransitPathWithStops();

        JourneyRevisionComposer.Result result = composer.compose(current, legsOf(current),
                midRideApply(),
                new JourneyRevisionComposer.RideTruncation(2, 480, "정류장 E"));

        assertThat(result.failed()).isFalse();
    }

    @Test
    @DisplayName("정류장 목록이 없는 옛 스냅샷은 자를 수 없다")
    void rejectsWhenSnapshotHasNoStations() {
        // 정류장 목록 없이 저장된 구간(전환 전 여정)은 X 를 가리킬 방법이 없다.
        PathResult current = twoTransitPath();

        JourneyRevisionComposer.Result result = composer.compose(current, legsOf(current),
                midRideApply(), new JourneyRevisionComposer.RideTruncation(2, 480));

        assertThat(result.failureReason()).isEqualTo("truncation-unmappable");
    }

    @Test
    @DisplayName("지하철 구간은 자르지 않는다 — 초기 스코프는 버스만")
    void rejectsSubwayTruncation() {
        PathResult current = new PathResult(1, null, List.of(
                walk(5).names("출발지", "A역").coords(127.0, 37.0, 127.1, 37.1)
                        .distance(300.0).build(),
                subway(25).names("A역", "Z역").coords(127.1, 37.1, 127.5, 37.5)
                        .subwayStations(101, 105)
                        .stations(new StationResult(0, "OTP:A", "A역", 127.1, 37.1),
                                new StationResult(1, "OTP:B", "B역", 127.2, 37.2),
                                new StationResult(2, "OTP:Z", "Z역", 127.5, 37.5))
                        .lanes(subwayLane("2호선", 2)).build()),
                List.of());

        JourneyRevisionComposer.Result result = composer.compose(current, legsOf(current),
                new RerouteCandidateApply.RemainingPathApply(2, candidateFromMidStop(),
                        new RerouteOrigin(2, "ST-B-SEOUL", "BL-2", 3,
                                RerouteOriginKind.RIDE_INTERIOR)),
                new JourneyRevisionComposer.RideTruncation(1, 480));

        assertThat(result.failureReason()).isEqualTo("truncation-unmappable");
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

    /** 주행 중인 두 번째 버스의 중간 정류장(정류장E)에서 내리는 후보. */
    private RerouteCandidateApply midRideApply() {
        return new RerouteCandidateApply.RemainingPathApply(5, candidateFromMidStop(),
                new RerouteOrigin(5, "ST-E-SEOUL", "BL-802", 12,
                        RerouteOriginKind.RIDE_INTERIOR));
    }

    /** 중간 하차 정류장에서 목적지까지 버스 하나. */
    private PathResult candidateFromMidStop() {
        return new PathResult(1, null, List.of(
                bus(12).names("정류장E", "목적지").coords(127.35, 37.35, 127.5, 37.5)
                        .busStops("ST-E-SEOUL", "ST-Z").lanes(busLane("471", "BL-471")).build()),
                List.of());
    }

    /** 두 번째 버스가 정류장별 목록을 들고 있는 스냅샷. 중간 하차는 그 목록 안에서만 가능하다. */
    private PathResult twoTransitPathWithStops() {
        return new PathResult(1, null, List.of(
                walk(5).names("출발지", "정류장A").coords(127.0, 37.0, 127.1, 37.1)
                        .distance(300.0).build(),
                bus(20).names("정류장A", "정류장B").coords(127.1, 37.1, 127.2, 37.2)
                        .busStops("ST-A", "ST-B").lanes(busLane("721", "BL-721")).build(),
                walk(3).names("정류장B", "정류장C").coords(127.2, 37.2, 127.25, 37.25)
                        .distance(150.0).build(),
                bus(25).names("정류장C", "목적지").coords(127.25, 37.25, 127.5, 37.5)
                        .busStops("ST-C", "ST-Z").ars("11-001", "11-009")
                        .stations(new StationResult(0, "OTP:C", "정류장C", 127.25, 37.25),
                                new StationResult(1, "OTP:D", "정류장D", 127.3, 37.3),
                                new StationResult(2, "OTP:E", "정류장E", 127.35, 37.35),
                                new StationResult(3, "OTP:Z", "목적지", 127.5, 37.5))
                        .distance(8000.0).payment(1500)
                        .lanes(busLane("802", "BL-802")).build()),
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
