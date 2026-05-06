package watoo.grd.nextroute.application.kakao.dto;

import java.util.List;

public record KakaoSearchResult(
        List<Place> places
) {
    public record Place(
            String id,
            String placeName,
            double x,
            double y
    ) {}
}
