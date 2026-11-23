package watoo.grd.nextroute.application.place.service;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

        // 키워드 검색은 주소 지점을 못 준다(가게 없는 주택, 동·호수가 붙은 주소는 0건). 주소로 찾히면 맨 위에 둔다.
        // 같은 건물의 가게가 이미 있으면(이름·50m) 그쪽이 더 구체적이라 주소 항목을 넣지 않는다.
        findAddress(query)
                .filter(address -> !placeDeduplicator.isDuplicate(address, merged))
                .ifPresent(address -> merged.add(0, address));

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

    /**
     * 주소 검색. 원래 검색어로 못 찾으면 끝의 동·층·호수를 떼고 한 번 더 찾는다. 실패는 결과를 비울 뿐
     * degraded 로 올리지 않는다 — 주소 항목은 덧붙이는 결과이고, 키워드 검색 결과는 그대로 유효하다.
     */
    private Optional<PlaceSearchResult.Item> findAddress(String query) {
        if (!AddressQueryTrimmer.mayBeAddress(query)) {
            return Optional.empty();
        }
        try {
            Optional<PlaceSearchResult.Item> found = kakaoLocalSearchPort.searchAddress(query);
            if (found.isPresent()) {
                return found;
            }
            String trimmed = AddressQueryTrimmer.trim(query);
            if (trimmed.isEmpty() || trimmed.equals(query.strip()) || !AddressQueryTrimmer.mayBeAddress(trimmed)) {
                return Optional.empty();
            }
            return kakaoLocalSearchPort.searchAddress(trimmed);
        } catch (Exception e) {
            String reason = e instanceof ResponseStatusException rse && rse.getReason() != null
                    ? rse.getReason()
                    : e.getMessage();
            log.warn("[PlaceSearch] 카카오 주소 검색 실패: {}", reason);
            return Optional.empty();
        }
    }

    private PlaceSearchResult.DegradedProvider degraded(String provider, Exception e) {
        String reason = e instanceof ResponseStatusException rse && rse.getReason() != null
                ? rse.getReason()
                : e.getMessage();
        log.warn("[PlaceSearch] {} 프로바이더 저하: {}", provider, reason);
        return new PlaceSearchResult.DegradedProvider(provider, reason);
    }
}
