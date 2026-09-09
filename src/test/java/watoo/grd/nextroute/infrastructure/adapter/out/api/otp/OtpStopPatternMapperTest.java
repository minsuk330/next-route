package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.routing.StopPattern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** OTP stop.patterns JSON → StopPattern 순수 매핑 계약. */
class OtpStopPatternMapperTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OtpStopPatternMapper mapper = new OtpStopPatternMapper();

    private List<StopPattern> mapFixture() throws IOException {
        var root = om.readTree(new ClassPathResource("otp/stopPatterns-success.json")
                .getContentAsString(StandardCharsets.UTF_8));
        return mapper.map(root);
    }

    @Test
    @DisplayName("route/shortName/stops 를 매핑하고 정차 순서를 그대로 보존한다")
    void mapsPatternsPreservingStopOrder() throws IOException {
        List<StopPattern> patterns = mapFixture();

        assertThat(patterns).hasSize(2);
        StopPattern first = patterns.get(0);
        assertThat(first.routeGtfsId()).isEqualTo("1:BR_TAGO_SEB100100022");
        assertThat(first.routeShortName()).isEqualTo("143");
        // 순서 보존이 방향 판정(승차 index < 하차 index)의 근거다.
        assertThat(first.stopGtfsIds()).containsExactly(
                "1:BS_TAGO_SEB101000041", "1:BS_TAGO_SEB101000048", "1:BS_TAGO_SEB101000059");
        // 반대 방향 pattern 은 stops 순서가 뒤집혀 있다.
        assertThat(patterns.get(1).stopGtfsIds()).containsExactly(
                "1:BS_TAGO_SEB101000059", "1:BS_TAGO_SEB101000041");
    }

    @Test
    @DisplayName("directionId 는 KTDB feed 상 -1(방향 무의미) — 원천값을 그대로 옮긴다")
    void keepsDirectionIdAsIs() throws IOException {
        assertThat(mapFixture()).allSatisfy(p -> assertThat(p.directionId()).isEqualTo(-1));
    }

    @Test
    @DisplayName("stop 이 없으면(모르는 id) 빈 목록")
    void unknownStopIsEmpty() throws IOException {
        var root = om.readTree("{\"data\":{\"stop\":null}}");
        assertThat(mapper.map(root)).isEmpty();
    }
}
