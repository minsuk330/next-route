package watoo.grd.nextroute.application.place.dto;

import java.util.List;

public record PlaceSearchResult(
        String query,
        List<Item> items,
        Meta meta
) {
    public record Item(
            String key,
            String name,
            String address,
            String roadAddress,
            double latitude,
            double longitude,
            String category,
            List<String> sources
    ) {
    }

    public record Meta(String outcome, List<DegradedProvider> degraded) {
    }

    public record DegradedProvider(String provider, String reason) {
    }
}
