package watoo.grd.nextroute.application.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.WeatherPort.WeatherFeatures;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;

class MlFeatureVectorBuilderTest {

    // 2026-01-05(월) 08:00:00 KST. 다음날 01-06을 휴일로 설정 → is_pre_holiday=1.
    private static final String DATA_TM = "20260105080000";
    private static final LocalDate NEXT_DAY_HOLIDAY = LocalDate.of(2026, 1, 6);

    private BusPositionInfo position() {
        return new BusPositionInfo(
                "v1", 60, 3, 500.0, 10000.0, "0", "s1",
                DATA_TM, "AB1234", 0, 120, "prev",
                null, null, "0", "0", 1000.0, "next",
                2, "turn", 127.0, 37.5, "1");
    }

    private MlFeatureVectorBuilder builder(WeatherSnapshotService weather) {
        HolidayCalendar holiday = d -> d.equals(NEXT_DAY_HOLIDAY);
        return new MlFeatureVectorBuilder(holiday, weather);
    }

    @Test
    @DisplayName("캘린더+날씨 12키 추가, 적설/severity 미포함")
    void addsCalendarAndWeatherKeys() {
        WeatherSnapshotService weather = mock(WeatherSnapshotService.class);
        when(weather.current()).thenReturn(new WeatherFeatures(5.0, 2.0, 80.0, 1.5, 1, 0));

        Optional<MlFeatureVector> result = builder(weather).build("req", position(), 5, "R1");
        assertThat(result).isPresent();
        Map<String, Object> f = result.get().features();

        // 캘린더: 08시=출근, 01-05 평일, 다음날(01-06) 휴일
        assertThat(f).containsEntry("is_commute_morning", 1)
                .containsEntry("is_commute_evening", 0)
                .containsEntry("is_late_night", 0)
                .containsEntry("is_holiday", 0)
                .containsEntry("is_pre_holiday", 1)
                .containsEntry("is_post_holiday", 0);

        // 날씨: 초단기실황 값 + PTY 파생
        assertThat(f).containsEntry("temperature_c", 5.0)
                .containsEntry("precipitation_mm", 2.0)
                .containsEntry("humidity_pct", 80.0)
                .containsEntry("wind_speed_mps", 1.5)
                .containsEntry("is_raining", 1)
                .containsEntry("is_snowing", 0);

        // 적설/종합심각도는 serve가 산출 불가 → 보내지 않음
        assertThat(f).doesNotContainKeys("snow_depth_cm", "weather_severity");
    }

    @Test
    @DisplayName("날씨 캐시 null이면 날씨 feature는 null이되 키는 존재")
    void weatherNullKeepsKeys() {
        WeatherSnapshotService weather = mock(WeatherSnapshotService.class);
        when(weather.current()).thenReturn(null);

        Map<String, Object> f = builder(weather).build("req", position(), 5, "R1").orElseThrow().features();
        assertThat(f).containsKey("temperature_c");
        assertThat(f.get("temperature_c")).isNull();
        assertThat(f.get("is_raining")).isNull();
        // 캘린더는 날씨와 무관하게 계산됨
        assertThat(f).containsEntry("is_commute_morning", 1);
    }
}
