package watoo.grd.nextroute.infrastructure.adapter.out.persistence.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.application.route.mapping.MappingVerificationStatus;
import watoo.grd.nextroute.common.entity.BaseEntity;

/** V49 transit_identity_mapping 행. GTFS(OTP)↔서울 공급자 식별 매핑(읽기 모델). */
@Entity
@Table(name = "transit_identity_mapping")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransitIdentityMappingEntity extends BaseEntity {

    @Column(name = "data_version", nullable = false)
    private String dataVersion;

    @Column(name = "feed_id", nullable = false)
    private String feedId;

    @Column(name = "agency_id", nullable = false)
    private String agencyId;

    @Column(name = "source_route_id", nullable = false)
    private String sourceRouteId;

    @Column(name = "trip_pattern")
    private String tripPattern;

    @Column(name = "source_stop_id", nullable = false)
    private String sourceStopId;

    @Column(name = "source_stop_sequence")
    private Integer sourceStopSequence;

    @Column(name = "occurrence_index", nullable = false)
    private int occurrenceIndex;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_route_id", nullable = false)
    private String providerRouteId;

    @Column(name = "provider_stop_id", nullable = false)
    private String providerStopId;

    @Column(name = "provider_ars_id")
    private String providerArsId;

    @Column(name = "direction")
    private String direction;

    @Column(name = "provider_seq")
    private Integer providerSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private MappingVerificationStatus verificationStatus;

    @Column(name = "evidence")
    private String evidence;
}
