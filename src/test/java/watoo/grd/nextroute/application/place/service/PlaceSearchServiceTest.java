package watoo.grd.nextroute.application.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;
import watoo.grd.nextroute.application.place.port.out.KakaoLocalSearchPort;
import watoo.grd.nextroute.application.place.port.out.NaverLocalSearchPort;

class PlaceSearchServiceTest {

    private NaverLocalSearchPort naver;
    private KakaoLocalSearchPort kakao;
    private PlaceSearchService service;

    @BeforeEach
    void setUp() {
        naver = Mockito.mock(NaverLocalSearchPort.class);
        kakao = Mockito.mock(KakaoLocalSearchPort.class);
        service = new PlaceSearchService(naver, kakao, new PlaceDeduplicator());
    }

    private PlaceSearchResult.Item item(String source, String name, double lat, double lng) {
        return new PlaceSearchResult.Item(source + ":" + name, name, "addr", null, lat, lng, "cat", List.of(source));
    }

    @Test
    void naverFirstThenNonDuplicateKakaoAppended() {
        when(naver.search("강남", 5)).thenReturn(List.of(item("NAVER", "스타벅스 강남역점", 37.4979, 127.0276)));
        when(kakao.search("강남", 15)).thenReturn(List.of(
                item("KAKAO", "스타벅스(강남역점)", 37.4980, 127.0277), // 중복 → 버림
                item("KAKAO", "투썸 강남역점", 37.4970, 127.0280)       // 신규 → 유지
        ));

        var result = service.search("강남", 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::name)
                .containsExactly("스타벅스 강남역점", "투썸 강남역점");
        assertThat(result.items().get(0).sources()).containsExactly("NAVER");
        assertThat(result.meta().outcome()).isEqualTo("full");
        assertThat(result.meta().degraded()).isEmpty();
    }

    @Test
    void naverFailureFallsBackToKakaoWithDegraded() {
        when(naver.search("강남", 5)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "네이버 다운"));
        when(kakao.search("강남", 15)).thenReturn(List.of(item("KAKAO", "투썸 강남역점", 37.497, 127.028)));

        var result = service.search("강남", 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::name).containsExactly("투썸 강남역점");
        assertThat(result.meta().outcome()).isEqualTo("partial");
        assertThat(result.meta().degraded()).extracting(PlaceSearchResult.DegradedProvider::provider)
                .containsExactly("NAVER");
    }

    @Test
    void kakaoFailureReturnsNaverWithDegraded() {
        when(naver.search("강남", 5)).thenReturn(List.of(item("NAVER", "스타벅스 강남역점", 37.4979, 127.0276)));
        when(kakao.search("강남", 15)).thenThrow(new RuntimeException("카카오 타임아웃"));

        var result = service.search("강남", 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::name).containsExactly("스타벅스 강남역점");
        assertThat(result.meta().outcome()).isEqualTo("partial");
        assertThat(result.meta().degraded()).extracting(PlaceSearchResult.DegradedProvider::provider)
                .containsExactly("KAKAO");
    }

    @Test
    void bothFailuresThrow503() {
        when(naver.search("강남", 5)).thenThrow(new RuntimeException("네이버 다운"));
        when(kakao.search("강남", 15)).thenThrow(new RuntimeException("카카오 다운"));

        assertThatThrownBy(() -> service.search("강남", 15))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void limitClampedPerProvider() {
        when(naver.search("강남", 5)).thenReturn(List.of());
        when(kakao.search("강남", 15)).thenReturn(List.of());

        service.search("강남", 100);

        Mockito.verify(naver).search("강남", 5);
        Mockito.verify(kakao).search("강남", 15);
    }
}
