package watoo.grd.nextroute.application.place.service;

import java.util.List;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;

/**
 * 두 프로바이더(네이버·카카오) 결과의 중복 판정. 같은 장소가 상호명 표기 미세차(띄어쓰기/괄호)만
 * 다르게 오는 경우가 잦아, 정규화한 상호명이 같고 좌표가 근접(≤50m)하면 같은 장소로 본다.
 */
@Component
public class PlaceDeduplicator {

    private static final double DUP_RADIUS_METERS = 50d;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    /** candidate 가 existing 중 하나와 같은 장소면 true. existing 은 우선순위가 높은 쪽(네이버)이다. */
    public boolean isDuplicate(PlaceSearchResult.Item candidate, List<PlaceSearchResult.Item> existing) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        String candidateName = normalize(candidate.name());
        if (candidateName.isEmpty()) {
            return false;
        }
        for (PlaceSearchResult.Item item : existing) {
            if (candidateName.equals(normalize(item.name()))
                    && distanceMeters(candidate, item) <= DUP_RADIUS_METERS) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^0-9a-z가-힣]", "");
    }

    private static double distanceMeters(PlaceSearchResult.Item a, PlaceSearchResult.Item b) {
        double lat1 = a.latitude();
        double lon1 = a.longitude();
        double lat2 = b.latitude();
        double lon2 = b.longitude();
        if (Double.isNaN(lat1) || Double.isNaN(lon1) || Double.isNaN(lat2) || Double.isNaN(lon2)) {
            return Double.MAX_VALUE;
        }
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(h)));
    }
}
