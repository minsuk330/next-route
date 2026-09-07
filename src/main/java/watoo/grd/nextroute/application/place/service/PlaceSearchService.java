package watoo.grd.nextroute.application.place.service;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;
import watoo.grd.nextroute.application.place.port.in.PlaceSearchUseCase;
import watoo.grd.nextroute.application.place.port.out.KakaoLocalSearchPort;
import watoo.grd.nextroute.application.place.port.out.NaverLocalSearchPort;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceSearchService implements PlaceSearchUseCase {

    private static final int NAVER_MAX_DISPLAY = 5;
    private static final int KAKAO_MAX_SIZE = 15;

    private final NaverLocalSearchPort naverLocalSearchPort;
    private final KakaoLocalSearchPort kakaoLocalSearchPort;
    private final PlaceDeduplicator placeDeduplicator;

    @Override
    public PlaceSearchResult search(String query, int limit) {
        int naverDisplay = Math.min(limit, NAVER_MAX_DISPLAY);
        int kakaoSize = Math.min(limit, KAKAO_MAX_SIZE);

        List<PlaceSearchResult.Item> naver = null;
        PlaceSearchResult.DegradedProvider naverDegraded = null;
        try {
            naver = naverLocalSearchPort.search(query, naverDisplay);
        } catch (Exception e) {
            naverDegraded = degraded("NAVER", e);
        }

        List<PlaceSearchResult.Item> kakao = null;
        PlaceSearchResult.DegradedProvider kakaoDegraded = null;
        try {
            kakao = kakaoLocalSearchPort.search(query, kakaoSize);
        } catch (Exception e) {
            kakaoDegraded = degraded("KAKAO", e);
        }

        if (naver == null && kakao == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "장소 검색 제공자에 연결하지 못했어요.");
        }

        // 조건1: 네이버 결과 전부 상단. 조건2: 중복 아닌 카카오만 뒤에 append. 조건3: 개수 제한 없음.
        List<PlaceSearchResult.Item> merged = new ArrayList<>();
        if (naver != null) {
            merged.addAll(naver);
        }
        if (kakao != null) {
            for (PlaceSearchResult.Item item : kakao) {
                if (!placeDeduplicator.isDuplicate(item, merged)) {
                    merged.add(item);
                }
            }
        }

        List<PlaceSearchResult.DegradedProvider> degraded = new ArrayList<>();
        if (naverDegraded != null) {
            degraded.add(naverDegraded);
        }
        if (kakaoDegraded != null) {
            degraded.add(kakaoDegraded);
        }
        String outcome = degraded.isEmpty() ? "full" : "partial";

        return new PlaceSearchResult(query, merged, new PlaceSearchResult.Meta(outcome, degraded));
    }

    private PlaceSearchResult.DegradedProvider degraded(String provider, Exception e) {
        String reason = e instanceof ResponseStatusException rse && rse.getReason() != null
                ? rse.getReason()
                : e.getMessage();
        log.warn("[PlaceSearch] {} 프로바이더 저하: {}", provider, reason);
        return new PlaceSearchResult.DegradedProvider(provider, reason);
    }
}
