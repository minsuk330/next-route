package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationNormalizerTest {

    private final DestinationNormalizer normalizer = new DestinationNormalizer();

    @Test
    void normalize_strips_trailing_행() {
        assertThat(normalizer.normalize("한강진행")).isEqualTo("한강진");
    }

    @Test
    void normalize_strips_trailing_역() {
        assertThat(normalizer.normalize("한강진역")).isEqualTo("한강진");
    }

    @Test
    void normalize_strips_parenthesis_suffix() {
        assertThat(normalizer.normalize("잠실(송파구청)")).isEqualTo("잠실");
    }

    @Test
    void normalize_collapses_whitespace() {
        assertThat(normalizer.normalize("  한강 진  ")).isEqualTo("한강진");
    }

    @Test
    void normalize_returns_null_for_blank() {
        assertThat(normalizer.normalize(null)).isNull();
        assertThat(normalizer.normalize("")).isNull();
        assertThat(normalizer.normalize("   ")).isNull();
    }

    @Test
    void compare_both_unknown_returns_UNKNOWN() {
        assertThat(normalizer.compare(null, null)).isEqualTo(DestinationNormalizer.Match.UNKNOWN);
        assertThat(normalizer.compare(null, "한강진")).isEqualTo(DestinationNormalizer.Match.UNKNOWN);
        assertThat(normalizer.compare("한강진", null)).isEqualTo(DestinationNormalizer.Match.UNKNOWN);
    }

    @Test
    void compare_normalized_equality_returns_KNOWN_MATCH() {
        assertThat(normalizer.compare("한강진행", "한강진역"))
                .isEqualTo(DestinationNormalizer.Match.KNOWN_MATCH);
    }

    @Test
    void compare_normalized_mismatch_returns_KNOWN_MISMATCH() {
        assertThat(normalizer.compare("한강진행", "응암"))
                .isEqualTo(DestinationNormalizer.Match.KNOWN_MISMATCH);
    }
}
