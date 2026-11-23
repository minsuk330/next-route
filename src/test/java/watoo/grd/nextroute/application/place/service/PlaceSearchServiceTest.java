package watoo.grd.nextroute.application.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
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

    private PlaceSearchResult.Item address(String name, double lat, double lng) {
        return new PlaceSearchResult.Item("KAKAO_ADDRESS:" + name, name, "서울 송파구 올림픽로 300", "서울 송파구 올림픽로 300",
                lat, lng, "주소", List.of("KAKAO"));
    }

    @Test
    void addressFoundGoesOnTopOfKeywordResults() {
        String q = "서울 송파구 올림픽로 300";
        when(naver.search(q, 5)).thenReturn(List.of(item("NAVER", "롯데시네마 월드타워", 37.5131, 127.1041)));
        when(kakao.search(q, 15)).thenReturn(List.of());
        when(kakao.searchAddress(q)).thenReturn(Optional.of(address("롯데월드타워앤드롯데월드몰", 37.5125, 127.1025)));

        var result = service.search(q, 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::name)
                .containsExactly("롯데월드타워앤드롯데월드몰", "롯데시네마 월드타워");
        assertThat(result.items().get(0).sources()).containsExactly("KAKAO");
        assertThat(result.meta().outcome()).isEqualTo("full");
    }

    @Test
    void unitSuffixIsTrimmedWhenTheFullAddressIsNotFound() {
        String q = "서울 송파구 올림픽로 300 1001호";
        when(naver.search(q, 5)).thenReturn(List.of());
        when(kakao.search(q, 15)).thenReturn(List.of());
        when(kakao.searchAddress(q)).thenReturn(Optional.empty());
        when(kakao.searchAddress("서울 송파구 올림픽로 300"))
                .thenReturn(Optional.of(address("롯데월드타워앤드롯데월드몰", 37.5125, 127.1025)));

        var result = service.search(q, 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::name).containsExactly("롯데월드타워앤드롯데월드몰");
    }

    @Test
    void addressIsDroppedWhenTheSameBuildingIsAlreadyAPlace() {
        String q = "대전 서구 둔산로 100";
        when(naver.search(q, 5)).thenReturn(List.of(item("NAVER", "대전광역시청", 36.3504, 127.3845)));
        when(kakao.search(q, 15)).thenReturn(List.of());
        when(kakao.searchAddress(q)).thenReturn(Optional.of(address("대전광역시청", 36.3505, 127.3846)));

        var result = service.search(q, 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::key).containsExactly("NAVER:대전광역시청");
    }

    @Test
    void addressFailureKeepsKeywordResultsWithoutDegrading() {
        String q = "서울 송파구 올림픽로 300";
        when(naver.search(q, 5)).thenReturn(List.of(item("NAVER", "롯데시네마 월드타워", 37.5131, 127.1041)));
        when(kakao.search(q, 15)).thenReturn(List.of());
        when(kakao.searchAddress(q)).thenThrow(new RuntimeException("카카오 주소 타임아웃"));

        var result = service.search(q, 15);

        assertThat(result.items()).extracting(PlaceSearchResult.Item::name).containsExactly("롯데시네마 월드타워");
        assertThat(result.meta().outcome()).isEqualTo("full");
        assertThat(result.meta().degraded()).isEmpty();
    }

    @Test
    void queriesWithoutABuildingNumberSkipTheAddressLookup() {
        when(naver.search("강남역", 5)).thenReturn(List.of());
        when(kakao.search("강남역", 15)).thenReturn(List.of());

        service.search("강남역", 15);

        Mockito.verify(kakao, Mockito.never()).searchAddress(Mockito.anyString());
    }
}
