package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/**
 * 환승 정류장의 좌표 찾기. {@code journey_leg} 에 좌표 칸이 없어서 스냅샷을 다시 쪼갠다.
 *
 * <p>이 재컴파일이 ④의 유일한 외줄이다 — "저장된 단계와 같은 규칙으로 쪼개진다"는 가정이 깨지면
 * 좌표가 <b>다른 정류장</b>을 가리키고, 그러면 엉뚱한 곳에서 출발하는 경로를 제안하게 된다.
 * 그래서 재컴파일한 단계 번호가 저장된 단계 번호와 같은지를 여기서 못박는다.
 */
@DisplayName("환승 지점 좌표 찾기")
class JourneyWaypointResolverTest {

    private static final long JOURNEY_ID = 7L;

    private final JourneyRevisionRepository revisionRepository = mock(JourneyRevisionRepository.class);
    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JourneyWaypointResolver resolver =
            new JourneyWaypointResolver(revisionRepository, compiler, objectMapper);

    @Test
    @DisplayName("재컴파일한 단계 번호가 저장된 단계 번호와 같다 — 그래야 좌표가 그 정류장을 가리킨다")
    void legIndexMatchesStoredLegs() {
        PathResult path = twoTransitPath();
        givenRevision(1, path);

        // 저장 흐름과 같은 계산기로 쪼갠 결과가 곧 저장된 단계다.
        JourneyPlan stored = compiler.compile(path);
        JourneyLegDraft secondWait = stored.legs().stream()
                .filter(leg -> leg.type().isWaiting())
                .skip(1)
                .findFirst()
                .orElseThrow();

        JourneyWaypointResolver.Waypoints waypoints =
                resolver.resolve(JOURNEY_ID, 1, secondWait.legIndex()).orElseThrow();

        assertThat(waypoints.origin().x()).isEqualTo(secondWait.startX());
        assertThat(waypoints.origin().y()).isEqualTo(secondWait.startY());
    }

    @Test
    @DisplayName("도착점은 마지막 단계의 끝 좌표다 — 목적지는 바뀌지 않는다")
    void destinationIsLastLegEnd() {
        givenRevision(1, twoTransitPath());

        JourneyWaypointResolver.Waypoints waypoints = resolver.resolve(JOURNEY_ID, 1, 2).orElseThrow();

        assertThat(waypoints.destination().x()).isEqualTo(127.5);
        assertThat(waypoints.destination().y()).isEqualTo(37.5);
    }

    @Test
    @DisplayName("이름도 같은 단계에서 집는다 — 출발은 그 단계의 시작 이름, 도착은 마지막 단계의 끝 이름(#474)")
    void namesComeFromSameLegs() {
        givenRevision(1, twoTransitPath());
        JourneyLegDraft secondWait = compiler.compile(twoTransitPath()).legs().stream()
                .filter(leg -> leg.type().isWaiting())
                .skip(1)
                .findFirst()
                .orElseThrow();

        JourneyWaypointResolver.Waypoints waypoints =
                resolver.resolve(JOURNEY_ID, 1, secondWait.legIndex()).orElseThrow();

        assertThat(waypoints.originName()).isEqualTo("정류장C");
        // 마지막 걷기의 끝 이름 = 처음 검색 때 고른 목적지 이름(#471).
        assertThat(waypoints.destinationName()).isEqualTo("목적지");
    }

    @Test
    @DisplayName("목적지 이름이 비어 있어도 좌표가 있으면 찾는다 — 이름은 지어내지 않고 null 로 둔다")
    void missingDestinationNameStillResolves() {
        PathResult path = new PathResult(1, null, List.of(
                bus(20).names("정류장A", "정류장B").coords(127.1, 37.1, 127.2, 37.2)
                        .lanes(busLane("721", "BL-721")).busStops("ST-A", "ST-B").build(),
                walk(4).coords(127.2, 37.2, 127.5, 37.5).build()),
                List.of());
        givenRevision(1, path);

        JourneyWaypointResolver.Waypoints waypoints = resolver.resolve(JOURNEY_ID, 1, 0).orElseThrow();

        assertThat(waypoints.destination().x()).isEqualTo(127.5);
        assertThat(waypoints.destinationName()).isNull();
    }

    @Test
    @DisplayName("#471 전 OTP 스냅샷처럼 마지막 걷기에 좌표가 없으면 예전처럼 빈 결과다 — 재검색을 하지 않는다")
    void preFillOtpSnapshotWithoutWalkCoordsIsEmpty() {
        PathResult path = new PathResult(1, null, List.of(
                bus(20).names("정류장A", "정류장B").coords(127.1, 37.1, 127.2, 37.2)
                        .lanes(busLane("721", "BL-721")).busStops("ST-A", "ST-B").build(),
                walk(4).build()),
                List.of());
        givenRevision(1, path);

        assertThat(resolver.resolve(JOURNEY_ID, 1, 0)).isEmpty();
    }

    @Test
    @DisplayName("출발점만 바꿔도 목적지 좌표·이름은 그대로다")
    void departingFromKeepsDestination() {
        JourneyWaypointResolver.Waypoints planned = new JourneyWaypointResolver.Waypoints(
                new JourneyWaypointResolver.Coord(127.3, 37.3),
                new JourneyWaypointResolver.Coord(127.5, 37.5), "정류장C", "코엑스");

        JourneyWaypointResolver.Waypoints moved =
                planned.departingFrom(new JourneyWaypointResolver.Coord(127.1, 37.1), "정류장B");

        assertThat(moved).isEqualTo(new JourneyWaypointResolver.Waypoints(
                new JourneyWaypointResolver.Coord(127.1, 37.1),
                new JourneyWaypointResolver.Coord(127.5, 37.5), "정류장B", "코엑스"));
    }

    @Test
    @DisplayName("개정본은 번호로 집는다 — ACTIVE 를 읽으면 옛 단계와 새 좌표가 섞인다")
    void looksUpByRevisionNumber() {
        givenRevision(3, twoTransitPath());

        resolver.resolve(JOURNEY_ID, 3, 0);

        verify(revisionRepository).findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 3);
        verifyNoMoreInteractions(revisionRepository);
    }

    @Test
    @DisplayName("그 번호의 개정본이 없으면 빈 결과")
    void missingRevisionIsEmpty() {
        given(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 9))
                .willReturn(Optional.empty());

        assertThat(resolver.resolve(JOURNEY_ID, 9, 0)).isEmpty();
    }

    @Test
    @DisplayName("없는 단계 번호를 물으면 빈 결과")
    void unknownLegIndexIsEmpty() {
        givenRevision(1, twoTransitPath());

        assertThat(resolver.resolve(JOURNEY_ID, 1, 99)).isEmpty();
    }

    @Test
    @DisplayName("좌표가 비어 있으면 빈 결과 — 0,0 으로 검색하지 않는다")
    void missingCoordinatesIsEmpty() {
        PathResult path = new PathResult(1, null,
                List.of(bus(20).names("정류장A", "정류장B").lanes(busLane("721", "BL-721")).build()),
                List.of());
        givenRevision(1, path);

        assertThat(resolver.resolve(JOURNEY_ID, 1, 0)).isEmpty();
    }

    @Test
    @DisplayName("스냅샷이 경로 모양이 아니면 빈 결과 — 예외를 올리지 않는다")
    void brokenSnapshotIsEmpty() {
        JourneyRevision revision = JourneyRevision.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(1)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(objectMapper.createObjectNode().put("subPaths", "깨진 값"))
                .activatedAt(Instant.parse("2026-08-13T09:00:00Z"))
                .build();
        given(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 1))
                .willReturn(Optional.of(revision));

        assertThat(resolver.resolve(JOURNEY_ID, 1, 0)).isEmpty();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 도보 → 버스 → 도보 → 버스 → 도보. 환승이 하나 있는 흔한 모양이다. */
    private PathResult twoTransitPath() {
        return new PathResult(1, null, List.of(
                walk(5).names("출발지", "정류장A").coords(127.0, 37.0, 127.1, 37.1).build(),
                bus(20).names("정류장A", "정류장B").coords(127.1, 37.1, 127.2, 37.2)
                        .lanes(busLane("721", "BL-721")).busStops("ST-A", "ST-B").build(),
                walk(3).names("정류장B", "정류장C").coords(127.2, 37.2, 127.3, 37.3).distance(200.0).build(),
                bus(15).names("정류장C", "정류장D").coords(127.3, 37.3, 127.4, 37.4)
                        .lanes(busLane("360", "BL-360")).busStops("ST-C", "ST-D").build(),
                walk(4).names("정류장D", "목적지").coords(127.4, 37.4, 127.5, 37.5).build()),
                List.of());
    }

    private void givenRevision(int routeRevision, PathResult path) {
        JourneyRevision revision = JourneyRevision.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(routeRevision)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(objectMapper.valueToTree(path))
                .activatedAt(Instant.parse("2026-08-13T09:00:00Z"))
                .build();
        given(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
                JOURNEY_ID, routeRevision)).willReturn(Optional.of(revision));
    }
}
