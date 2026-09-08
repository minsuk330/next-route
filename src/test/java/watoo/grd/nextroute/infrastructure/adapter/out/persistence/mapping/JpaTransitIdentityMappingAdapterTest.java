package watoo.grd.nextroute.infrastructure.adapter.out.persistence.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.mapping.MappingVerificationStatus;
import watoo.grd.nextroute.application.route.mapping.TransitIdentityKey;
import watoo.grd.nextroute.application.route.mapping.TransitIdentityMapping;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaTransitIdentityMappingAdapterTest {

    private final TransitIdentityMappingRepository repo = mock(TransitIdentityMappingRepository.class);
    private final JpaTransitIdentityMappingAdapter adapter = new JpaTransitIdentityMappingAdapter(repo);

    private final TransitIdentityKey key = new TransitIdentityKey(
            "d1", "1", "1:A1", "1:R", null, "1:S1", null, 0);

    private TransitIdentityMappingEntity row(String provider, MappingVerificationStatus status) {
        return TransitIdentityMappingEntity.builder()
                .dataVersion("d1").feedId("1").agencyId("1:A1").sourceRouteId("1:R")
                .sourceStopId("1:S1").occurrenceIndex(0)
                .provider(provider).providerRouteId("SR-" + provider).providerStopId("SS-" + provider)
                .providerArsId("ARS1").direction("UP").providerSeq(3)
                .verificationStatus(status).build();
    }

    @Test
    @DisplayName("미매핑 → empty (호출자는 STATIC 처리)")
    void unmapped() {
        when(repo.findMappings(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        assertThat(adapter.resolve(key)).isEmpty();
    }

    @Test
    @DisplayName("여러 후보 → VERIFIED 우선 선택 + 필드 매핑")
    void prefersVerified() {
        when(repo.findMappings(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        row("SEOUL_BUS_A", MappingVerificationStatus.REVIEW),
                        row("SEOUL_BUS_B", MappingVerificationStatus.VERIFIED)));
        Optional<TransitIdentityMapping> m = adapter.resolve(key);
        assertThat(m).isPresent();
        assertThat(m.get().provider()).isEqualTo("SEOUL_BUS_B");
        assertThat(m.get().isVerified()).isTrue();
        assertThat(m.get().providerStopId()).isEqualTo("SS-SEOUL_BUS_B");
        assertThat(m.get().providerArsId()).isEqualTo("ARS1");
    }

    @Test
    @DisplayName("REVIEW 만 있으면 조회는 되지만 isVerified=false → 실시간 미적용(STATIC)")
    void reviewOnlyNotVerified() {
        when(repo.findMappings(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row("SEOUL_BUS_A", MappingVerificationStatus.REVIEW)));
        Optional<TransitIdentityMapping> m = adapter.resolve(key);
        assertThat(m).isPresent();
        assertThat(m.get().isVerified()).isFalse();
    }
}
