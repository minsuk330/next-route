package watoo.grd.nextroute.application.route.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static watoo.grd.nextroute.application.route.service.BusTimeParser.SEOUL;

/**
 * BusPositionInfo + targetSeq → MlFeatureVector.
 * feature 이름/파생은 ml/build_dataset.py, train.py와 정확히 동일.
 * 시간 feature는 차량 dataTm(snapshotAt) KST 기준.
 */
@Component
public class MlFeatureVectorBuilder {

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

        return Optional.of(new MlFeatureVector(requestId, features));
    }
}
