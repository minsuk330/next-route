package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.WeatherPort.WeatherFeatures;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static watoo.grd.nextroute.application.route.service.BusTimeParser.SEOUL;

/**
 * BusPositionInfo + targetSeq → MlFeatureVector.
 * feature 이름/파생은 ml/build_dataset.py, train.py와 정확히 동일.
 * 시간 feature는 차량 dataTm(snapshotAt) KST 기준.
 * <p>
 * 캘린더 경계 상수·날씨 파생은 build_dataset.py와 1:1로 맞춘다(train/serve skew 방지).
 * 휴일은 기존 {@link HolidayCalendar}(Python sidecar와 동일 gov API), 날씨는 미리 폴링된
 * {@link WeatherSnapshotService} 캐시만 읽어 builder 내 I/O는 없다.
 */
@Component
@RequiredArgsConstructor
public class MlFeatureVectorBuilder {

    // build_dataset.py COMMUTE_MORNING/EVENING/LATE_NIGHT 상수와 동일. [start, end) 반열림.
    private static final int COMMUTE_MORNING_START = 7;
    private static final int COMMUTE_MORNING_END = 9;
    private static final int COMMUTE_EVENING_START = 18;
    private static final int COMMUTE_EVENING_END = 20;
    private static final int LATE_NIGHT_START = 23; // hour >= 23
    private static final int LATE_NIGHT_END = 5;    // or hour < 5

    private final HolidayCalendar holidayCalendar;
    private final WeatherSnapshotService weatherSnapshot;

    /**
     * dataTm 파싱·sectionOrder·targetSeq 누락 차량은 호출 전에 필터링할 것.
     * null 값은 serving이 NaN으로 처리(허용).
     */
    public Optional<MlFeatureVector> build(String requestId, BusPositionInfo pos, int targetSeq, String routeId) {
        if (pos.sectionOrder() == null || pos.dataTm() == null) return Optional.empty();

        var snapshotInstant = BusTimeParser.parse(pos.dataTm());
        if (snapshotInstant.isEmpty()) return Optional.empty();

        ZonedDateTime snap = snapshotInstant.get().atZone(SEOUL);
        int hour = snap.getHour();
        int minuteOfDay = hour * 60 + snap.getMinute();
        int dow = snap.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
        int isWeekend = dow >= 6 ? 1 : 0;

        int sectionOrder = pos.sectionOrder();
        int remainingStopCount = targetSeq - sectionOrder;

        Double sectionProgress = null;
        if (pos.fullSectionDistance() != null && pos.fullSectionDistance() != 0.0) {
            sectionProgress = pos.sectionDistance() != null
                    ? pos.sectionDistance() / pos.fullSectionDistance()
                    : null;
        }

        Map<String, Object> features = new HashMap<>();
        features.put("current_section_order", sectionOrder);
        features.put("section_progress", sectionProgress);
        features.put("current_section_distance", pos.sectionDistance());
        features.put("current_full_section_distance", pos.fullSectionDistance());
        features.put("next_stop_time", pos.nextStopTime());
        features.put("last_stop_time", pos.lastStopTime());
        features.put("congestion", pos.congestion());
        features.put("gps_x", pos.gpsX());
        features.put("gps_y", pos.gpsY());
        features.put("target_seq", targetSeq);
        features.put("remaining_stop_count", remainingStopCount);
        features.put("hour_of_day", hour);
        features.put("minute_of_day", minuteOfDay);
        features.put("day_of_week", dow);
        features.put("is_weekend", isWeekend);
        features.put("route_id", routeId);

        putCalendarFeatures(features, snap.toLocalDate(), hour);
        putWeatherFeatures(features);

        // 차량·관측시각은 feature 가 아니라 rt_* 계산용 식별자 — serving 이 route_history 에서 찾는다.
        return Optional.of(new MlFeatureVector(requestId, features, pos.vehicleId(), snapshotInstant.get()));
    }

    /** is_holiday/pre/post + commute/late. build_dataset.calendar_columns와 동일 규칙. */
    private void putCalendarFeatures(Map<String, Object> features, LocalDate date, int hour) {
        features.put("is_holiday", holidayCalendar.isHoliday(date) ? 1 : 0);
        features.put("is_pre_holiday", holidayCalendar.isHoliday(date.plusDays(1)) ? 1 : 0);
        features.put("is_post_holiday", holidayCalendar.isHoliday(date.minusDays(1)) ? 1 : 0);
        features.put("is_commute_morning",
                hour >= COMMUTE_MORNING_START && hour < COMMUTE_MORNING_END ? 1 : 0);
        features.put("is_commute_evening",
                hour >= COMMUTE_EVENING_START && hour < COMMUTE_EVENING_END ? 1 : 0);
        features.put("is_late_night",
                hour >= LATE_NIGHT_START || hour < LATE_NIGHT_END ? 1 : 0);
    }

    /**
     * 캐시된 현재 날씨(초단기실황)를 put. 캐시 없으면(콜드/실패) 전부 null → serving NaN.
     * snow_depth_cm/weather_severity는 실황에서 산출 불가 → 보내지 않음(모델 학습 제외).
     */
    private void putWeatherFeatures(Map<String, Object> features) {
        WeatherFeatures w = weatherSnapshot.current();
        features.put("temperature_c", w == null ? null : w.temperatureC());
        features.put("precipitation_mm", w == null ? null : w.precipitationMm());
        features.put("humidity_pct", w == null ? null : w.humidityPct());
        features.put("wind_speed_mps", w == null ? null : w.windSpeedMps());
        features.put("is_raining", w == null ? null : w.isRaining());
        features.put("is_snowing", w == null ? null : w.isSnowing());
    }
}
