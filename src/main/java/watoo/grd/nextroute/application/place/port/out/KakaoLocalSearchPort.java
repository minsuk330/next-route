package watoo.grd.nextroute.application.place.port.out;

import java.util.List;
import java.util.Optional;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;

public interface KakaoLocalSearchPort {

    List<PlaceSearchResult.Item> search(String query, int size);

    /**
     * 주소 검색(카카오 {@code address.json}). 키워드 검색은 가게·건물 이름만 찾아서, 가게가 없는 주택 주소나
     * 동·호수가 붙은 주소는 0건이 된다. 이 메서드는 그 주소 지점 하나를 돌려준다.
     *
     * <p>건물 번호까지 정확히 맞은 주소만 돌려준다. 지역(예: "역삼동")이나 도로 이름만 맞은 결과는 빈 값이다.
     */
    Optional<PlaceSearchResult.Item> searchAddress(String query);
}
