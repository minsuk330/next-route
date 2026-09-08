package watoo.grd.nextroute.application.route.mapping;

/**
 * provider(서울 실시간) 쪽 식별과 검증 정보. VERIFIED 인 매핑에만 실시간/ML 호환 필드를 채운다.
 *
 * @param provider        공급자 태그(예: SEOUL_BUS)
 * @param providerRouteId 서울 route_id(= busLocalBlID 계열)
 * @param providerStopId  서울 stop_id
 * @param providerArsId   서울 ARS id(있으면)
 * @param direction       방향(있으면)
 * @param providerSeq     경유순번(있으면)
 */
public record TransitIdentityMapping(
        String provider,
        String providerRouteId,
        String providerStopId,
        String providerArsId,
        String direction,
        Integer providerSeq,
        MappingVerificationStatus verificationStatus,
        String dataVersion
) {
    public boolean isVerified() {
        return verificationStatus == MappingVerificationStatus.VERIFIED;
    }
}
