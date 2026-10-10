package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("stale-date(#309): 만든 봉투의 progressValidUntil 을 epoch 초로 되읽는다")
    void staleDateRoundTrip() {
        SubwayRingState ring = ring();
        ObjectNode envelope = factory.contentState(JOURNEY_ID, "2호선", ring, T0);

        assertThat(SubwayRingPushPayloadFactory.staleDateSeconds(envelope))
                .isEqualTo(ring.progressValidUntil().getEpochSecond());
        assertThat(SubwayRingPushPayloadFactory.staleDateSeconds(null)).isNull();
    }

    @Test
    @DisplayName("#310 props 에 하차 예정(밀리초)이 실리고, stale-date 는 min(유효기간, 하차 예정 − 60초)")
    void alightingEtaAndStaleDate() throws Exception {
        SubwayRingState base = ring();
        Instant alighting = base.progressValidUntil().plusSeconds(30);
        ObjectNode envelope = factory.contentState(JOURNEY_ID, "2호선", base.withEstimatedAlightingAt(alighting), T0);

        assertThat(propsOf(envelope).path("estimatedAlightingAt").asLong()).isEqualTo(alighting.toEpochMilli());
        assertThat(SubwayRingPushPayloadFactory.staleDateSeconds(envelope))
                .as("하차 1분 전이 유효기간보다 이르다")
                .isEqualTo(alighting.minusSeconds(60).getEpochSecond());

        ObjectNode far = factory.contentState(JOURNEY_ID, "2호선",
                base.withEstimatedAlightingAt(base.progressValidUntil().plusSeconds(3600)), T0);
        assertThat(SubwayRingPushPayloadFactory.staleDateSeconds(far))
                .isEqualTo(base.progressValidUntil().getEpochSecond());
        assertThat(propsOf(factory.contentState(JOURNEY_ID, "2호선", base, T0)).path("estimatedAlightingAt").isNull())
                .as("모르면 null").isTrue();
    }

    private SubwayRingState ring() {
        return new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                1, "1002000223", "역삼", "삼성", 2,
                new BigDecimal("0.4500"), T0, new BigDecimal("0.90"),
                T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);
    }
}
