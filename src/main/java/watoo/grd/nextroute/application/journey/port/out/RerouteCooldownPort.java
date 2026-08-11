package watoo.grd.nextroute.application.journey.port.out;

/**
 * 같은 환승에서 재탐색을 너무 자주 하지 않게 막는다.
 *
 * <p>이 PR 은 방아쇠를 세기만 하지만(shadow) cooldown 은 지금부터 적용한다. <b>cooldown 을 뺀
 * 발생률은 ③에서 실제로 나갈 외부 호출 수와 다르기 때문</b>이다 — 재려고 만든 값이 실제와 다르면
 * 재는 의미가 없다.
 *
 * <p>구현이 실패하면 <b>통과시키지 않는다(fail-closed)</b>. 여기서 열어 두면 ③이 켜진 뒤 캐시
 * 장애가 곧 ODsay 요금이 된다. shadow 단계에서 치르는 값은 지표가 조금 덜 찍히는 것뿐이다.
 */
public interface RerouteCooldownPort {

    /**
     * @return 지금 찾아봐도 되면 true. 최근에 이미 찾았거나 확인 자체가 실패하면 false
     */
    boolean tryAdmit(Long journeyId, int routeRevision, int transferIndex);
}
