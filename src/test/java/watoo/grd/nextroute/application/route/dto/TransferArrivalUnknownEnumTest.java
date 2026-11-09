package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * search-regional-bus-wait PR-0: 다른 버전 서버가 저장한 검색 스냅샷(Redis·여정 routeSnapshot)에 이 서버가 모르는 enum 값이
 * 있어도 읽혀야 한다. 스프링 ObjectMapper(application.yaml spring.jackson 설정 포함)로 확인한다. (SCHEDULE 은 PR-1b 에서 알려진 값이 됐다.)
 */
@JsonTest
class TransferArrivalUnknownEnumTest {

    @Autowired
    ObjectMapper objectMapper;

    record Holder(List<TransferArrival> transferArrivals) {}

    @Test
    void unknownSourceAndStatusFallBackToDefaults() throws Exception {
        String json = """
                {"transferArrivals":[{"routeId":"234000026","laneIndex":0,"source":"FROM_A_NEWER_SERVER","status":"SOMETHING_NEW",
                  "waitSeconds":120}]}
                """;
        TransferArrival ta = objectMapper.readValue(json, Holder.class).transferArrivals().get(0);

        assertThat(ta.source()).isEqualTo(TransferArrival.Source.NONE);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.ERROR);
        assertThat(ta.waitSeconds()).isEqualTo(120L);
    }

    @Test
    void knownValuesAreUnchanged() throws Exception {
        String json = """
                {"transferArrivals":[{"laneIndex":0,"source":"SCHEDULE","status":"NO_VEHICLE","fallbackReason":"LIMITED"}]}
                """;
        TransferArrival ta = objectMapper.readValue(json, Holder.class).transferArrivals().get(0);

        assertThat(ta.source()).isEqualTo(TransferArrival.Source.SCHEDULE);
        assertThat(ta.fallbackReason()).isEqualTo("LIMITED");
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.NO_VEHICLE);
    }

    enum NoDefault { A, B }

    record NoDefaultHolder(NoDefault value) {}

    @Test
    void enumsWithoutADefaultStillFail() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"value\":\"C\"}", NoDefaultHolder.class))
                .isInstanceOf(InvalidFormatException.class);
    }
}
