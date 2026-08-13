package watoo.grd.nextroute.application.subway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.subway.service.SubwayLineIdMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집 대상 노선 설정의 계약.
 *
 * <p>여기서 막으려는 사고는 하나다 — <b>한 노선만 조용히 빠지는 것.</b> 호선명을 모르는 값을
 * 적어 두면 그 노선은 영원히 안 불리는데, 나머지가 정상으로 보여 아무도 눈치채지 못한다.
 * 지하철 안내가 특정 노선에서만 안 되는 상태는 로그를 뒤지기 전까지 드러나지 않는다.
 */
class SubwayPositionPropertiesTest {

    @Test
    @DisplayName("설정한 노선은 모두 호선명으로 바뀐다 — 안 바뀌면 그 노선은 영원히 안 불린다")
    void everyConfiguredLineResolvesToAName() {
        List<String> configured = configuredLines();

        assertThat(configured).isNotEmpty();
        assertThat(configured)
                .allSatisfy(lineId -> assertThat(SubwayLineIdMapper.toLineName(lineId))
                        .as("line_id %s", lineId)
                        .isPresent());
    }

    /**
     * 설정 파일에서 직접 읽는다.
     *
     * <p>목록을 시험에 베껴 두면 설정만 고쳤을 때 시험은 그대로 통과한다 — 정작 잡아야 할
     * 사고가 그 순간에 생기는데도.
     */
    private List<String> configuredLines() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        List<String> lines = new ArrayList<>();
        for (int i = 0; ; i++) {
            String value = properties.getProperty("realtime.subway.position.lines[" + i + "]");
            if (value == null) {
                return lines;
            }
            lines.add(value);
        }
    }

    @Test
    @DisplayName("숫자 노선과 이름 노선이 모두 제 이름을 찾는다")
    void resolvesBothNumberedAndNamedLines() {
        assertThat(SubwayLineIdMapper.toLineName("1001")).contains("1호선");
        assertThat(SubwayLineIdMapper.toLineName("1009")).contains("9호선");
        assertThat(SubwayLineIdMapper.toLineName("1063")).contains("경의중앙선");
        assertThat(SubwayLineIdMapper.toLineName("1032")).contains("GTX-A");
    }

    @Test
    @DisplayName("호선명을 모르는 값은 대상에서 빠진다 — 부팅은 막지 않는다")
    void dropsLinesThatCannotBeResolved() {
        SubwayPositionProperties properties = new SubwayPositionProperties();
        properties.setLines(List.of("1002", "9999", "1075"));

        properties.validateLines();

        // 부팅을 막지 않는 이유: 이 기능은 기본이 off 이고, 노선 하나의 오타로 서버가 못 뜨는
        // 것은 대가가 더 크다.
        assertThat(properties.getLines()).containsExactly("1002", "1075");
    }

    @Test
    @DisplayName("전부 정상이면 목록을 건드리지 않는다")
    void keepsTheListWhenEveryLineResolves() {
        List<String> configured = configuredLines();
        SubwayPositionProperties properties = new SubwayPositionProperties();
        properties.setLines(configured);

        properties.validateLines();

        assertThat(properties.getLines()).isEqualTo(configured);
    }

    @Test
    @DisplayName("기본값은 비어 있다 — 켜더라도 노선을 적기 전에는 아무것도 부르지 않는다")
    void defaultsToNoLines() {
        SubwayPositionProperties properties = new SubwayPositionProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getLines()).isEmpty();
    }
}
