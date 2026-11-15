package watoo.grd.nextroute.application.regionalsubway.service;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원천마다 다른 시각·역명 표기를 한 규칙으로 맞춘다.
 *
 * <p>시각: {@code "5:42:00"}, {@code "05:37:15"}, {@code "24:02:00"}(대구, 부산), {@code "05:30"}(광주), {@code "053200"}(TAGO).
 * 서비스일 경계는 04:00 이다. 04:00 전 시각은 전날 서비스일의 연장이라 86400 을 더한다(기존 {@code TimetableConverter} 와 같은 규칙).
 * {@code 24:xx} 표기는 이미 연장 표기라 그대로 86400 이상이 된다.
 *
 * <p>역명: 괄호 부분, 공백, 가운뎃점(·)과 마침표, 끝의 '역' 을 뺀다. 시드 생성 도구(tools/regional-subway/build_stations.py)와 같은 규칙.
 * 예 {@code "대곡(정부대구청사)"} → {@code "대곡"}, {@code "평동역"} → {@code "평동"}, {@code "경성대·부경대"} → {@code "경성대부경대"}.
 */
public final class RegionalTimetableText {

    public static final int SERVICE_DAY_START_SECONDS = 4 * 3600;
    private static final Pattern CLOCK = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern COMPACT = Pattern.compile("^\\s*(\\d{2})(\\d{2})(\\d{2})\\s*$");
    private static final Pattern PAREN = Pattern.compile("\\((.*?)\\)");

    private RegionalTimetableText() {
    }

    /** 시각 표기를 서비스일 기준 초로. 해석할 수 없으면(빈 값, {@code "-"}, {@code "0"}) empty. */
    public static OptionalInt serviceSeconds(String raw) {
        if (raw == null) {
            return OptionalInt.empty();
        }
        String text = PAREN.matcher(raw).replaceAll("").trim();
        int h;
        int m;
        int s;
        Matcher clock = CLOCK.matcher(text);
        Matcher compact = COMPACT.matcher(text);
        if (clock.find()) {
            h = Integer.parseInt(clock.group(1));
            m = Integer.parseInt(clock.group(2));
            s = clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3));
        } else if (compact.matches()) {
            h = Integer.parseInt(compact.group(1));
            m = Integer.parseInt(compact.group(2));
            s = Integer.parseInt(compact.group(3));
        } else {
            return OptionalInt.empty();
        }
        if (m > 59 || s > 59 || h > 29) {
            return OptionalInt.empty();
        }
        int seconds = h * 3600 + m * 60 + s;
        return OptionalInt.of(seconds < SERVICE_DAY_START_SECONDS ? seconds + 86400 : seconds);
    }

    /** {@code "23:45:00(이곡)"} 의 괄호 안 종착역. 없으면 null. */
    public static String parenthesized(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = PAREN.matcher(raw);
        return m.find() ? m.group(1).trim() : null;
    }

    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String n = PAREN.matcher(name).replaceAll("");
        n = n.replaceAll("[\\s·.]", "");
        if (n.endsWith("역") && n.length() > 2) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /** 표시용 HH:mm (24 시 넘는 값은 다음 날 시각으로). */
    public static OptionalInt minuteOfDay(int serviceSeconds) {
        return OptionalInt.of((serviceSeconds / 60) % (24 * 60));
    }
}
