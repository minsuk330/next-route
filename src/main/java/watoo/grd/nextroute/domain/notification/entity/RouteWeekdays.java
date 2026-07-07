package watoo.grd.nextroute.domain.notification.entity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 반복 알림 요일 집합의 bitmask ↔ {@link DayOfWeek} 변환과 "다음 도래 datetime" 계산.
 * bit0=월 … bit6=일 (=DayOfWeek.getValue()-1). mask=0 은 요일 무관(일회성).
 */
public final class RouteWeekdays {

    private RouteWeekdays() {
    }

    /** API 요일 코드(3글자 대문자). */
    private static final String[] CODES = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    public static int bit(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }

    public static boolean has(int mask, DayOfWeek day) {
        return (mask & bit(day)) != 0;
    }

    public static int toMask(Collection<DayOfWeek> days) {
        int mask = 0;
        for (DayOfWeek d : days) {
            mask |= bit(d);
        }
        return mask;
    }

    /** 코드 리스트(예: ["MON","WED"]) → mask. null/빈 = 0. */
    public static int maskFromCodes(Collection<String> codes) {
        if (codes == null) {
            return 0;
        }
        int mask = 0;
        for (String c : codes) {
            mask |= bit(parse(c));
        }
        return mask;
    }

    public static List<DayOfWeek> fromMask(int mask) {
        List<DayOfWeek> days = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (has(mask, d)) {
                days.add(d);
            }
        }
        return days;
    }

    /** mask → 코드 리스트(월→일 순). */
    public static List<String> toCodes(int mask) {
        List<String> codes = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (has(mask, d)) {
                codes.add(CODES[d.getValue() - 1]);
            }
        }
        return codes;
    }

    public static DayOfWeek parse(String code) {
        if (code == null) {
            throw new IllegalArgumentException("요일 코드가 비었습니다.");
        }
        String up = code.trim().toUpperCase();
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(up)) {
                return DayOfWeek.of(i + 1);
            }
        }
        throw new IllegalArgumentException("알 수 없는 요일 코드: " + code);
    }

    /**
     * 다음 도래 datetime 계산.
     * <ul>
     *   <li>일회성(repeat=false 또는 mask=0): 오늘 hhmm 이 now 이후면 오늘, 아니면 내일.</li>
     *   <li>반복: 오늘(d=0)부터 하루씩 증가하며 요일이 mask 에 포함 && date@hhmm > now 인 최초. d=7 까지
     *       탐색해 "선택 요일이 오늘뿐인데 시각이 지난" 경우 다음 주 같은 요일로 넘긴다.</li>
     * </ul>
     */
    public static LocalDateTime nextOccurrence(LocalDateTime now, LocalTime hhmm, int mask, boolean repeat) {
        if (!repeat || mask == 0) {
            LocalDateTime today = now.toLocalDate().atTime(hhmm);
            return today.isAfter(now) ? today : now.toLocalDate().plusDays(1).atTime(hhmm);
        }
        for (int d = 0; d <= 7; d++) {
            LocalDate date = now.toLocalDate().plusDays(d);
            if (has(mask, date.getDayOfWeek())) {
                LocalDateTime dt = date.atTime(hhmm);
                if (dt.isAfter(now)) {
                    return dt;
                }
            }
        }
        throw new IllegalStateException("다음 도래 요일을 찾을 수 없습니다(mask=" + mask + ")");
    }
}
