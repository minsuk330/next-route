package watoo.grd.nextroute.infrastructure.adapter.out.persistence.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.mapping.MappingVerificationStatus;
import watoo.grd.nextroute.application.route.mapping.TransitIdentityKey;
import watoo.grd.nextroute.application.route.mapping.TransitIdentityMapping;
import watoo.grd.nextroute.application.route.mapping.TransitIdentityMappingPort;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * V49 표 기반 매핑 조회. 여러 provider 후보가 있으면 VERIFIED 를 우선한다 — 호출자는 여전히
 * {@link TransitIdentityMapping#isVerified()} 로 실시간 적용 여부를 최종 판단한다(REVIEW 는 STATIC).
 */
@Component
@RequiredArgsConstructor
public class JpaTransitIdentityMappingAdapter implements TransitIdentityMappingPort {

    private final TransitIdentityMappingRepository repository;

    @Override
    public Optional<TransitIdentityMapping> resolve(TransitIdentityKey key) {
        List<TransitIdentityMappingEntity> rows = repository.findMappings(
                key.dataVersion(), key.feedId(), key.agencyId(), key.sourceRouteId(),
                key.tripPattern(), key.sourceStopId(), key.occurrenceIndex());
        return rows.stream()
                // VERIFIED 먼저, 그 다음 provider 이름 순으로 안정적 선택
                .min(Comparator
                        .comparing((TransitIdentityMappingEntity e) ->
                                e.getVerificationStatus() == MappingVerificationStatus.VERIFIED ? 0 : 1)
                        .thenComparing(TransitIdentityMappingEntity::getProvider))
                .map(this::toDomain);
    }

    private TransitIdentityMapping toDomain(TransitIdentityMappingEntity e) {
        return new TransitIdentityMapping(
                e.getProvider(), e.getProviderRouteId(), e.getProviderStopId(),
                e.getProviderArsId(), e.getDirection(), e.getProviderSeq(),
                e.getVerificationStatus(), e.getDataVersion());
    }
}
