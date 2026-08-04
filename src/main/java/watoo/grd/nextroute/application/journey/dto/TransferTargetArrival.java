package watoo.grd.nextroute.application.journey.dto;

/**
 * 갈아탈 차량 예측 결과와, 그것을 어떻게 얻었는지에 관한 부속 정보.
 *
 * <p>{@link CurrentLegArrival} 만으로 부족한 값이 둘이다.
 * <ul>
 *   <li><b>어느 모델이</b> 추정했는지 — 모델을 바꿨을 때 예측 품질을 견주려면 필요하다</li>
 *   <li>답을 못 준 이유가 <b>막힘</b>인지 — 근거가 없는 것과 provider 가 막힌 것은 다르다.
 *       막힌 상황에서 30초마다 다시 부르면 막힌 문을 계속 두드리는 셈이다</li>
 * </ul>
 *
 * <p>둘 다 사용자에게 보내는 값이 아니고 저장·운영 판단용이다.
 *
 * @param arrival         도착 예측. 근거가 없으면 {@code CurrentLegArrival.unknown()}
 * @param modelVersion    모델이 추정한 경우의 모델 이름. 실시간이거나 모르면 null
 * @param upstreamBlocked provider 차단·호출 한도로 조회 자체를 못 했는지
 */
public record TransferTargetArrival(
        CurrentLegArrival arrival,
        String modelVersion,
        boolean upstreamBlocked
) {

    public static TransferTargetArrival unknown() {
        return new TransferTargetArrival(CurrentLegArrival.unknown(), null, false);
    }

    /** 조회는 됐지만 근거가 없어 모르는 경우와, 막혀서 못 물어본 경우를 가른다. */
    public static TransferTargetArrival blocked() {
        return new TransferTargetArrival(CurrentLegArrival.unknown(), null, true);
    }

    public static TransferTargetArrival of(CurrentLegArrival arrival) {
        return new TransferTargetArrival(arrival, null, false);
    }
}
