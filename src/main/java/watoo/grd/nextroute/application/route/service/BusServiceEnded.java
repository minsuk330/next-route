package watoo.grd.nextroute.application.route.service;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 서울 버스 도착 응답으로 "이 정류장에서 이 노선은 오늘 운행이 끝났다"를 판정한다(#400).
 *
 * <p>근거는 도착 메시지다 — 1번째가 {@value #ENDED} 이고 2번째도 {@value #ENDED} 이거나 비어 있어야 한다. 2번째에 차가 있으면
 * 1번째 메시지만 어긋난 것이라 끝난 게 아니다.
 *
 * <p><b>첫차 전은 끝난 게 아니다.</b> 새벽에는 첫차 전에도 {@value #ENDED} 가 나온다. 그래서 정류장의 첫차 시각
 * ({@code firstTm}, 정류장마다 {@code yyyyMMddHHmmss}, 2026-10-13 실측)이 지금보다 뒤이고 사용자가 정류장에 닿는 시각이 그 첫차
 * 이후면 곧 다닌다고 본다. 자정 넘은 검색(00:30, 첫차 04:35)은 사용자가 첫차 전에 닿으므로 끝난 것으로 본다. 첫차 시각을 못 읽으면
 * 메시지만 믿는다(기점은 10자리 같은 깨진 값이 온다).
 */
final class BusServiceEnded {

    static final String ENDED = "운행종료";

    private static final DateTimeFormatter FIRST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private BusServiceEnded() {
    }

    /**
     * @param now    지금(검색 시작 시각)
     * @param userAt 사용자가 그 정류장에 닿는 예상 시각
     */
    static boolean isEnded(BusArrivalInfo arrival, Instant now, Instant userAt) {
        if (arrival == null || !ENDED.equals(trim(arrival.arrivalMsg1()))) {
            return false;
        }
        String second = trim(arrival.arrivalMsg2());
        if (second != null && !second.isEmpty() && !ENDED.equals(second)) {
            return false;
        }
        LocalTime first = firstTime(arrival.firstBusTime());
        if (first == null || now == null || userAt == null) {
            return true;
        }
        Instant firstAt = userAt.atZone(ClockConfig.KST).toLocalDate().atTime(first)
                .atZone(ClockConfig.KST).toInstant();
        boolean startsBeforeUser = now.isBefore(firstAt) && !userAt.isBefore(firstAt);
        return !startsBeforeUser;
    }

    private static LocalTime firstTime(String raw) {
        String value = trim(raw);
        if (value == null || value.length() != 14) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, FIRST_TIME).toLocalTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
