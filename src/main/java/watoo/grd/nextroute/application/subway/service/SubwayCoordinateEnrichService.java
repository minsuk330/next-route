package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.kakao.dto.KakaoSearchResult;
import watoo.grd.nextroute.application.kakao.port.out.KakaoApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayCoordinateEnrichService {

    private final SubwayDataService subwayDataService;
    private final KakaoApiPort kakaoApiPort;

    public record EnrichResult(int total, int updated, int skipped) {}

    @Transactional
    public EnrichResult enrich() {
        List<SubwayStation> targets = subwayDataService.findAllWithoutCoordinates();
        int updated = 0;
        int skipped = 0;

        for (SubwayStation station : targets) {
            if (station.getKakaoQuery() == null || station.getKakaoQuery().isBlank()) {
                skipped++;
                continue;
            }

            try {
                KakaoSearchResult result = kakaoApiPort.searchSubwayStation(station.getKakaoQuery());
                if (result.places().isEmpty()) {
                    log.warn("[Enrich] 결과 없음: {}", station.getKakaoQuery());
                    skipped++;
                    continue;
                }

                KakaoSearchResult.Place place = result.places().get(0);
                subwayDataService.updateCoordinates(station.getId(), place.y(), place.x());
                log.debug("[Enrich] 완료: {} → ({}, {})", station.getKakaoQuery(), place.y(), place.x());
                updated++;

                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Enrich] 인터럽트 발생, 중단");
                break;
            } catch (Exception e) {
                log.warn("[Enrich] 실패: {} - {}", station.getKakaoQuery(), e.getMessage());
                skipped++;
            }
        }

        log.info("[Enrich] 완료 - total={}, updated={}, skipped={}", targets.size(), updated, skipped);
        return new EnrichResult(targets.size(), updated, skipped);
    }
}
