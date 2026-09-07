package watoo.grd.nextroute.application.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;

class PlaceDeduplicatorTest {

    private final PlaceDeduplicator deduplicator = new PlaceDeduplicator();

    private PlaceSearchResult.Item item(String name, double lat, double lng) {
        return new PlaceSearchResult.Item("k", name, "addr", null, lat, lng, "cat", List.of("KAKAO"));
    }

    @Test
    void sameNameWithMarkupSpacingAndNearbyCoordinatesIsDuplicate() {
        var naver = List.of(item("스타벅스 강남역점", 37.4979, 127.0276));
        // 카카오는 띄어쓰기/괄호 표기가 미세하게 다르고 좌표는 ~20m 차이
        var candidate = item("스타벅스(강남역점)", 37.4980, 127.0277);

        assertThat(deduplicator.isDuplicate(candidate, naver)).isTrue();
    }

    @Test
    void sameNameButFarApartIsNotDuplicate() {
        var naver = List.of(item("스타벅스", 37.4979, 127.0276));
        // 이름 같아도 좌표 ~2km 차이(다른 지점)면 중복 아님
        var candidate = item("스타벅스", 37.5100, 127.0400);

        assertThat(deduplicator.isDuplicate(candidate, naver)).isFalse();
    }

    @Test
    void differentNameAtSameSpotIsNotDuplicate() {
        var naver = List.of(item("스타벅스 강남역점", 37.4979, 127.0276));
        var candidate = item("투썸플레이스 강남역점", 37.4979, 127.0276);

        assertThat(deduplicator.isDuplicate(candidate, naver)).isFalse();
    }

    @Test
    void emptyExistingIsNeverDuplicate() {
        assertThat(deduplicator.isDuplicate(item("스타벅스", 37.5, 127.0), List.of())).isFalse();
    }
}
