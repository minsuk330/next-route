package watoo.grd.nextroute.application.route.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class BusTimeParser {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter FMT_14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter FMT_17 = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter FMT_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_SPACE_DOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");

    private BusTimeParser() {}

    /**
     * mkTm / dataTm 문자열 → Instant(Asia/Seoul ���석).
     * 지원 ���맷: 14자리 숫자, 17자리 숫자, "yyyy-MM-dd HH:mm:ss[.S]".
     */
    public static Optional<Instant> parse(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String t = s.trim();
        if (t.length() == 14 && isDigits(t)) return tryParse(t, FMT_14);
        if (t.length() == 17 && isDigits(t)) return tryParse(t, FMT_17);
        if (t.contains("-")) {
            if (t.contains(".")) return tryParse(t, FMT_SPACE_DOT);
            return tryParse(t, FMT_SPACE);
        }
        return Optional.empty();
    }

    private static boolean isDigits(String s) {
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static Optional<Instant> tryParse(String s, DateTimeFormatter fmt) {
        try {
            return Optional.of(LocalDateTime.parse(s, fmt).atZone(SEOUL).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
