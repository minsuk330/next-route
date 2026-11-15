package watoo.grd.nextroute.application.regionalsubway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalTimetableTextTest {

    @Test
    @DisplayName("원천마다 다른 시각 표기를 서비스일 초로 — 04:00 전과 24:xx 는 86400 이상")
    void serviceSeconds() {
        assertThat(RegionalTimetableText.serviceSeconds("5:42:00")).hasValue(5 * 3600 + 42 * 60);        // 대구
        assertThat(RegionalTimetableText.serviceSeconds("05:37:15")).hasValue(5 * 3600 + 37 * 60 + 15);  // 부산
        assertThat(RegionalTimetableText.serviceSeconds("05:30")).hasValue(5 * 3600 + 30 * 60);           // 광주
        assertThat(RegionalTimetableText.serviceSeconds("053200")).hasValue(5 * 3600 + 32 * 60);          // TAGO
        assertThat(RegionalTimetableText.serviceSeconds("24:02:00")).hasValue(86400 + 2 * 60);            // 대구 막차
        assertThat(RegionalTimetableText.serviceSeconds("00:16:50")).hasValue(86400 + 16 * 60 + 50);      // 부산2 막차
        assertThat(RegionalTimetableText.serviceSeconds("23:45:00(이곡)")).hasValue(23 * 3600 + 45 * 60);
    }

    @Test
    @DisplayName("해석할 수 없는 값은 비운다 — 대구 '-', TAGO '0', 빈 값")
    void unparseable() {
        assertThat(RegionalTimetableText.serviceSeconds("-")).isEmpty();
        assertThat(RegionalTimetableText.serviceSeconds("0")).isEmpty();
        assertThat(RegionalTimetableText.serviceSeconds("")).isEmpty();
        assertThat(RegionalTimetableText.serviceSeconds(null)).isEmpty();
    }

    @Test
    @DisplayName("역명 정규화 — 괄호, 가운뎃점, 끝 '역' 제거(시드 도구와 같은 규칙)")
    void normalizeName() {
        assertThat(RegionalTimetableText.normalizeName("대곡(정부대구청사)")).isEqualTo("대곡");
        assertThat(RegionalTimetableText.normalizeName("평동역")).isEqualTo("평동");
        assertThat(RegionalTimetableText.normalizeName("경성대·부경대")).isEqualTo("경성대부경대");
        assertThat(RegionalTimetableText.normalizeName("경성대.부경대(동명대학교)")).isEqualTo("경성대부경대");
        assertThat(RegionalTimetableText.parenthesized("23:45:00(이곡)")).isEqualTo("이곡");
        assertThat(RegionalTimetableText.parenthesized("23:45:00")).isNull();
    }
}
