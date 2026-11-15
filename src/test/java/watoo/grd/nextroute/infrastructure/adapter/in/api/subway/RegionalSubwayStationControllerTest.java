package watoo.grd.nextroute.infrastructure.adapter.in.api.subway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayServingProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalDepartureReadPort.Row;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalDepartureQueryService;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegionalSubwayStationControllerTest {

    private final RegionalStationSeedCatalog catalog = RegionalStationSeedCatalog.of(List.of(
            new RegionalStationSeed("BS1-134", "BS1", "부산1호선", "노포", 0, null, "134", "g0", 35.28, 129.09),
            new RegionalStationSeed("BS1-119", "BS1", "부산1호선", "서면", 1, null, "119", "g1", 35.15, 129.05),
            new RegionalStationSeed("BS1-95", "BS1", "부산1호선", "다대포해수욕장", 2, null, "95", "g2", 35.04, 128.96)));
    private final RegionalDepartureQueryService query = new RegionalDepartureQueryService(catalog,
            (key, day) -> key.equals("BS1-119") && day.equals("WD")
                    ? List.of(new Row(8 * 3600 + 3 * 60 + 30, "다대포해수욕장", 2), new Row(8 * 3600 + 5 * 60, "노포", 0))
                    : List.of(),
            date -> false);
    private final RegionalSubwayServingProperties serving = new RegionalSubwayServingProperties();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RegionalSubwayStationController(query, serving,
                    Clock.fixed(Instant.parse("2026-11-15T23:00:00Z"), ZoneOffset.UTC)))   // 11-16(월) 08:00 KST
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    // Spring Boot 기본값과 같게(날짜를 ISO 문자열로)
                    .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .build();

    @Test
    @DisplayName("serving off → 404")
    void off() throws Exception {
        serving.setEnabled(false);
        mvc.perform(get("/api/subway/regional/stations/BS1-119/departures")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("응답 계약: source=TIMETABLE, dayType, 방향별 노선 끝 역 방면, scheduledAt(+09:00), minutesUntil")
    void contract() throws Exception {
        serving.setEnabled(true);
        mvc.perform(get("/api/subway/regional/stations/BS1-119/departures?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationKey").value("BS1-119"))
                .andExpect(jsonPath("$.lineName").value("부산1호선"))
                .andExpect(jsonPath("$.source").value("TIMETABLE"))
                .andExpect(jsonPath("$.dayType").value("WEEKDAY"))
                .andExpect(jsonPath("$.timetableDayType").value("WEEKDAY"))
                .andExpect(jsonPath("$.directions[0].towardTerminal").value("노포"))
                .andExpect(jsonPath("$.directions[0].departures[0].minutesUntil").value(5))
                .andExpect(jsonPath("$.directions[1].towardTerminal").value("다대포해수욕장"))
                .andExpect(jsonPath("$.directions[1].departures[0].scheduledAt").value("2026-11-16T08:03:30+09:00"))
                .andExpect(jsonPath("$.directions[1].departures[0].minutesUntil").value(3));
        mvc.perform(get("/api/subway/regional/stations/XX-1/departures")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("구간용: OTP 정류장 id + 기준 시각(at). 내릴 역 방향 하나만, 서울 정류장이면 404")
    void legDepartures() throws Exception {
        serving.setEnabled(true);
        mvc.perform(get("/api/subway/regional/departures")
                        .param("boardStopId", "g1").param("alightStopId", "g2").param("at", "2026-11-16T08:01:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directions.length()").value(1))
                .andExpect(jsonPath("$.directions[0].towardTerminal").value("다대포해수욕장"))
                .andExpect(jsonPath("$.directions[0].departures[0].minutesUntil").value(2));
        mvc.perform(get("/api/subway/regional/departures").param("boardStopId", "1:seoul").param("alightStopId", "g2"))
                .andExpect(status().isNotFound());
        // 내릴 역 없이 OTP id 만: 역 화면(두 방향)
        mvc.perform(get("/api/subway/regional/departures").param("boardStopId", "g1").param("at", "2026-11-16T08:01:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directions.length()").value(2))
                .andExpect(jsonPath("$.stationKey").value("BS1-119"));
    }
}
