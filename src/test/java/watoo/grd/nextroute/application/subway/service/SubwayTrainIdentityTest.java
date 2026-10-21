package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayTrainIdentityTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(nullValues = "NULL", value = {
            "K1056, 1056",       // 서울 시간표 API
            "1056, 1056",        // 실시간 도착·위치 API
            "0112, 112",         // 1호선 선행 0
            "K0112, 112",
            "0067, 67",          // GTX-A 선행 0
            "' k1932 ', 1932",   // 공백·소문자
            "A1031, A1031",      // 공항철도 접두 유지
            "A01031, A1031",
            "0000, 0",
            "ITX1, ITX1",        // 모르는 모양은 지어내지 않는다
            "'', NULL",
            "NULL, NULL"})
    @DisplayName("카탈로그 비교용 번호 — K·선행 0 제거, 공항철도 A 유지")
    void catalogNumber(String raw, String expected) {
        assertThat(SubwayTrainIdentity.catalogNumber(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("저장 identity 는 원문 그대로, 비교용 번호만 정규화한다(링 조회는 원문 완전 일치)")
    void storedIdentityKeepsRawTrainNo() {
        SubwayTrainIdentity train = SubwayTrainIdentity.of("1001", "상행", "0660", "광운대");

        assertThat(train.trainNo()).isEqualTo("0660");
        assertThat(train.catalogNo()).isEqualTo("660");
    }

    @Test
    @DisplayName("숫자 번호만 값으로 읽는다")
    void numeric() {
        assertThat(SubwayTrainIdentity.numeric("112")).isEqualTo(112);
        assertThat(SubwayTrainIdentity.numeric("A1031")).isNull();
        assertThat(SubwayTrainIdentity.numeric(null)).isNull();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(nullValues = "NULL", value = {
            "1001, 상행, 상행",
            "1001, 0, 상행",
            "1001, 1, 하행",
            "1009, 0, 하행",    // 9호선 위치 API 는 반대(SubwayDirectionNormalizer)
            "1009, 1, 상행",
            "1065, 1, 상행",    // 공항철도도 반대
            "1002, 0, 내선",
            "1002, 외선, 외선",
            "1001, 2, NULL",
            "1001, NULL, NULL"})
    @DisplayName("방향은 도착 API 표기 — 위치 API 숫자는 SubwayDirectionNormalizer 규칙(9호선·공항철도 반전)")
    void direction(String lineId, String raw, String expected) {
        assertThat(SubwayTrainIdentity.direction(lineId, raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("이미 라벨인 방향은 9호선·공항철도에서도 뒤집지 않는다 — fromUpDownLine 은 라벨도 뒤집으므로 거기 넣지 않는다")
    void labelIsNotInvertedForInvertedLines() {
        assertThat(SubwayTrainIdentity.direction("1009", "하행")).isEqualTo("하행");
        assertThat(SubwayTrainIdentity.direction("1065", "상행")).isEqualTo("상행");
        // 대조: 정규화기에 라벨을 넣으면 뒤집힌다(위치 API 의미로 읽음) — 이 계약이 따로 있어야 하는 이유
        assertThat(SubwayDirectionNormalizer.fromUpDownLine("1009", "하행")).isEqualTo("상행");
        assertThat(SubwayTrainIdentity.direction(null, "0")).isNull();
        assertThat(SubwayTrainIdentity.direction(null, "하행")).isEqualTo("하행");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(nullValues = "NULL", value = {
            "서울역, 서울",
            "서울, 서울",
            "당고개행, 불암산",
            "화전, 한국항공대",
            "신촌(경의중앙선), 신촌",
            "쌍용(나사렛대), 쌍용",
            "평택지제, 지제",
            "이수, 총신대입구",
            "'  ', NULL",
            "NULL, NULL"})
    @DisplayName("행선지 — 역 마스터 정규화명 + 개명 별칭")
    void destinationAliases(String raw, String expected) {
        assertThat(SubwayTrainIdentity.destinationName(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("행선지 자리에 10자리 역 id 가 오면 terminalId 로 받는다(위치 기반 출발 기록)")
    void terminalIdInsteadOfName() {
        SubwayTrainIdentity byId = SubwayTrainIdentity.of("1001", "상행", "1902", "1001000133");
        SubwayTrainIdentity byName = SubwayTrainIdentity.of("1001", "상행", "1902", "서울역");

        assertThat(byId.terminalId()).isEqualTo("1001000133");
        assertThat(byId.destinationName()).isNull();
        assertThat(byName.terminalId()).isNull();
        assertThat(byName.destinationName()).isEqualTo("서울");
    }

    @Test
    @DisplayName("반대 방향")
    void opposite() {
        assertThat(SubwayTrainIdentity.opposite("상행")).isEqualTo("하행");
        assertThat(SubwayTrainIdentity.opposite("내선")).isEqualTo("외선");
        assertThat(SubwayTrainIdentity.opposite("?")).isNull();
    }

    static List<RealtimeTrainSamples.Sample> samples() {
        return RealtimeTrainSamples.ALL;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("운영 실측 표본(2026-10-21) — 비교용 번호와 방향")
    void realSamples(RealtimeTrainSamples.Sample sample) {
        SubwayTrainIdentity train = SubwayTrainIdentity.of(sample.lineId(), sample.direction(), sample.trainNo(),
                sample.destination());

        assertThat(train.trainNo()).isEqualTo(sample.trainNo());
        assertThat(train.catalogNo()).isEqualTo(sample.expectedCatalogNo());
        assertThat(train.direction()).isEqualTo(sample.direction());
        assertThat(train.destinationName() != null || train.terminalId() != null).isTrue();
    }
}
