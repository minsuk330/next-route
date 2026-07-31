package watoo.grd.nextroute.application.journey.dto;

/**
 * 갈아타는 이음매 하나의 계획 값.
 *
 * <p>여정이 실제로 깨지는 곳은 대개 이음매다. 단계와 따로 세어 두면 "몇 번째 환승이 위험한가"를
 * 단계 순번을 되짚지 않고 바로 물을 수 있다.
 *
 * @param transferIndex 환승 순번. 0 부터
 * @param fromLegIndex  내리는 단계(타고 가기)의 순번
 * @param walkLegIndex  사이에 끼는 걷기 단계의 순번. 같은 정류장에서 갈아타면 null
 * @param toLegIndex    타는 단계(기다리기)의 순번
 */
public record TransferConnectionDraft(
        int transferIndex,
        int fromLegIndex,
        Integer walkLegIndex,
        int toLegIndex
) {

    /** 걷지 않는 환승인지. 0초짜리 걷기 단계를 만들지 않았다는 뜻이다. */
    public boolean isSameStop() {
        return walkLegIndex == null;
    }
}
