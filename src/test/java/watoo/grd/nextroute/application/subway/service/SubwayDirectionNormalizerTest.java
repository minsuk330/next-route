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

    /** 라벨 → wayCode. OTP 노선 이름이 방향을 들고 있어서 필요해진 방향이다. */
    @Test
    void labelBecomesWayCode() {
        assertThat(SubwayDirectionNormalizer.toWayCode("상행")).isEqualTo(1);
        assertThat(SubwayDirectionNormalizer.toWayCode("하행")).isEqualTo(2);
        assertThat(SubwayDirectionNormalizer.toWayCode("내선")).isZero();
        assertThat(SubwayDirectionNormalizer.toWayCode("외선")).isEqualTo(1);
        assertThat(SubwayDirectionNormalizer.toWayCode(" 상행 ")).isEqualTo(1);
    }

    /**
     * <b>순환선 여부를 노선 id 로 묻지 않는다 — 라벨이 이미 답이다.</b>
     *
     * <p>내·외선으로 말하면 그 자체가 순환선이라는 뜻이다. id 로 가르려 들면 "인천2호선"처럼
     * 번호만 같고 순환이 아닌 노선을 2호선(1002)으로 오인한다.
     */
    @Test
    void labelDecidesValueSpaceNotLineNumber() {
        assertThat(SubwayDirectionNormalizer.toWayCode("내선")).isZero();
        assertThat(SubwayDirectionNormalizer.toWayCode("상행")).isEqualTo(1);
    }

    /** 왕복이 성립한다 — 읽는 쪽은 노선을 알고 있으므로 같은 뜻으로 되돌아온다. */
    @Test
    void wayCodeRoundTripsPerLine() {
        assertThat(SubwayDirectionNormalizer.fromWayCode("1002",
                SubwayDirectionNormalizer.toWayCode("내선"))).isEqualTo("내선");
        assertThat(SubwayDirectionNormalizer.fromWayCode("1002",
                SubwayDirectionNormalizer.toWayCode("외선"))).isEqualTo("외선");
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001",
                SubwayDirectionNormalizer.toWayCode("상행"))).isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.fromWayCode("1001",
                SubwayDirectionNormalizer.toWayCode("하행"))).isEqualTo("하행");
    }

    /** 모르는 라벨은 null. 노선 이름에 다른 꺾쇠 표기가 생겨도 방향으로 둔갑하지 않는다. */
    @Test
    void doesNotGuessWayCodeFromUnknownLabel() {
        assertThat(SubwayDirectionNormalizer.toWayCode("급행")).isNull();
        assertThat(SubwayDirectionNormalizer.toWayCode("")).isNull();
        assertThat(SubwayDirectionNormalizer.toWayCode(null)).isNull();
    }

    @Test
    /** 9호선·공항철도는 위치 API 상·하행이 도착 API 와 반대다(2026-10-10 실측 26/26, 24/24) — 뒤집는다. */
    void invertedLines() {
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1009", "0")).isEqualTo("하행");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1009", "1")).isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1065", "0")).isEqualTo("하행");
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1065", "1")).isEqualTo("상행");
        // 한글로 와도 같은 뜻이면 같이 뒤집는다
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1009", "상행")).isEqualTo("하행");
        // 모르는 값은 여전히 null, 다른 노선은 그대로
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1009", "2")).isNull();
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1005", "0")).isEqualTo("상행");
    }

    /**
     * 6호선은 feed 에 노선이 하나뿐이라 이름에 라벨이 없다 — 승·하차역 순서로 정한다(#395). 실측(2026-10-12): 이태원 상행
     * 행선지 응암순환·하행 봉화산, 역촌·불광·구산(순환)은 모두 하행.
     */
    @Test
    void line6LabelFromStopOrder() {
        String route = "1:RR_ACC1_S-1-06-00";
        String itaewon = "1:RS_ACC1_S-1-2631";
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", itaewon, "1:RS_ACC1_S-1-2626"))
                .as("이태원 → 대흥, 응암 쪽").isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", itaewon, "1:RS_ACC1_S-1-2632"))
                .as("이태원 → 한강진, 봉화산 쪽").isEqualTo("하행");
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", "1:RS_ACC1_S-1-2626", "1:RS_ACC1_S-1-2615"))
                .as("대흥 → 연신내, 탄 역에서는 응암 쪽").isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", "1:RS_ACC1_S-1-2612", "1:RS_ACC1_S-1-2615"))
                .as("역촌 → 연신내, 순환 구간").isEqualTo("하행");
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", "1:RS_ACC1_S-1-2616", "1:RS_ACC1_S-1-2626"))
                .as("구산 → 응암 → 대흥, 순환을 돌아 나간다").isEqualTo("하행");
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", "1:RS_ACC1_S-1-2611", "1:RS_ACC1_S-1-2626"))
                .as("응암에서 타면 하행").isEqualTo("하행");
    }

    @Test
    void line6UnknownStaysNullAndOtherLinesUseLongName() {
        String route = "1:RR_ACC1_S-1-06-00";
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", null, "1:RS_ACC1_S-1-2626")).isNull();
        assertThat(SubwayDirectionNormalizer.labelFor(route, "서울6호선", "1:RS_ACC1_S-1-0201", "1:RS_ACC1_S-1-2626"))
                .as("6호선 역 id 가 아니면 모른다").isNull();
        // 다른 노선은 이름 라벨만 — 역 순서로 지어내지 않는다
        assertThat(SubwayDirectionNormalizer.labelFor("1:RR_ACC1_S-1-05-1U", "서울5호선<상행>", null, null))
                .isEqualTo("상행");
        assertThat(SubwayDirectionNormalizer.labelFor("1:BR_TAGO_X", "143", "1:RS_ACC1_S-1-2631", "1:RS_ACC1_S-1-2626"))
                .isNull();
    }

}
