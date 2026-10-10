package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.SubwayRingHoldReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live Activity {@code content-state} 의 <b>모양</b> 계약.
 *
 * <p>여기서 막는 사고는 전부 <b>조용하다</b> — 앱이 디코딩에 실패해도 실패 응답이 오지 않으므로
 * 서버 쪽에서는 정상으로 보이고, 사용자만 멈춘 화면을 본다.
 *
 * <ul>
 *   <li>봉투가 한 겹이면 갱신이 통째로 무시된다</li>
 *   <li>{@code props} 를 객체로 넣으면 역시 무시된다 — 문자열이어야 한다</li>
 *   <li>시각 단위가 뒤집히면 1000배 어긋난 채 그럴듯해 보인다</li>
 *   <li>non-optional 필드에 null 이 들어가면 디코딩이 실패한다</li>
 * </ul>
 */
@DisplayName("Live Activity content-state 계약")
class SubwayRingPushPayloadFactoryTest {

    private static final Instant T0 = Instant.parse("2026-08-15T09:00:00Z");
    private static final Long JOURNEY_ID = 501L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubwayRingPushPayloadFactory factory =
            new SubwayRingPushPayloadFactory(objectMapper);

    @Test
    @DisplayName("봉투는 두 겹이고 props 는 JSON 문자열이다 — 한 겹이면 앱이 조용히 무시한다")
    void contentStateIsATwoLayerEnvelope() throws Exception {
        ObjectNode envelope = factory.contentState(JOURNEY_ID, "2호선", ring(), T0.plusSeconds(5));

        assertThat(envelope.get("name").asText()).isEqualTo("NavigationLiveActivity");
        JsonNode props = envelope.get("props");
        assertThat(props.isTextual())
                .as("props 는 객체가 아니라 문자열이어야 한다")
                .isTrue();
        // 문자열 안이 다시 JSON 이다.
        assertThat(objectMapper.readTree(props.asText()).isObject()).isTrue();
    }

    @Test
    @DisplayName("props 의 시각은 전부 epoch 밀리초다 — aps.timestamp(초)와 축이 다르다")
    void everyTimeInPropsIsMillis() throws Exception {
        JsonNode props = propsOf(factory.contentState(
                JOURNEY_ID, "2호선", ring(), T0.plusSeconds(5)));

        assertThat(props.get("progressBaseAt").asLong()).isEqualTo(T0.toEpochMilli());
        assertThat(props.get("estimatedNextStationAt").asLong())
                .isEqualTo(T0.plusSeconds(90).toEpochMilli());
        assertThat(props.get("progressValidUntil").asLong())
                .isEqualTo(T0.plusSeconds(120).toEpochMilli());
        assertThat(props.get("updatedAt").asLong()).isEqualTo(T0.plusSeconds(5).toEpochMilli());
        // 초로 잘못 넣으면 이 자릿수가 무너진다.
        assertThat(props.get("progressBaseAt").asLong()).isGreaterThan(1_000_000_000_000L);
    }

    @Test
    @DisplayName("앱이 non-optional 로 받는 필드에 null 이 없다")
    void requiredFieldsAreNeverNull() throws Exception {
        JsonNode props = propsOf(factory.contentState(JOURNEY_ID, "2호선", ring(), T0));

        for (String field : new String[]{"journeyId", "phase", "routeName", "nextStationName",
                "alightingStationName", "segmentProgress", "progressBaseAt", "progressTarget",
                "progressValidUntil", "quality", "updatedAt"}) {
            assertThat(props.get(field))
                    .as("%s 가 비어 있으면 앱 디코딩이 통째로 실패한다", field)
                    .isNotNull();
            assertThat(props.get(field).isNull()).as("%s", field).isFalse();
        }
    }

    @Test
    @DisplayName("도착 예정이 없으면 null 로 내려간다 — 만들어 내지 않는다")
    void missingEstimateStaysNull() throws Exception {
        SubwayRingState frozen = new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                1, "1002000223", "역삼", "삼성", 2,
                new BigDecimal("0.4500"), T0, new BigDecimal("0.90"),
                null, T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.STALE, T0, T0);

        JsonNode props = propsOf(factory.contentState(JOURNEY_ID, "2호선", frozen, T0));

        assertThat(props.get("estimatedNextStationAt").isNull()).isTrue();
        assertThat(props.get("quality").asText()).isEqualTo("STALE");
    }

    private JsonNode propsOf(ObjectNode envelope) throws Exception {
        return objectMapper.readTree(envelope.get("props").asText());
    }

    @Test
    @DisplayName("#310 props 에 하차 예정(밀리초)이 실린다, 모르면 null")
    void alightingEtaInProps() throws Exception {
        Instant alighting = T0.plusSeconds(400);
        assertThat(propsOf(factory.contentState(JOURNEY_ID, "2호선", ring().withEstimatedAlightingAt(alighting), T0))
                .path("estimatedAlightingAt").asLong()).isEqualTo(alighting.toEpochMilli());
        assertThat(propsOf(factory.contentState(JOURNEY_ID, "2호선", ring(), T0)).path("estimatedAlightingAt").isNull())
                .isTrue();
    }

    @Test
    @DisplayName("props 에 예정 출처·하차 예정 출처가 스냅샷과 같은 이름으로 실린다, 없으면 null")
    void etaSourceInProps() throws Exception {
        JsonNode sourced = propsOf(factory.contentState(JOURNEY_ID, "2호선",
                ring().withSource(SubwayRingEtaSource.TYPICAL, SubwayRingStage.RUN)
                        .withEstimatedAlightingAt(T0.plusSeconds(400), SubwayRingEtaSource.TYPICAL), T0));
        JsonNode plain = propsOf(factory.contentState(JOURNEY_ID, "2호선", ring(), T0));

        assertThat(sourced.path("etaSource").asText()).isEqualTo("TYPICAL");
        assertThat(sourced.path("alightingEtaSource").asText()).isEqualTo("TYPICAL");
        assertThat(plain.path("etaSource").isNull()).isTrue();
        assertThat(plain.path("alightingEtaSource").isNull()).isTrue();
        assertThat(sourced.has("stage")).as("단계는 내부 전용이다").isFalse();
        assertThat(sourced.has("sourceSeenAt")).isFalse();
    }

    @Test
    @DisplayName("props 에 멈춤 이유·정차 역이 스냅샷과 같은 이름으로 실린다, 없으면 null")
    void holdInProps() throws Exception {
        JsonNode stopped = propsOf(factory.contentState(JOURNEY_ID, "2호선",
                ring().withHold(SubwayRingHoldReason.PREV_STATION_STOPPED, "신사"), T0));
        JsonNode moving = propsOf(factory.contentState(JOURNEY_ID, "2호선", ring(), T0));

        assertThat(stopped.path("holdReason").asText()).isEqualTo("PREV_STATION_STOPPED");
        assertThat(stopped.path("holdStationName").asText()).isEqualTo("신사");
        assertThat(moving.path("holdReason").isNull()).isTrue();
        assertThat(moving.path("holdStationName").isNull()).isTrue();
    }

    /**
     * stale-date 는 앱 규칙(JourneyGuidanceLiveActivity.swift redrawAt)과 같다. 링: 다음 역 T0+90, 유효기간 T0+120.
     */
    @Nested
    @DisplayName("stale-date — 앱과 같은 규칙")
    class StaleDate {

        @Test
        @DisplayName("다음 역 도착 예정이 가장 이르면 그때 — 늦는 열차에서 링이 꽉 찼다가 되돌아가지 않게")
        void nextStationFirst() {
            assertThat(staleAt(ring(), T0)).isEqualTo(T0.plusSeconds(90).getEpochSecond());
        }

        @Test
        @DisplayName("다음 역 예정이 지났으면 그다음으로 이른 미래 시각(유효기간)")
        void pastCandidatesAreSkipped() {
            assertThat(staleAt(ring(), T0.plusSeconds(100))).isEqualTo(T0.plusSeconds(120).getEpochSecond());
        }

        @Test
        @DisplayName("하차 1분 전이 더 이르면 그때 — '곧 도착'")
        void alightingLeadWins() {
            SubwayRingState ring = ring().withEstimatedAlightingAt(T0.plusSeconds(170));

            assertThat(staleAt(ring, T0.plusSeconds(100))).isEqualTo(T0.plusSeconds(110).getEpochSecond());
        }

        @Test
        @DisplayName("하차 1분 전이 이미 지났으면 넣지 않는다 — 하차 직전 push 가 바로 낡은 화면이 되면 안 된다")
        void pastAlightingLeadIsSkipped() {
            SubwayRingState ring = ring().withEstimatedAlightingAt(T0.plusSeconds(140));

            assertThat(staleAt(ring, T0.plusSeconds(100))).as("하차 1분 전 T0+80 은 지났다")
                    .isEqualTo(T0.plusSeconds(120).getEpochSecond());
            assertThat(staleAt(ring, T0.plusSeconds(130))).as("셋 다 지났다").isNull();
        }

        @Test
        @DisplayName("링이 STALE 이면 넣지 않는다 — 이미 멈춘 화면이다")
        void staleRingHasNone() {
            SubwayRingState frozen = new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                    1, "1002000223", "역삼", "삼성", 2,
                    new BigDecimal("0.4500"), T0, new BigDecimal("0.4500"),
                    null, T0.plusSeconds(120),
                    SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.STALE, T0, T0);

            assertThat(staleAt(frozen, T0)).isNull();
            assertThat(SubwayRingPushPayloadFactory.staleDateSeconds(null, 0L)).isNull();
        }

        private Long staleAt(SubwayRingState ring, Instant now) {
            return SubwayRingPushPayloadFactory.staleDateSeconds(
                    factory.contentState(JOURNEY_ID, "2호선", ring, T0), now.toEpochMilli());
        }
    }

    private SubwayRingState ring() {
        return new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                1, "1002000223", "역삼", "삼성", 2,
                new BigDecimal("0.4500"), T0, new BigDecimal("0.90"),
                T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);
    }

    @Test
    @DisplayName("하차 출구가 있으면 alightingExitNo 로 싣고, 없으면 키를 넣지 않는다")
    void alightingExitNo() throws Exception {
        assertThat(propsOf(factory.contentState(JOURNEY_ID, "2호선", "3", ring(), T0)).path("alightingExitNo").asText())
                .isEqualTo("3");
        assertThat(propsOf(factory.contentState(JOURNEY_ID, "2호선", null, ring(), T0)).has("alightingExitNo"))
                .isFalse();
        assertThat(propsOf(factory.contentState(JOURNEY_ID, "2호선", " ", ring(), T0)).has("alightingExitNo"))
                .isFalse();
    }

}
