package watoo.grd.nextroute.application.place.port.out;

import java.util.List;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;

public interface KakaoLocalSearchPort {

    List<PlaceSearchResult.Item> search(String query, int size);
}
