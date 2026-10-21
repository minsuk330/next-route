package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayTrainKindClassifierTest {

    private static final SubwayExpressCatalog CATALOG =
            SubwayExpressCatalog.of(line -> SubwayPatternFixtures.STATIONS.getOrDefault(line, List.of()));

    private final SubwayTrainKindClassifier classifier = new SubwayTrainKindClassifier(CATALOG);

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource(nullValues = "NULL", value = {
            "1065, A1031, 급행, DIRECT",
            "1065, A1001, NULL, DIRECT",
            "1065, A1056, 일반, DIRECT",   // 직통 번호면 표시와 무관
            "1065, A1057, 급행, EXPRESS",  // 범위 밖은 직통으로 짓지 않는다
            "1065, A3063, 일반, NORMAL",
            "1001, A1031, 일반, NORMAL"})  // 공항철도가 아니면 직통 아님
    @DisplayName("① 공항철도 A1001–A1056 → 직통")
    void direct(String lineId, String trainNo, String trainType, TrainKind expected) {
        assertThat(classifier.classify(lineId, trainNo, trainType, null)).isEqualTo(expected);
    }

    @Test
    @DisplayName("② ITX — trainType 'ITX' 또는 원시 directAt 9. enum 은 9 를 UNKNOWN 으로 접어 원시 코드가 필요하다")
    void itx() {
        assertThat(classifier.classify("1067", "2021", "ITX", SubwayExpressType.UNKNOWN)).isEqualTo(TrainKind.ITX);
        assertThat(classifier.classify("1067", "2021", "itx-청춘", null)).isEqualTo(TrainKind.ITX);
        assertThat(classifier.classifyRaw("1067", "2021", null, "9")).isEqualTo(TrainKind.ITX);
        assertThat(classifier.classify("1067", "2021", null, SubwayExpressType.from("9"))).isEqualTo(TrainKind.UNKNOWN);
    }

    @ParameterizedTest(name = "{0}/{1} → EXPRESS")
    @CsvSource(nullValues = "NULL", value = {
            "급행, NULL",
            "특급, NULL",
            "NULL, EXPRESS",
            "NULL, SPECIAL",
            "일반, EXPRESS"})            // 둘 중 하나라도 급행이면 급행
    @DisplayName("③ '급행'·'특급' 또는 directAt 1·7 → 급행")
    void express(String trainType, SubwayExpressType directAt) {
        assertThat(classifier.classify("1009", "9625", trainType, directAt)).isEqualTo(TrainKind.EXPRESS);
    }

    @Test
    @DisplayName("③ 원시 directAt 1·7 → 급행")
    void expressRaw() {
        assertThat(classifier.classifyRaw("1009", "9625", null, "1")).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.classifyRaw("1009", "9625", null, " 7 ")).isEqualTo(TrainKind.EXPRESS);
    }

    @Test
    @DisplayName("③' 카탈로그가 번호로 급행이라 한 열차는 실시간 '일반' 이어도 급행 — 4호선 K44xx, 1호선 A급행 서울교통공사 구간 행")
    void trainNoAuthoritativeOverridesNormal() {
        assertThat(classifier.classify("1004", "4402", "일반", SubwayExpressType.NORMAL)).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.classify("1004", "K4405", null, SubwayExpressType.NORMAL)).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.classify("1001", "1932", "일반", null)).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.classify("1004", "4302", "일반", SubwayExpressType.NORMAL)).isEqualTo(TrainKind.NORMAL);
        // 번호 권위가 없는 서비스(9호선)는 실시간 표시를 따른다
        assertThat(classifier.classify("1009", "9625", "일반", SubwayExpressType.NORMAL)).isEqualTo(TrainKind.NORMAL);
    }

    @Test
    @DisplayName("②′ 구로 급행(1호선 1401–1416)은 실시간이 '급행'이어도 일반 — 모든 역에 선다")
    void allStopExpressIsNormal() {
        assertThat(classifier.classify("1001", "1406", "급행", null)).isEqualTo(TrainKind.NORMAL);
        assertThat(classifier.classify("1001", "1401", null, SubwayExpressType.EXPRESS)).isEqualTo(TrainKind.NORMAL);
        assertThat(classifier.classify("1001", "K1413", "급행", SubwayExpressType.EXPRESS)).isEqualTo(TrainKind.NORMAL);
        assertThat(classifier.classifyRaw("1001", "1416", null, "1")).isEqualTo(TrainKind.NORMAL);
        // 범위 밖·다른 노선은 그대로
        assertThat(classifier.classify("1001", "1417", "급행", null)).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.classify("1001", "1101", "급행", null)).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.classify("1004", "1406", "급행", null)).isEqualTo(TrainKind.EXPRESS);
    }

    @Test
    @DisplayName("④ '일반' 또는 directAt 0 → 일반, ⑤ 그 밖 → 모름(일반으로 승격하지 않음)")
    void normalAndUnknown() {
        assertThat(classifier.classify("1002", "2001", "일반", null)).isEqualTo(TrainKind.NORMAL);
        assertThat(classifier.classify("1002", "2001", null, SubwayExpressType.NORMAL)).isEqualTo(TrainKind.NORMAL);
        assertThat(classifier.classifyRaw("1002", "2001", null, "0")).isEqualTo(TrainKind.NORMAL);
        assertThat(classifier.classify("1002", "2001", null, null)).isEqualTo(TrainKind.UNKNOWN);
        assertThat(classifier.classify("1002", "2001", "", SubwayExpressType.UNKNOWN)).isEqualTo(TrainKind.UNKNOWN);
        assertThat(classifier.classifyRaw("1002", "2001", "", "5")).isEqualTo(TrainKind.UNKNOWN);
    }

    @Test
    @DisplayName("카탈로그 없이도 판정은 돈다(번호 권위 단계만 빠진다)")
    void withoutCatalog() {
        SubwayTrainKindClassifier bare = new SubwayTrainKindClassifier(null);

        assertThat(bare.classify("1004", "4402", "일반", null)).isEqualTo(TrainKind.NORMAL);
        assertThat(bare.classify("1065", "A1031", "급행", null)).isEqualTo(TrainKind.DIRECT);
    }

    @Test
    @DisplayName("표시 이름")
    void labels() {
        assertThat(TrainKind.NORMAL.label()).isEqualTo("일반");
        assertThat(TrainKind.EXPRESS.label()).isEqualTo("급행");
        assertThat(TrainKind.DIRECT.label()).isEqualTo("직통");
        assertThat(TrainKind.ITX.label()).isEqualTo("ITX-청춘");
        assertThat(TrainKind.UNKNOWN.label()).isNull();
    }

    static List<RealtimeTrainSamples.Sample> samples() {
        return RealtimeTrainSamples.ALL;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("운영 실측 표본(2026-10-21)")
    void realSamples(RealtimeTrainSamples.Sample sample) {
        TrainKind kind = sample.directAt() != null
                ? classifier.classifyRaw(sample.lineId(), sample.trainNo(), sample.trainType(), sample.directAt())
                : classifier.classify(sample.lineId(), sample.trainNo(), sample.trainType(), null);

        assertThat(kind).isEqualTo(TrainKind.valueOf(sample.expectedKind()));
    }
}
