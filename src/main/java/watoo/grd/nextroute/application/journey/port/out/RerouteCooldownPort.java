package watoo.grd.nextroute.application.journey.port.out;

/**
 * 한 여정에서 재탐색을 너무 자주 하지 않게 막는다.
 *
 * <p><b>예전에는 환승 단위였다</b>(stage5 컷오버 전). 원점이 leg 경계를 넘어 주행 중 정류장까지
 * 가리키게 되면서 "같은 환승"이라는 단위가 성립하지 않고, 살아 있는 제안도 여정당 하나가 됐다 —
 * 잠그는 단위가 자리의 단위와 다르면 연타 한 번이 자리를 계속 갈아치운다.
 *
 * <p>방아쇠(①)는 세기만 하지만(shadow) cooldown 은 그때부터 적용한다. <b>cooldown 을 뺀 발생률은
 * ③에서 실제로 나갈 외부 호출 수와 다르기 때문</b>이다 — 재려고 만든 값이 실제와 다르면 재는
 * 의미가 없다.
 *
 * <p>구현이 실패하면 <b>통과시키지 않는다(fail-closed)</b>. 여기서 열어 두면 캐시 장애가 곧 외부
 * 호출 요금이 된다. shadow 단계에서 치르는 값은 지표가 조금 덜 찍히는 것뿐이다.
 */
public interface RerouteCooldownPort {

    /**
     * 누가 쓰는 잠금인지. <b>키가 갈린다.</b>
     *
     * <p>하나로 두면 아무것도 만들지 않는 shadow 방아쇠가 잠금을 태워, 그 직후 사용자가 누른
     * 요청이 2분간 거절된다(D6). 지금 둘은 실제로 같은 자원을 다투지 않는다 — 방아쇠는 세기만
     * 하고 외부 호출은 요청 경로만 한다.
     *
     * <p>⑥에서 자동 생성을 켜면 그때 합칠지 다시 판단한다. 그때는 둘 다 외부 호출을 부른다.
     */
    enum Scope {
        /** 주기 평가의 방아쇠(shadow). 발생률 측정용. */
        TRIGGER,
        /** 사용자가 "다시 찾아줘"를 누른 경로. 실제 외부 호출이 나간다. */
        REQUEST
    }

    /**
     * @return 지금 찾아봐도 되면 true. 최근에 이미 찾았거나 확인 자체가 실패하면 false
     */
    boolean tryAdmit(Long journeyId, int routeRevision, Scope scope);
}
