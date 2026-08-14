package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 계획 역 체인의 계약.
 *
 * <p>후행 확정이 "승차 다음 역"에서 후보를 가르는데, 그 역을 <b>여기서</b> 정한다. 도착 로그에서
 * 뽑으면 결측 하나에 한 구간(약 2분) 통째로 어긋난 채 확정된다.
 *
 * <p>그리고 반드시 <b>그 개정본의</b> 스냅샷이어야 한다. 세션 것을 읽으면 재탐색 뒤에도 옛 역
 * 체인이 나오는데, 오류 없이 조용히 그렇게 된다.
 */
class JourneySubwayStationChainReaderTest {

    private static final long JOURNEY_ID = 7L;
    private static final int RIDE_LEG_INDEX = 1;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JourneyRevisionRepository revisionRepository;
    private JourneySubwayStationChainReader reader;

    @BeforeEach
    void setUp() {
        revisionRepository = mock(JourneyRevisionRepository.class);
        reader = new JourneySubwayStationChainReader(revisionRepository,
                new JourneyCompiler(new StopIdentityResolver()), objectMapper);
    }

    @Test
    @DisplayName("승차역부터 하차역까지 순서대로 준다")
    void readsTheStationChainInOrder() {
        givenRevision(1, path("강남", "역삼", "선릉", "삼성"));

        assertThat(reader.stationNames(JOURNEY_ID, 1, RIDE_LEG_INDEX))
                .containsExactly("강남", "역삼", "선릉", "삼성");
    }

    @Test
    @DisplayName("순번은 승차역 다음부터 센다 — 0 번은 승차역이다")
    void countsFromTheStationAfterBoarding() {
        List<String> chain = List.of("강남", "역삼", "선릉", "삼성");

        assertThat(reader.stationAfterBoarding(chain, 1)).isEqualTo("역삼");
        assertThat(reader.stationAfterBoarding(chain, 2)).isEqualTo("선릉");
    }

    @Test
    @DisplayName("체인이 짧거나 순번이 없으면 비교역이 없다 — 없으면 확정하지 않는다")
    void returnsNothingWhenTheChainIsTooShort() {
        List<String> chain = List.of("강남", "역삼");

        assertThat(reader.stationAfterBoarding(chain, 5)).isNull();
        assertThat(reader.stationAfterBoarding(chain, null)).isNull();
        assertThat(reader.stationAfterBoarding(chain, 0)).isNull();
    }

    @Test
    @DisplayName("재탐색 뒤에는 그 개정본의 스냅샷을 읽는다 — 세션 것은 시작 시점에 멈춰 있다")
    void readsTheSnapshotOfTheRequestedRevision() {
        givenRevision(1, path("강남", "역삼", "선릉"));
        givenRevision(2, path("사당", "방배", "서초"));

        assertThat(reader.stationNames(JOURNEY_ID, 2, RIDE_LEG_INDEX))
                .containsExactly("사당", "방배", "서초");
    }

    @Test
    @DisplayName("스냅샷이 없거나 깨져 있으면 빈 체인이다 — 확정만 쉬어 가고 잠정 판정은 산다")
    void returnsEmptyWhenTheSnapshotIsMissing() {
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 9))
                .thenReturn(Optional.empty());

        assertThat(reader.stationNames(JOURNEY_ID, 9, RIDE_LEG_INDEX)).isEmpty();
    }

    private void givenRevision(int routeRevision, PathResult path) {
        JourneyRevision revision = JourneyRevision.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(routeRevision)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(objectMapper.valueToTree(path))
                .activatedAt(Instant.now())
                .build();
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
                JOURNEY_ID, routeRevision)).thenReturn(Optional.of(revision));
    }

    /** 걷기 → 지하철. 쪼개기가 대기·타기를 붙이므로 타는 단계는 {@link #RIDE_LEG_INDEX} 다. */
    private PathResult path(String... stationNames) {
        StationResult[] stations = new StationResult[stationNames.length];
        for (int i = 0; i < stationNames.length; i++) {
            stations[i] = new StationResult(i, "9002" + i, stationNames[i], null, null);
        }
        return new PathResult(1, null,
                List.of(SubPathFixture.subway(15)
                        .names(stationNames[0], stationNames[stationNames.length - 1])
                        .subwayStations(900222, 900240).wayCode(2)
                        .stations(stations)
                        .lanes(SubPathFixture.subwayLane("2호선", 2)).build()),
                List.of());
    }
}
