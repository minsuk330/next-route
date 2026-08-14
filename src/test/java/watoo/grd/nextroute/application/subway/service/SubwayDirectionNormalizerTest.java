package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방향 정규화의 계약.
 *
 * <p>여기서 지키려는 것은 하나다 — <b>서울 {@code updnLine} 을 ODsay {@code wayCode} 규칙으로
 * 읽지 않는다.</b> 둘은 숫자와 뜻의 대응이 반대라, 섞이면 반대 방향 열차를 고르고 ETA 와 탑승
 * 판정이 그럴듯하게 틀린다.
 */
class SubwayDirectionNormalizerTest {

    @Test
    void mapsSeoulUpDownLineToArrivalLabels() {
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", "0")).isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", "1")).isEqualTo("하행");
    }

    /** 2호선은 상·하행이 아니라 내·외선으로 말한다. */
    @Test
    void circularLineUsesInnerAndOuterLabels() {
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1002", "0")).isEqualTo("내선");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1002", "1")).isEqualTo("외선");
    }

    /**
     * ODsay wayCode 규칙과 <b>겹치지 않는다</b>.
     *
     * <p>ODsay 는 {@code wayCode == 1} 이 상행이다. 서울 {@code updnLine} 은 {@code 0} 이 상행이다.
     * 같은 숫자가 반대 뜻이므로, 이 값을 뒤집어 읽는 순간 모든 판정이 반대 방향으로 간다.
     */
    @Test
    void doesNotFollowOdsayWayCodeConvention() {
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", "1")).isNotEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1002", "0")).isNotEqualTo("외선");
    }

    /** 이미 한글 표기로 오는 응답은 왕복시키지 않고 그대로 통과시킨다. */
    @Test
    void passesThroughLabelsThatAreAlreadyNormalized() {
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", "상행")).isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1002", "외선")).isEqualTo("외선");
    }

    /** 모르는 값에서 방향을 지어내지 않는다. 반대 방향 열차를 고르는 것보다 모름이 낫다. */
    @Test
    void doesNotGuessDirectionFromUnknownValues() {
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", "2")).isNull();
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", "")).isNull();
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1001", null)).isNull();
    }

    // ── ODsay wayCode ────────────────────────────────────────────────────

    @Test
    void mapsOdsayWayCodeToArrivalLabels() {
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001", 1)).isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001", 2)).isEqualTo("하행");
    }

    @Test
    void circularLineWayCodeUsesInnerAndOuterLabels() {
        assertThat(SubwayDirectionNormalizer.fromWayCode("1002", 0)).isEqualTo("내선");
        assertThat(SubwayDirectionNormalizer.fromWayCode("1002", 1)).isEqualTo("외선");
    }

    /**
     * <b>허용값이 노선별로 다르다.</b> 2호선은 {@code 0/1}, 그 외는 {@code 1/2} 다.
     *
     * <p>둘을 {@code updnLine} 처럼 {@code 0/1} 하나로 묶으면 <b>하행(2)이 통째로 죽고</b>
     * 2호선에서는 {@code 0} 이 상행으로 읽힌다. 두 실패 모두 조용하다.
     */
    @Test
    void wayCodeWhitelistDiffersPerLine() {
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001", 0)).isNull();
        assertThat(SubwayDirectionNormalizer.fromWayCode("1002", 2)).isNull();
    }

    /** 모르는 값에서 방향을 지어내지 않는다 — 예전 구현은 무엇이 오든 하행을 만들어 냈다. */
    @Test
    void doesNotGuessDirectionFromUnknownWayCode() {
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001", 99)).isNull();
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001", -1)).isNull();
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001", null)).isNull();
    }
}
