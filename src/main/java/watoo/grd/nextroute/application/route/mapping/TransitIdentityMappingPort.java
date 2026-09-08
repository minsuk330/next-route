package watoo.grd.nextroute.application.route.mapping;

import java.util.Optional;

/**
 * GTFS(OTP) 식별 → 서울 실시간/ML 공급자 식별 매핑 조회.
 *
 * <p>OTP leg 는 GTFS stop/route id 를 들고 있는데(예: {@code 1:BS_TAGO_...}), 서울 실시간 API 는
 * 별도 ID(ARS/localStationID/route_id)를 쓴다. 이 포트가 둘을 잇는다. <b>매핑이 없거나 REVIEW 면
 * 실시간을 붙이지 않고 STATIC_SCHEDULE 로 둔다</b> — 잘못된 매핑이 실시간을 오염시키지 않게.
 *
 * <p>버전별 immutable 매핑을 읽는 읽기 전용 모델이다. 활성 snapshot 이 참조하는 dataVersion 은
 * 덮어쓰지 않는다(신규 버전으로만 교체).
 */
public interface TransitIdentityMappingPort {

    /** 미매핑이면 empty → 호출자는 STATIC 으로 처리. */
    Optional<TransitIdentityMapping> resolve(TransitIdentityKey key);
}
