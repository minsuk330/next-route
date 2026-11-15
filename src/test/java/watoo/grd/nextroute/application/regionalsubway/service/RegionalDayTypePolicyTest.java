package watoo.grd.nextroute.application.regionalsubway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalDayTypePolicyTest {

    /** 특일정보 API 이름 그대로(2026-11 ~ 2027-03 실측 이름). */
    private static final Map<LocalDate, String> NAMES = Map.of(
            LocalDate.of(2026, 10, 3), "개천절",
            LocalDate.of(2027, 2, 6), "설날",
            LocalDate.of(2027, 2, 9), "대체공휴일(설날)",
            LocalDate.of(2026, 11, 18), "임시공휴일");

    private static final HolidayCalendar CAL = new HolidayCalendar() {
        @Override public boolean isHoliday(LocalDate date) { return NAMES.containsKey(date); }
        @Override public Optional<String> holidayName(LocalDate date) { return Optional.ofNullable(NAMES.get(date)); }
    };

    private static RegionalDayType at(String line, int y, int m, int d) {
        return RegionalDayTypePolicy.resolve(line, LocalDate.of(y, m, d), CAL);
    }

    @Test
    @DisplayName("명절은 광주만 MH, 다른 노선은 공휴일표 — 토요일 설날(2027-02-06)도 MH/HO, 대체공휴일(설날)도 명절")
    void lunarHoliday() {
        assertThat(at("GJ1", 2027, 2, 6)).isEqualTo(RegionalDayType.MH);
        assertThat(at("GJ1", 2027, 2, 9)).isEqualTo(RegionalDayType.MH);
        assertThat(at("DG1", 2027, 2, 6)).isEqualTo(RegionalDayType.HO);
        assertThat(at("BS1", 2027, 2, 9)).isEqualTo(RegionalDayType.HO);
    }

    @Test
    @DisplayName("공휴일이 토요일이면 공휴일표(개천절 2026-10-03), 평일 공휴일도 공휴일표, 일요일 공휴일표, 토요일 토요일표")
    void order() {
        assertThat(at("BS1", 2026, 10, 3)).isEqualTo(RegionalDayType.HO);
        assertThat(at("GJ1", 2026, 10, 3)).isEqualTo(RegionalDayType.HO);
        assertThat(at("DG2", 2026, 11, 18)).isEqualTo(RegionalDayType.HO);   // 수요일 공휴일
        assertThat(at("DG2", 2026, 11, 21)).isEqualTo(RegionalDayType.SA);
        assertThat(at("DG2", 2026, 11, 22)).isEqualTo(RegionalDayType.HO);
        assertThat(at("DG2", 2026, 11, 16)).isEqualTo(RegionalDayType.WD);
    }

    @Test
    @DisplayName("대체 순서: 대전 토요일 → 공휴일표(공식이 한 표), 명절 → 공휴일표, 다른 노선 토요일은 대체 없음")
    void candidates() {
        assertThat(RegionalDayTypePolicy.candidates("DJ1", RegionalDayType.SA)).containsExactly(RegionalDayType.SA, RegionalDayType.HO);
        assertThat(RegionalDayTypePolicy.candidates("DG1", RegionalDayType.SA)).containsExactly(RegionalDayType.SA);
        assertThat(RegionalDayTypePolicy.candidates("GJ1", RegionalDayType.MH)).containsExactly(RegionalDayType.MH, RegionalDayType.HO);
        assertThat(RegionalDayTypePolicy.candidates("BS1", RegionalDayType.WD)).isEqualTo(List.of(RegionalDayType.WD));
    }
}
