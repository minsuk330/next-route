package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 키가 "같은 입력이면 같다"를 지키는지 본다.
 *
 * <p>이 성질이 깨지면 두 방향으로 틀린다. 같은 입력에 다른 키가 나오면 캐시가 상시 miss 가 되고,
 * 다른 입력에 같은 키가 나오면 <b>다른 차량의 예측을 답으로 준다.</b>
 */
@DisplayName("ML 예측 캐시 키")
class MlFeatureCacheKeyTest {

    @Test
    void TC_키_순서만_다르면_같은_키() {
        Map<String, Object> inserted = new LinkedHashMap<>();
        inserted.put("route_id", "R1");
        inserted.put("target_seq", 12);
        inserted.put("gps_x", 127.01);

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("gps_x", 127.01);
        reversed.put("target_seq", 12);
        reversed.put("route_id", "R1");

        assertThat(MlFeatureCacheKey.of(inserted)).isEqualTo(MlFeatureCacheKey.of(reversed));
    }

    @Test
    void TC_map_구현체가_달라도_같은_키() {
        Map<String, Object> hash = new HashMap<>(Map.of("route_id", "R1", "target_seq", 12));
        Map<String, Object> tree = new TreeMap<>(Map.of("route_id", "R1", "target_seq", 12));

        assertThat(MlFeatureCacheKey.of(hash)).isEqualTo(MlFeatureCacheKey.of(tree));
    }

    @Test
    void TC_값_하나만_달라도_다른_키() {
        Map<String, Object> base = new HashMap<>(Map.of("route_id", "R1", "current_section_order", 3));
        Map<String, Object> moved = new HashMap<>(Map.of("route_id", "R1", "current_section_order", 4));

        assertThat(MlFeatureCacheKey.of(base)).isNotEqualTo(MlFeatureCacheKey.of(moved));
    }

    @Test
    void TC_관측시각이_바뀌면_키가_바뀐다() {
        // 상류 위치 캐시가 갱신되면 시간 feature 가 바뀌고, 그래서 키가 저절로 죽는다.
        Map<String, Object> before = new HashMap<>(Map.of("minute_of_day", 600, "route_id", "R1"));
        Map<String, Object> after = new HashMap<>(Map.of("minute_of_day", 601, "route_id", "R1"));

        assertThat(MlFeatureCacheKey.of(before)).isNotEqualTo(MlFeatureCacheKey.of(after));
    }

    @Test
    void TC_null_값이_있어도_안정적() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("route_id", "R1");
        withNull.put("section_progress", null);

        String first = MlFeatureCacheKey.of(withNull);

        assertThat(first).isNotBlank();
        assertThat(MlFeatureCacheKey.of(withNull)).isEqualTo(first);
    }

    @Test
    void TC_null_값과_빈문자열은_구분되지_않는다() {
        // 알려진 성질이다. feature 는 숫자·route_id 뿐이고 빈 문자열 route_id 는 없으므로 무해하다.
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("section_progress", null);
        Map<String, Object> withBlank = new HashMap<>();
        withBlank.put("section_progress", "");

        assertThat(MlFeatureCacheKey.of(withNull)).isEqualTo(MlFeatureCacheKey.of(withBlank));
    }
}
