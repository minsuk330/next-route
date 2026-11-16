package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartTrackCommand;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.service.TrackJourneyStartService;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.PatternStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpTransitPatternAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 단독 추적 시작을 실제 DB 로(#265 PR-2). OTP 는 실측 응답(2호선 내선 ::10)으로 대신한다.
 *
 * <p>고정하는 것: 시작 → {@code /active} 가 1구간(대기+탑승)·{@code kind=TRACK} 이고, 재탐색 보드 예약이 없다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.track.enabled=true"
})
@DisplayName("단독 추적 시작 IT")
class TrackJourneyStartIT {

    private static final String GANGNAM = "1:RS_ACC1_S-1-0222";
    private static final String PATTERN = "1:RR_ACC1_S-1-02-1I::10";

    @Autowired TrackJourneyStartService startService;
    @Autowired JourneyQueryUseCase queryUseCase;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean TransitPatternPort port;
    @MockBean OtpConcurrencyGate gate;

    private final List<Long> users = new ArrayList<>();
    private TransitPatternDetail detail;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        detail = OtpTransitPatternAdapter.parsePatternDetail(mapper.readTree(
                new ClassPathResource("otp/track/pattern-detail-subway2.json").getContentAsByteArray())).orElseThrow();
        given(gate.tryAcquire(any(), anyString())).willReturn(Optional.of(new OtpPermit("it")));
        given(port.patternsAtStop(eq(GANGNAM), any())).willReturn(List.of(new TransitPattern(PATTERN, null,
                detail.route(), detail.stops().stream().map(s -> new PatternStop(s.gtfsId(), s.name())).toList(), 2)));
        given(port.pattern(eq(PATTERN), any())).willReturn(Optional.of(detail));
        given(port.tripsForDate(eq(PATTERN), any(), any())).willReturn(OtpTransitPatternAdapter.parseTripsForDate(
                mapper.readTree(new ClassPathResource("otp/track/trips-for-date-subway2.json").getContentAsByteArray())));
        given(port.tripStopTimes(anyString(), any(), any())).willReturn(OtpTransitPatternAdapter.parseTripStopTimes(
                mapper.readTree(new ClassPathResource("otp/track/trip-stoptimes-subway2.json").getContentAsByteArray())));
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : users) {
            jdbcTemplate.update("""
                    update journey_leg set selected_route_option_id = null
                    where journey_id in (select id from journey_session where user_id = ?)""", userId);
            jdbcTemplate.update("""
                    delete from journey_leg_route_option where journey_leg_id in (
                        select l.id from journey_leg l join journey_session s on s.id = l.journey_id
                        where s.user_id = ?)""", userId);
            for (String table : List.of("journey_leg", "journey_revision", "transfer_connection", "journey_event",
                    "journey_notification")) {
                jdbcTemplate.update("delete from " + table
                        + " where journey_id in (select id from journey_session where user_id = ?)", userId);
            }
            jdbcTemplate.update("delete from journey_session where user_id = ?", userId);
            jdbcTemplate.update("delete from users where id = ?", userId);
        }
        users.clear();
    }

    @Test
    @DisplayName("시작 → active 가 1구간·kind=TRACK, 보드 예약 없음")
    void startThenActive() {
        Long userId = newUser();
        String alight = detail.stops().get(35).gtfsId();

        JourneySnapshot started = startService.start(userId,
                new StartTrackCommand("1:RR_ACC1_S-1-02-1I", GANGNAM, alight, PATTERN, false));

        assertThat(started.kind()).isEqualTo(JourneyKind.TRACK);
        JourneySnapshot active = queryUseCase.getActive(userId).orElseThrow();
        assertThat(active.journeyId()).isEqualTo(started.journeyId());
        assertThat(active.kind()).isEqualTo(JourneyKind.TRACK);
        assertThat(active.legs()).extracting(JourneyLegView::type)
                .containsExactly(JourneyLegType.WAIT_SUBWAY, JourneyLegType.RIDE_SUBWAY);
        // #614 단독 추적은 검색 실시간이 없다 → 첫 후보, 주인 DEFAULT
        assertThat(active.legs()).allSatisfy(leg -> {
            assertThat(leg.selectedRouteOptionId()).isEqualTo(active.legs().get(0).routeOptions().get(0).optionId());
            assertThat(leg.routeOptionSelectedBy()).isEqualTo("DEFAULT");
        });
        assertThat(jdbcTemplate.queryForObject("select kind from journey_session where id = ?", String.class,
                started.journeyId())).isEqualTo("TRACK");
        assertThat(jdbcTemplate.queryForObject("select reroute_refresh_at is null from journey_session where id = ?",
                Boolean.class, started.journeyId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select route_snapshot -> 'info' ->> 'lastEndStation' from journey_session where id = ?",
                String.class, started.journeyId())).isEqualTo(detail.stops().get(35).name());
    }

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id""", Long.class);
        users.add(id);
        return id;
    }
}
