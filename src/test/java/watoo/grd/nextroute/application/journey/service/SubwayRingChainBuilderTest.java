package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.subway.service.DestinationNormalizer;
import watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 묶인 열차 기준 링 체인(subway-express PR-S4). 운영 fixture(역 마스터·OTP 패턴, 2026-10-07)와 번들 카탈로그로 돈다.
 * 9호선 김포공항 → 중앙보훈병원은 도착 API 표기로 <b>상행</b>이다(S1).
 */
@DisplayName("링 체인 — 묶인 열차가 서는 역만, 지나치는 역은 다음 정차역 앞에")
class SubwayRingChainBuilderTest {

    private static final String LINE9 = "1009";
    private static final String UP = "상행";
    private static final List<String> ALL_STOP_GAYANG_SINNONHYEON = List.of("가양", "증미", "등촌", "염창", "신목동",
            "선유도", "당산", "국회의사당", "여의도", "샛강", "노량진", "노들", "흑석", "동작", "구반포", "신반포", "고속터미널",
            "사평", "신논현");
    private static final List<String> EXPRESS_GAYANG_SINNONHYEON = List.of("가양", "염창", "당산", "여의도", "노량진",
            "동작", "고속터미널", "신논현");

    private SubwayRingChainBuilder builder;
    private SubwayStopPatternResolver resolver;

    @BeforeEach
    void setUp() {
        SubwayStationRepository repository = mock(SubwayStationRepository.class);
        given(repository.findByLineId(anyString())).willAnswer(inv -> SubwayExpressFixtures.stations(inv.getArgument(0)));
        resolver = SubwayExpressFixtures.resolver();
        builder = new SubwayRingChainBuilder(new SubwayRingStationResolver(repository, new DestinationNormalizer()),
                resolver);
    }

    private ResolvedPattern express9600() {
        return resolver.resolve(LINE9, UP, "9600", "중앙보훈병원", null).orElseThrow();
    }

    private static String id(String name) {
        return SubwayExpressFixtures.stationId(LINE9, name);
    }

    @Test
    @DisplayName("급행: 완행 계획을 서는 역만 남기고 순번을 다시 센다. 지나치는 역은 다음 정차역의 passingBefore")
    void expressKeepsOnlyStops() {
        SubwayRingChain chain = builder.build(LINE9, UP, ALL_STOP_GAYANG_SINNONHYEON, SubwayBindingTrainKind.EXPRESS,
                express9600());

        assertThat(chain.stations()).extracting(SubwayRingChain.Station::name)
                .containsExactlyElementsOf(EXPRESS_GAYANG_SINNONHYEON);
        assertThat(chain.stations()).extracting(SubwayRingChain.Station::sequence)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(chain.at(0).passingBefore()).isEmpty();
        assertThat(chain.at(1).passingBefore()).containsExactly(id("증미"), id("등촌"));
        assertThat(chain.at(2).passingBefore()).containsExactly(id("신목동"), id("선유도"));
        assertThat(chain.at(7).passingBefore()).containsExactly(id("사평"));
        assertThat(chain.sequenceOfPassing(id("등촌"))).isEqualTo(1);
        assertThat(chain.sequenceOfPassing(id("염창"))).as("정차역은 지나치는 역이 아니다").isEqualTo(-1);
        assertThat(chain.sequenceOfKey(id("등촌"))).as("지나치는 역은 체인 역이 아니다").isEqualTo(-1);
        assertThat(chain.alightingSequence()).as("남은 역은 정차역만 센다(D9)").isEqualTo(7);
        assertThat(chain.pattern()).isNotNull();
    }

    @Test
    @DisplayName("급행: 계획이 이미 급행 정차열이어도 완행 경로에서 지나치는 역을 채운다")
    void expressFromExpressPlanFillsPassing() {
        SubwayRingChain chain = builder.build(LINE9, UP, EXPRESS_GAYANG_SINNONHYEON, SubwayBindingTrainKind.EXPRESS,
                express9600());

        assertThat(chain.stations()).extracting(SubwayRingChain.Station::name)
                .containsExactlyElementsOf(EXPRESS_GAYANG_SINNONHYEON);
        assertThat(chain.at(1).passingBefore()).containsExactly(id("증미"), id("등촌"));
    }

    @Test
    @DisplayName("일반 열차인데 계획이 급행 정차열(OTP 급행 trip) — 완행으로 펼친다(중간역이 구간 밖으로 보여 PASSED 로 뛰던 버그)")
    void normalTrainOnExpressPlanExpands() {
        SubwayRingChain chain = builder.build(LINE9, UP, EXPRESS_GAYANG_SINNONHYEON, SubwayBindingTrainKind.NORMAL,
                null);

        assertThat(chain.stations()).extracting(SubwayRingChain.Station::name)
                .containsExactlyElementsOf(ALL_STOP_GAYANG_SINNONHYEON);
        assertThat(chain.sequenceOfKey(id("등촌"))).isEqualTo(2);
        assertThat(chain.hasPassing()).isFalse();
        assertThat(chain.pattern()).isNull();
    }

    @Test
    @DisplayName("일반 열차 · 완행 계획 — 계획 체인 그대로(기존 링과 같다)")
    void normalTrainOnAllStopPlanIsUnchanged() {
        SubwayRingChain planned = builder.build(LINE9, UP, ALL_STOP_GAYANG_SINNONHYEON, null, null);
        SubwayRingChain normal = builder.build(LINE9, UP, ALL_STOP_GAYANG_SINNONHYEON, SubwayBindingTrainKind.NORMAL,
                null);

        assertThat(normal).isEqualTo(planned);
    }

    @Test
    @DisplayName("미분류(null)·모름은 계획 체인 그대로 — 예전 동작")
    void unclassifiedKeepsPlannedChain() {
        SubwayRingChain unclassified = builder.build(LINE9, UP, EXPRESS_GAYANG_SINNONHYEON, null, null);
        SubwayRingChain unknown = builder.build(LINE9, UP, EXPRESS_GAYANG_SINNONHYEON, SubwayBindingTrainKind.UNKNOWN,
                null);

        assertThat(unclassified.stations()).extracting(SubwayRingChain.Station::name)
                .containsExactlyElementsOf(EXPRESS_GAYANG_SINNONHYEON);
        assertThat(unknown).isEqualTo(unclassified);
    }

    @Test
    @DisplayName("급행이 탑승역·하차역에 서지 않거나 패턴을 모르면 빈 체인 — 링을 그리지 않는다")
    void expressNotStoppingOrUnresolvedIsEmpty() {
        List<String> fromDeungchon = ALL_STOP_GAYANG_SINNONHYEON.subList(2, ALL_STOP_GAYANG_SINNONHYEON.size());
        List<String> toSeonyudo = ALL_STOP_GAYANG_SINNONHYEON.subList(0, 6);

        assertThat(builder.build(LINE9, UP, fromDeungchon, SubwayBindingTrainKind.EXPRESS, express9600()).isUsable())
                .as("탑승 등촌").isFalse();
        assertThat(builder.build(LINE9, UP, toSeonyudo, SubwayBindingTrainKind.EXPRESS, express9600()).isUsable())
                .as("하차 선유도").isFalse();
        assertThat(builder.build(LINE9, UP, ALL_STOP_GAYANG_SINNONHYEON, SubwayBindingTrainKind.EXPRESS, null)
                .isUsable()).as("패턴 모름").isFalse();
    }

    @Test
    @DisplayName("종착역까지 자른 급행(종합운동장행)으로 중앙보훈병원까지 — 빈 체인")
    void expressCutAtTerminal() {
        ResolvedPattern toSportsComplex = resolver.resolve(LINE9, UP, "9602", "종합운동장", null).orElseThrow();
        List<String> names = List.of("고속터미널", "사평", "신논현", "언주", "선정릉", "삼성중앙", "봉은사", "종합운동장", "삼전",
                "석촌고분", "석촌", "송파나루", "한성백제", "올림픽공원", "둔촌오륜", "중앙보훈병원");

        assertThat(builder.build(LINE9, UP, names, SubwayBindingTrainKind.EXPRESS, toSportsComplex).isUsable())
                .isFalse();
        assertThat(builder.build(LINE9, UP, names.subList(0, 8), SubwayBindingTrainKind.EXPRESS, toSportsComplex)
                .stations()).extracting(SubwayRingChain.Station::name)
                .containsExactly("고속터미널", "신논현", "선정릉", "봉은사", "종합운동장");
    }
}
