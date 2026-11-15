package watoo.grd.nextroute.application.regionalsubway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 커밋된 시드가 OTP 실측 역 수, 운영사 코드와 맞는지. */
class RegionalStationSeedCatalogTest {

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());

    @Test
    @DisplayName("노선별 역 수 = OTP 실측(2026-11-16), seq 는 0..n-1 연속")
    void counts() {
        Map<String, Long> counts = catalog.all().stream()
                .collect(Collectors.groupingBy(RegionalStationSeed::lineCode, Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "BS1", 40L, "BS2", 43L, "BS3", 17L, "BS4", 14L,
                "DG1", 35L, "DG2", 29L, "DG3", 30L, "DJ1", 22L, "GJ1", 20L));
        for (String line : counts.keySet()) {
            List<RegionalStationSeed> l = catalog.line(line);
            for (int i = 0; i < l.size(); i++) {
                assertThat(l.get(i).seq()).isEqualTo(i);
            }
        }
    }

    @Test
    @DisplayName("운영사 코드 예외 — 대구 중앙로 131(TAGO 13140), 부산 다대포해수욕장 95(TAGO 1195)")
    void providerCodeOverrides() {
        assertThat(catalog.findByName("DG1", "중앙로").providerStationCode()).isEqualTo("131");
        assertThat(catalog.findByProviderCode("BS1", "95").stationName()).isEqualTo("다대포해수욕장");
        assertThat(catalog.findByProviderCode("BS1", "134").stationName()).isEqualTo("노포");
        assertThat(catalog.findByName("BS1", "서면(부산1호선)").stationKey()).isEqualTo("BS1-119");
        assertThat(catalog.findByName("BS2", "서면").stationKey()).isEqualTo("BS2-219");
    }

    @Test
    @DisplayName("현재 역명을 쓴다 — OTP 개명 전 이름(큰고개) 이 아니라 동구청")
    void currentNames() {
        assertThat(catalog.findByName("DG1", "동구청")).isNotNull();
        assertThat(catalog.findByName("DG1", "큰고개")).isNull();
        assertThat(catalog.findByName("GJ1", "녹동역").seq()).isEqualTo(19);   // 소태 너머 분기 끝 = 일직선 끝
    }
}
