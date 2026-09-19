package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-B stage4: 후보의 적용 재료에 중간 하차 지점을 실을 수 있어야 하되, <b>이미 저장된 행이
 * 그대로 읽혀야</b> 한다. 저장 포맷이 계약이므로 왕복을 고정한다.
 */
class RemainingPathApplyOriginTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("옛 저장 JSON(origin 칸 없음)은 origin=null 로 읽힌다 — 옛 뜻(leg 경계 교체) 그대로")
    void legacyJsonWithoutOriginStillReads() throws Exception {
        String stored = """
                {"type":"REMAINING_PATH","fromLegIndex":3,"path":null}""";

        RerouteCandidateApply apply = objectMapper.readValue(stored, RerouteCandidateApply.class);

        assertThat(apply).isInstanceOf(RerouteCandidateApply.RemainingPathApply.class);
        RerouteCandidateApply.RemainingPathApply remaining =
                (RerouteCandidateApply.RemainingPathApply) apply;
        assertThat(remaining.fromLegIndex()).isEqualTo(3);
        assertThat(remaining.origin()).isNull();
    }

    @Test
    @DisplayName("origin 을 실으면 서울 좌표계 그대로 왕복한다")
    void originRoundTrips() throws Exception {
        RerouteCandidateApply.RemainingPathApply apply = new RerouteCandidateApply.RemainingPathApply(
                3, null, new RerouteOrigin(4, "STOP-A", "ROUTE-1", 7, RerouteOriginKind.RIDE_INTERIOR));

        String json = objectMapper.writeValueAsString(apply);
        RerouteCandidateApply parsed = objectMapper.readValue(json, RerouteCandidateApply.class);

        RerouteOrigin origin = ((RerouteCandidateApply.RemainingPathApply) parsed).origin();
        assertThat(origin.legIndex()).isEqualTo(4);
        assertThat(origin.stopId()).isEqualTo("STOP-A");
        assertThat(origin.routeId()).isEqualTo("ROUTE-1");
        assertThat(origin.seq()).isEqualTo(7);
        assertThat(origin.kind()).isEqualTo(RerouteOriginKind.RIDE_INTERIOR);
    }

    @Test
    @DisplayName("type 이름은 계약이다 — 클래스명이 아니라 REMAINING_PATH 로 적힌다")
    void typeNameStaysPinned() throws Exception {
        String json = objectMapper.writeValueAsString(
                new RerouteCandidateApply.RemainingPathApply(0, null, null));

        assertThat(objectMapper.readTree(json).path("type").asText()).isEqualTo("REMAINING_PATH");
    }

    @Test
    @DisplayName("파생 메서드가 봉투에 칸을 만들지 않는다 — isRealtimeAddressable 는 저장되지 않는다")
    void derivedFlagIsNotPersisted() throws Exception {
        String json = objectMapper.writeValueAsString(
                new RerouteOrigin(4, "STOP-A", "ROUTE-1", 7, RerouteOriginKind.TRANSFER_WAIT));

        assertThat(objectMapper.readTree(json).has("realtimeAddressable")).isFalse();
    }
}
