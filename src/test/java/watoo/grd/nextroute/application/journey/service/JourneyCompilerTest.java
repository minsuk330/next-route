package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.namelessLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subway;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subwayLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/**
 * 경로 쪼개기의 계약을 고정한다.
 *
 * <p>확인하는 것은 넷이다 — 교통수단이 기다리기와 타고 가기로 나뉘는지, 중간 도보만 환승 도보가
 * 되는지, 노선 후보 순서가 보존되는지, 식별자가 없을 때 단계를 죽이지 않고 근거 품질만 낮추는지.
 */
class JourneyCompilerTest {

    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());

    private PathResult path(SubPathResult... subPaths) {
        return new PathResult(1, null, List.of(subPaths), List.of());
    }

    private List<JourneyLegType> typesOf(JourneyPlan plan) {
        return plan.legs().stream().map(JourneyLegDraft::type).toList();
    }

    private List<JourneyLegState> statesOf(JourneyPlan plan) {
        return plan.legs().stream().map(JourneyLegDraft::state).toList();
    }

    // ── 시나리오별 쪼개기 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("검색 결과 모양별로")
    class Shapes {

        @Test
        @DisplayName("S1 도보 → 버스")
        void s1_walkThenBus() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).names("집", "정류장A").build(),
                    bus(20).names("정류장A", "정류장F")
                            .busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370")).build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WALK,
                    JourneyLegType.WAIT_BUS,
                    JourneyLegType.RIDE_BUS);
            assertThat(plan.transfers()).isEmpty();
        }

        @Test
        @DisplayName("S2 버스 → 버스")
        void s2_busThenBus() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).names("정류장A", "정류장F")
                            .busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370")).build(),
                    walk(3).names("정류장F", "정류장G").distance(180.0).build(),
                    bus(10).names("정류장G", "정류장Z")
                            .busStops("ST-G", "ST-Z")
                            .lanes(busLane("721", "BL-721")).build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WAIT_BUS,
                    JourneyLegType.RIDE_BUS,
                    JourneyLegType.TRANSFER_WALK,
                    JourneyLegType.WAIT_BUS,
                    JourneyLegType.RIDE_BUS);
            assertThat(plan.transfers()).singleElement()
                    .isEqualTo(new TransferConnectionDraft(0, 1, 2, 3));
        }

        @Test
        @DisplayName("S3 지하철 → 버스")
        void s3_subwayThenBus() {
            JourneyPlan plan = compiler.compile(path(
                    subway(15).names("강남", "신촌")
                            .subwayStations(222, 240)
                            .lanes(subwayLane("2호선", 2)).build(),
                    walk(4).names("신촌", "정류장G").distance(200.0).build(),
                    bus(10).names("정류장G", "정류장Z")
                            .busStops("ST-G", "ST-Z")
                            .lanes(busLane("721", "BL-721")).build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WAIT_SUBWAY,
                    JourneyLegType.RIDE_SUBWAY,
                    JourneyLegType.TRANSFER_WALK,
                    JourneyLegType.WAIT_BUS,
                    JourneyLegType.RIDE_BUS);
            assertThat(plan.transfers()).singleElement()
                    .isEqualTo(new TransferConnectionDraft(0, 1, 2, 3));
        }

        @Test
        @DisplayName("S4 버스 → 지하철")
        void s4_busThenSubway() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).names("정류장A", "정류장F")
                            .busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370")).build(),
                    walk(6).names("정류장F", "강남").distance(320.0).build(),
                    subway(15).names("강남", "신촌")
                            .subwayStations(222, 240)
                            .lanes(subwayLane("2호선", 2)).build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WAIT_BUS,
                    JourneyLegType.RIDE_BUS,
                    JourneyLegType.TRANSFER_WALK,
                    JourneyLegType.WAIT_SUBWAY,
                    JourneyLegType.RIDE_SUBWAY);
        }

        @Test
        @DisplayName("S6 다중 환승 — 환승 연결이 교통수단 사이마다 하나씩")
        void s6_multipleTransfers() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).names("집", "정류장A").build(),
                    bus(20).names("정류장A", "정류장F")
                            .busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370")).build(),
                    walk(4).names("정류장F", "강남").distance(210.0).build(),
                    subway(15).names("강남", "신촌")
                            .subwayStations(222, 240)
                            .lanes(subwayLane("2호선", 2)).build(),
                    walk(3).names("신촌", "정류장G").distance(150.0).build(),
                    bus(10).names("정류장G", "정류장Z")
                            .busStops("ST-G", "ST-Z")
                            .lanes(busLane("721", "BL-721")).build(),
                    walk(7).names("정류장Z", "회사").build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WALK,
                    JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS,
                    JourneyLegType.TRANSFER_WALK,
                    JourneyLegType.WAIT_SUBWAY, JourneyLegType.RIDE_SUBWAY,
                    JourneyLegType.TRANSFER_WALK,
                    JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS,
                    JourneyLegType.WALK);
            assertThat(plan.transfers()).containsExactly(
                    new TransferConnectionDraft(0, 2, 3, 4),
                    new TransferConnectionDraft(1, 5, 6, 7));
        }

        @Test
        @DisplayName("빈 경로는 예외 대신 빈 결과를 준다")
        void emptyPath() {
            assertThat(compiler.compile(null).isEmpty()).isTrue();
            assertThat(compiler.compile(new PathResult(1, null, List.of(), List.of())).isEmpty())
                    .isTrue();
        }
    }

    // ── 걷기의 종류 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("걷기는")
    class Walks {

        @Test
        @DisplayName("처음과 끝만 WALK, 교통수단 사이는 TRANSFER_WALK")
        void onlyMiddleWalkIsTransfer() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).names("집", "정류장A").build(),
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build(),
                    walk(3).names("정류장F", "정류장G").distance(120.0).build(),
                    bus(10).busStops("ST-G", "ST-Z").lanes(busLane("721", "BL-721")).build(),
                    walk(7).names("정류장Z", "회사").build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WALK,
                    JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS,
                    JourneyLegType.TRANSFER_WALK,
                    JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS,
                    JourneyLegType.WALK);
        }

        @Test
        @DisplayName("같은 정류장 환승은 0초짜리 걷기 단계를 만들지 않는다")
        void sameStopTransfer_hasNoWalkLeg() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).names("정류장A", "정류장F")
                            .busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370")).build(),
                    walk(0).names("정류장F", "정류장F").distance(0.0).build(),
                    bus(10).names("정류장F", "정류장Z")
                            .busStops("ST-F", "ST-Z")
                            .lanes(busLane("721", "BL-721")).build()));

            assertThat(typesOf(plan)).containsExactly(
                    JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS,
                    JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS);
            assertThat(plan.transfers()).singleElement()
                    .satisfies(t -> {
                        assertThat(t.walkLegIndex()).isNull();
                        assertThat(t.isSameStop()).isTrue();
                        assertThat(t.fromLegIndex()).isEqualTo(1);
                        assertThat(t.toLegIndex()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("정류장이 다르면 0분이어도 걷기 단계를 남긴다")
        void differentStop_keepsWalkLeg() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build(),
                    walk(0).names("정류장F", "정류장G").distance(0.0).build(),
                    bus(10).busStops("ST-G", "ST-Z").lanes(busLane("721", "BL-721")).build()));

            assertThat(typesOf(plan)).contains(JourneyLegType.TRANSFER_WALK);
            assertThat(plan.transfers()).singleElement()
                    .satisfies(t -> assertThat(t.walkLegIndex()).isEqualTo(2));
        }

        @Test
        @DisplayName("실측 도보 시간이 있으면 그것을 쓴다")
        void walkDuration_prefersMeasuredSeconds() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).walkSeconds(412).build(),
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).plannedDurationSec()).isEqualTo(412);
        }
    }

    // ── 상태와 소요시간 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("시작 상태는")
    class InitialState {

        @Test
        @DisplayName("첫 단계만 ACTIVE, 나머지는 PENDING")
        void firstLegActive() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).build(),
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(statesOf(plan)).containsExactly(
                    JourneyLegState.ACTIVE,
                    JourneyLegState.PENDING,
                    JourneyLegState.PENDING);
        }

        @Test
        @DisplayName("걷기 없이 버스로 시작해도 첫 단계가 ACTIVE")
        void firstLegActive_evenWhenTransitFirst() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).type()).isEqualTo(JourneyLegType.WAIT_BUS);
            assertThat(statesOf(plan)).containsExactly(
                    JourneyLegState.ACTIVE, JourneyLegState.PENDING);
        }

        @Test
        @DisplayName("기다리는 시간은 알 수 없어 비워 두고, 타는 시간은 분을 초로 바꾼다")
        void durations() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).plannedDurationSec()).isNull();
            assertThat(plan.legs().get(1).plannedDurationSec()).isEqualTo(1200);
        }

        @Test
        @DisplayName("기다리기와 타고가기가 서로를 가리킨다")
        void pairedLegs() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).build(),
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).pairedLegIndex()).isNull();
            assertThat(plan.legs().get(1).pairedLegIndex()).isEqualTo(2);
            assertThat(plan.legs().get(2).pairedLegIndex()).isEqualTo(1);
        }
    }

    // ── 노선 후보 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("노선 후보는")
    class RouteOptions {

        @Test
        @DisplayName("여러 개면 전부 순서대로 보존한다")
        void multipleLanes_allPreservedInOrder() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370"),
                                    busLane("721", "BL-721"),
                                    busLane("140", "BL-140")).build()));

            JourneyLegDraft wait = plan.legs().get(0);
            assertThat(wait.routeOptions())
                    .extracting(JourneyRouteOptionDraft::optionIndex,
                            JourneyRouteOptionDraft::laneIndex,
                            JourneyRouteOptionDraft::sourceRouteId,
                            JourneyRouteOptionDraft::routeName)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(0, 0, "BL-370", "370"),
                            org.assertj.core.groups.Tuple.tuple(1, 1, "BL-721", "721"),
                            org.assertj.core.groups.Tuple.tuple(2, 2, "BL-140", "140"));
            assertThat(wait.hasSingleRouteOption()).isFalse();
        }

        @Test
        @DisplayName("기다리는 단계만 들고 있다 — 타는 단계는 짝을 가리키기만 한다")
        void onlyWaitLegOwnsOptions() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F")
                            .lanes(busLane("370", "BL-370"), busLane("721", "BL-721")).build()));

            assertThat(plan.legs().get(0).routeOptions()).hasSize(2);
            assertThat(plan.legs().get(1).routeOptions()).isEmpty();
        }

        @Test
        @DisplayName("하나뿐이면 시작 시점에 확정할 수 있다고 표시된다")
        void singleLane() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).hasSingleRouteOption()).isTrue();
        }

        @Test
        @DisplayName("내부 노선 식별자는 이 단계에서 채우지 않는다")
        void routeIdNotResolvedHere() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).routeOptions().get(0).routeId()).isNull();
            assertThat(plan.legs().get(0).routeOptions().get(0).sourceRouteId()).isEqualTo("BL-370");
        }
    }

    // ── 불완전한 데이터 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("식별자가 없으면")
    class MissingIdentifiers {

        @Test
        @DisplayName("단계를 죽이지 않고 근거 품질만 모름으로 둔다")
        void unknownEvidence_butLegsStillUsable() {
            JourneyPlan plan = compiler.compile(path(
                    walk(5).names("집", "어느 정류장").build(),
                    bus(20).names("어느 정류장", "다른 정류장")
                            .coords(127.0, 37.5, 127.01, 37.51)
                            .lanes(namelessLane()).build()));

            JourneyLegDraft wait = plan.legs().get(1);
            JourneyLegDraft ride = plan.legs().get(2);

            assertThat(wait.evidenceQuality()).isEqualTo(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN);
            assertThat(ride.evidenceQuality()).isEqualTo(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN);
            assertThat(wait.state())
                    .as("근거가 없다고 진행 상태를 UNKNOWN 으로 만들면 손으로도 진행할 수 없다")
                    .isEqualTo(JourneyLegState.PENDING);
            assertThat(ride.state()).isEqualTo(JourneyLegState.PENDING);
        }

        @Test
        @DisplayName("정류장 목록 식별자는 원천 ID로 보존하고 내부 ID 칸은 비워 둔다")
        void fallsBackToStationList() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).names("정류장A", "정류장F")
                            .stations(
                                    new watoo.grd.nextroute.application.route.dto.StationResult(
                                            0, "ST-A", "정류장A", 127.0, 37.5),
                                    new watoo.grd.nextroute.application.route.dto.StationResult(
                                            1, "ST-F", "정류장F", 127.01, 37.51))
                            .lanes(busLane("370", "BL-370")).build()));

            JourneyLegDraft ride = plan.legs().get(1);
            assertThat(ride.startStopId()).isNull();
            assertThat(ride.endStopId()).isNull();
            assertThat(ride.sourceStartStopId()).isEqualTo("ST-A");
            assertThat(ride.sourceEndStopId()).isEqualTo("ST-F");
            assertThat(ride.evidenceQuality()).isEqualTo(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN);
        }

        @Test
        @DisplayName("ODSAY 식별자가 있어도 내부 해석 전까지는 근거 품질을 모름으로 둔다")
        void identifiersPresent_stillNeedsResolution() {
            JourneyPlan plan = compiler.compile(path(
                    bus(20).busStops("ST-A", "ST-F").lanes(busLane("370", "BL-370")).build()));

            assertThat(plan.legs().get(0).evidenceQuality())
                    .isEqualTo(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN);
        }
    }

    @Test
    @DisplayName("입력 경로를 고치지 않는다")
    void inputNotMutated() {
        SubPathResult busSub = bus(20).names("정류장A", "정류장F")
                .busStops("ST-A", "ST-F")
                .lanes(busLane("370", "BL-370")).build();
        PathResult input = path(walk(5).build(), busSub);
        PathResult copy = path(walk(5).build(), busSub);

        compiler.compile(input);

        assertThat(input).isEqualTo(copy);
    }

    @Nested
    @DisplayName("#266 지하철 방면(다음 역)")
    class DirectionName {

        @Test
        @DisplayName("승차역 다음 역 이름이 대기·탑승 단계 둘 다에 실린다")
        void nextStationOnWaitAndRide() {
            JourneyPlan plan = compiler.compile(path(
                    subway(15).names("강남", "서초")
                            .subwayStations(222, 224)
                            .stations(station(0, "강남"), station(1, "교대"), station(2, "서초"))
                            .lanes(subwayLane("2호선", 2)).build()));

            assertThat(plan.legs()).extracting(leg -> leg.guidance().directionName())
                    .containsExactly("교대", "교대");
        }

        @Test
        @DisplayName("#266 PR-4 탑승 단계에 하차 직전 역 이름(빠른 하차 방향용) — 대기 단계는 null")
        void alightPrevName() {
            JourneyPlan plan = compiler.compile(path(
                    subway(15).names("강남", "서초")
                            .subwayStations(222, 224)
                            .stations(station(0, "강남"), station(1, "교대(2호선)"), station(2, "서초"))
                            .lanes(subwayLane("2호선", 2)).build()));

            assertThat(plan.legs()).extracting(JourneyLegDraft::alightPrevName).containsExactly(null, "교대");
        }

        @Test
        @DisplayName("정차 목록이 승·하차 두 점뿐이면 null — OTP 폴백이라 두 번째 점이 하차역일 수 있다")
        void twoPointStopListIsUnknown() {
            JourneyPlan plan = compiler.compile(path(
                    subway(3).names("강남", "교대")
                            .subwayStations(222, 223)
                            .stations(station(0, "강남"), station(1, "교대"))
                            .lanes(subwayLane("2호선", 2)).build()));

            assertThat(plan.legs()).extracting(leg -> leg.guidance().directionName())
                    .containsOnlyNulls();
        }

        @Test
        @DisplayName("버스·걷기에는 방면이 없다")
        void busHasNoDirection() {
            JourneyPlan plan = compiler.compile(path(
                    walk(3).names("출발", "정류장A").distance(100.0).build(),
                    bus(10).names("정류장A", "정류장B").busStops("ST-A", "ST-B")
                            .stations(station(0, "정류장A"), station(1, "중간"), station(2, "정류장B"))
                            .lanes(busLane("721", "BL-721")).build()));

            assertThat(plan.legs()).extracting(leg -> leg.guidance().directionName())
                    .containsOnlyNulls();
        }

        private watoo.grd.nextroute.application.route.dto.StationResult station(int index, String name) {
            return new watoo.grd.nextroute.application.route.dto.StationResult(index, null, name, 127.0, 37.5);
        }
    }

}
