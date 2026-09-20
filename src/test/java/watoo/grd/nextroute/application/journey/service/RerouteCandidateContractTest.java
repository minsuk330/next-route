package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateSummary;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/**
 * 후보 계약을 코드로 못 박는다. <b>③④⑤가 같은 것을 보는지</b> 확인하는 시험이다.
 *
 * <p>이 PR 은 후보를 만들지도 적용하지도 않는다. 그래서 계약이 틀렸다면 ⑤를 구현할 때에야
 * 드러난다 — 그때는 이미 세 PR 이 각자의 해석 위에 올라가 있다. 여기서 미리 깨뜨린다.
 */
@DisplayName("재탐색 후보 계약")
class RerouteCandidateContractTest {

    /**
     * 시각 모듈을 등록한 매퍼. 저장 경로가 쓰는 것(Spring Boot 자동 구성)과 같은 능력을 갖춘다 —
     * 모듈 없이 {@link Instant} 를 직렬화하면 왕복이 조용히 다른 값이 된다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());

    // ── 적용 재료가 왕복을 견디는가 ──────────────────────────────────────────

    @Nested
    @DisplayName("적용 재료는 jsonb 왕복을 견딘다")
    class ApplyRoundTrip {

        @Test
        @DisplayName("REMAINING_PATH 의 경로 원본으로 왕복 뒤에도 여정을 쪼갤 수 있다")
        void remainingPathSurvivesRoundTrip() throws Exception {
            PathResult original = samplePath();
            int expectedLegs = compiler.compile(original).legs().size();

            RerouteCandidateApply restored = roundTrip(
                    new RerouteCandidateApply.RemainingPathApply(2, original));

            assertThat(restored).isInstanceOf(RerouteCandidateApply.RemainingPathApply.class);
            RerouteCandidateApply.RemainingPathApply remaining =
                    (RerouteCandidateApply.RemainingPathApply) restored;
            assertThat(remaining.fromLegIndex()).isEqualTo(2);
            // 여기가 깨지면 ⑤가 후보를 적용할 수 없다.
            assertThat(compiler.compile(remaining.path()).legs())
                    .as("왕복한 경로 원본으로도 같은 수의 단계가 나와야 한다")
                    .hasSize(expectedLegs);
        }

        private RerouteCandidateApply roundTrip(RerouteCandidateApply apply) throws Exception {
            return objectMapper.readValue(
                    objectMapper.writeValueAsString(apply), RerouteCandidateApply.class);
        }
    }

    // ── 종류를 적는 이름이 계약인가 ──────────────────────────────────────────

    @Nested
    @DisplayName("종류를 적는 이름")
    class Discriminator {

        @Test
        @DisplayName("클래스 이름이 아니라 고정한 문자열이 JSON 에 박힌다")
        void namesArePinnedNotDerivedFromClass() throws Exception {
            String remainingPath = objectMapper.writeValueAsString(
                    new RerouteCandidateApply.RemainingPathApply(0, samplePath()));

            assertThat(remainingPath).contains("\"type\":\"REMAINING_PATH\"");
            // 클래스명이 새어 나오면 클래스를 옮기는 순간 저장된 행을 못 읽는다.
            assertThat(remainingPath).doesNotContain("RemainingPathApply");
        }

        @Test
        @DisplayName("이름 셋이 같은 문자열이다 - DB check·enum·JSON")
        void nameMatchesKindEnum() throws Exception {
            String json = objectMapper.writeValueAsString(
                    new RerouteCandidateApply.RemainingPathApply(0, samplePath()));

            assertThat(json)
                    .contains("\"type\":\"" + RerouteProposalKind.REMAINING_PATH.name() + "\"");
        }

        @Test
        @DisplayName("폐기된 ROUTE_OPTION 봉투는 이제 모르는 종류다 — 옛 행은 조용히 적용되지 않는다")
        void retiredRouteOptionTypeIsRejected() {
            // ③ 이 만들던 후보다(journey-reroute-nextstop PR-5 에서 제거). 남아 있는 옛 행을
            // 읽어 적용하면 사라진 뜻으로 여정을 바꾸게 된다 — 거절이 맞다.
            String json = """
                    {"type":"ROUTE_OPTION","waitLegIndex":3,"routeOptionIndex":1}
                    """;

            assertThatThrownBy(() -> objectMapper.readValue(json, RerouteCandidateApply.class))
                    .isInstanceOf(InvalidTypeIdException.class);
        }

        @Test
        @DisplayName("모르는 종류는 역직렬화에서 터진다")
        void unknownTypeIsRejected() {
            String json = """
                    {"type":"TELEPORT","fromLegIndex":0}
                    """;

            // null 로 삼키면 ⑤가 빈 후보를 적용하려 든다.
            assertThatThrownBy(() -> objectMapper.readValue(json, RerouteCandidateApply.class))
                    .isInstanceOf(InvalidTypeIdException.class);
        }
    }

    // ── 후보의 두 겹이 유지되는가 ────────────────────────────────────────────

    @Nested
    @DisplayName("후보는 두 겹이다")
    class TwoLayers {

        @Test
        @DisplayName("적용 재료 없는 후보는 만들어지지 않는다")
        void candidateWithoutApplyIsRejected() {
            String json = """
                    {"candidateId":"c1","kind":"ROUTE_OPTION","display":{"totalDurationSec":600}}
                    """;

            assertThatThrownBy(() -> objectMapper.readValue(json, RerouteCandidate.class))
                    .isInstanceOf(ValueInstantiationException.class)
                    .hasMessageContaining("apply");
        }

        @Test
        @DisplayName("화면 요약으로 바꾸면 적용 재료가 사라진다")
        void summaryDropsApply() throws Exception {
            RerouteCandidate candidate = new RerouteCandidate("c1", RerouteProposalKind.REMAINING_PATH,
                    new RerouteCandidateApply.RemainingPathApply(0, samplePath()), sampleDisplay());

            String stored = objectMapper.writeValueAsString(candidate);
            String exposed = objectMapper.writeValueAsString(RerouteCandidateSummary.from(candidate));

            assertThat(stored).contains("subPaths");
            // 문자열 수준으로 본다 - 필드가 하나 늘어도 이 단언이 잡는다.
            assertThat(exposed)
                    .as("적용 재료가 API 로 새어 나가면 안 된다")
                    .doesNotContain("\"apply\"")
                    .doesNotContain("\"path\"")
                    .doesNotContain("subPaths");
            assertThat(exposed).contains("\"candidateId\":\"c1\"").contains("totalDurationSec");
        }
    }

    // ── 봉투 ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("후보 목록 봉투")
    class Envelope {

        @Test
        @DisplayName("version 과 candidates 를 감싼 모양이 유지된다")
        void envelopeSurvivesRoundTrip() throws Exception {
            RerouteCandidateEnvelope envelope = RerouteCandidateEnvelope.of(List.of(
                    new RerouteCandidate("c1", RerouteProposalKind.REMAINING_PATH,
                            new RerouteCandidateApply.RemainingPathApply(1, samplePath()),
                            sampleDisplay())));

            String json = objectMapper.writeValueAsString(envelope);
            RerouteCandidateEnvelope restored =
                    objectMapper.readValue(json, RerouteCandidateEnvelope.class);

            assertThat(json).contains("\"version\":1").contains("\"candidates\":[");
            assertThat(restored.isCurrentVersion()).isTrue();
            assertThat(restored.candidates()).singleElement()
                    .satisfies(c -> assertThat(c.candidateId()).isEqualTo("c1"));
        }

        @Test
        @DisplayName("모르는 version 은 읽는 쪽이 가려낼 수 있다")
        void unknownVersionIsDetectable() throws Exception {
            String json = """
                    {"version":99,"candidates":[]}
                    """;

            RerouteCandidateEnvelope restored =
                    objectMapper.readValue(json, RerouteCandidateEnvelope.class);

            assertThat(restored.isCurrentVersion()).isFalse();
        }
    }

    // ── 픽스처 ───────────────────────────────────────────────────────────────

    /** {@link JourneyCompilerTest} 와 같은 픽스처 도구를 쓴다. */
    private static PathResult samplePath() {
        SubPathResult[] subPaths = {
                walk(5).names("집", "정류장A").build(),
                bus(20).names("정류장A", "정류장F")
                        .busStops("ST-A", "ST-F")
                        .lanes(busLane("370", "BL-370")).build(),
                walk(3).names("정류장F", "회사").build()
        };
        return new PathResult(1, null, List.of(subPaths), List.of());
    }

    private static RerouteCandidateDisplay sampleDisplay() {
        return new RerouteCandidateDisplay(
                Instant.parse("2026-08-11T09:00:00Z"), 1_500, 240, 1, 300,
                "REALTIME", "HIGH", Instant.parse("2026-08-11T08:35:00Z"),
                List.of("370 버스 탑승"));
    }
}
