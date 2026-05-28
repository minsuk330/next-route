package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 행선지/종착역명 정규화 — destination_name(이벤트) vs end_station_name(시간표) 비교용.
 *
 * <p>규칙:
 * <ul>
 *   <li>trim + 공백 제거</li>
 *   <li>끝 "행"/"역" 제거</li>
 *   <li>끝 괄호 접미사 제거 — 예: 잠실(송파구청) → 잠실</li>
 * </ul>
 *
 * 환승역/동명이역 구분은 호출자가 {@code line_id}와 함께 해석한다.
 */
@Component
public class DestinationNormalizer {

    private static final Pattern PAREN_SUFFIX = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    public String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim().replaceAll("\\s+", "");
        if (trimmed.isEmpty()) return null;

        trimmed = PAREN_SUFFIX.matcher(trimmed).replaceAll("");
        if (trimmed.endsWith("행")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("역")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 결과:
     * <ul>
     *   <li>{@link Match#KNOWN_MATCH} — 둘 다 알 수 있고 normalize 일치</li>
     *   <li>{@link Match#KNOWN_MISMATCH} — 둘 다 알 수 있고 normalize 불일치</li>
     *   <li>{@link Match#UNKNOWN} — 한쪽 이상 null/blank</li>
     * </ul>
     */
    public Match compare(String eventDestination, String timetableEndStation) {
        String a = normalize(eventDestination);
        String b = normalize(timetableEndStation);
        if (a == null || b == null) return Match.UNKNOWN;
        return a.equals(b) ? Match.KNOWN_MATCH : Match.KNOWN_MISMATCH;
    }

    public enum Match { KNOWN_MATCH, KNOWN_MISMATCH, UNKNOWN }
}
