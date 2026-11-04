package watoo.grd.nextroute.infrastructure.adapter.out.api;

import java.util.Optional;

/**
 * {@link ApiCallBlocker} 가 몇몇 이름의 차단 상태를 서버 사이에서 공유할 때 쓰는 저장소(bus-multi-instance PR-4).
 * 한 서버가 한도 오류를 받으면 다른 서버도 바로 멈춘다 — 공급자 키는 어차피 전체가 한도 초과 상태다.
 */
public interface SharedBlockStore {

    /** 이 저장소가 맡는 이름인지. 아니면 JVM 차단기만 쓴다. */
    boolean handles(String apiName);

    /** @return 차단 중이면 true. 저장소 오류는 empty(호출자가 JVM 차단기로 내려앉힌다). */
    Optional<Boolean> isBlocked(String apiName);

    /** 오늘(KST) 자정까지 차단. 저장소 오류는 삼킨다(JVM 차단기가 이미 막았다). */
    void block(String apiName);
}
