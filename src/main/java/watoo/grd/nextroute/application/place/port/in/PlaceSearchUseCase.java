package watoo.grd.nextroute.application.place.port.in;

import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;

public interface PlaceSearchUseCase {

    PlaceSearchResult search(String query, int limit);
}
