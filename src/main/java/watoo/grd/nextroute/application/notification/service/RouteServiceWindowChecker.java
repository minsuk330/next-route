package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 버스 승차 지점의 첫차/막차(서비스윈도) 판정. {@link BusRouteStop} 의 beginTm/lastTm(정류장별)로
 * 예상 승차시각이 운행시간 내인지 본다.
 *
 * <p>포맷 불확실(§8 미확인) 대비: 파싱 실패는 {@link Availability#UNKNOWN}(permissive) 로 두어
 * OUT_OF_SERVICE 오발을 막는다. 자정 넘김(막차 < 첫차)은 막차에 +24h.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteServiceWindowChecker {

    private static final int DAY_MINUTES = 24 * 60;

    private final BusRouteStopRepository routeStopRepository;

    public enum Availability { AVAILABLE, OUT_OF_SERVICE, UNKNOWN }

    /**
     * @param routeId          resolve 된 노선 ID
     * @param stopId           resolve 된 승차 정류장 ID
     * @param expectedBoarding 예상 승차시각(Asia/Seoul LocalDateTime)
     */
    public Availability judge(String routeId, String stopId, LocalDateTime expectedBoarding) {
        if (routeId == null || stopId == null || expectedBoarding == null) return Availability.UNKNOWN;

        List<BusRouteStop> stops = routeStopRepository.findByRouteIdAndStopId(routeId, stopId);
        BusRouteStop stop = stops.stream()
                .filter(s -> s.getBeginTm() != null && s.getLastTm() != null)
                .findFirst().orElse(null);
        if (stop == null) return Availability.UNKNOWN;

        Integer begin = parseMinuteOfDay(stop.getBeginTm());
        Integer last = parseMinuteOfDay(stop.getLastTm());
        if (begin == null || last == null) return Availability.UNKNOWN;

        // 막차가 첫차보다 이르면 자정 넘김 → 막차에 +24h.
        int lastAdj = last < begin ? last + DAY_MINUTES : last;

        int boarding = expectedBoarding.getHour() * 60 + expectedBoarding.getMinute();
        // 승차시각이 첫차보다 이르면 자정 넘김 창의 새벽 시간대일 수 있음 → +24h 로 창 안 여부 재확인.
        int boardingAdj = boarding < begin ? boarding + DAY_MINUTES : boarding;

        boolean inWindow = boardingAdj >= begin && boardingAdj <= lastAdj;
        return inWindow ? Availability.AVAILABLE : Availability.OUT_OF_SERVICE;
    }

    /**
     * 시각 문자열 → 자정 기준 분(0~). 지원: "HHmm", "HHmmss", "HH:mm[:ss]", 14자리(yyyyMMddHHmmss).
     * "25:30" 등 24h 이상 시각도 허용(분으로 환산). 실패 → null.
     */
    static Integer parseMinuteOfDay(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            int hour, min;
            if (t.contains(":")) {
                String[] parts = t.split(":");
                hour = Integer.parseInt(parts[0]);
                min = Integer.parseInt(parts[1]);
            } else if (isDigits(t)) {
                if (t.length() == 14) t = t.substring(8);        // yyyyMMddHHmmss → HHmmss
                if (t.length() >= 4) {
                    hour = Integer.parseInt(t.substring(0, 2));
                    min = Integer.parseInt(t.substring(2, 4));
                } else {
                    return null;
                }
            } else {
                return null;
            }
            if (hour < 0 || min < 0 || min > 59) return null;
            return hour * 60 + min;
        } catch (RuntimeException e) {
            log.debug("[ServiceWindow] parse fail: {}", s);
            return null;
        }
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
