package watoo.grd.nextroute.application.journey.dto;

/**
 * batch 처리 결과.
 *
 * <p>세 수를 나눠 돌려주는 이유는 앱이 <b>재전송할지</b>를 판단해야 하기 때문이다. 중복은 이미
 * 서버에 있다는 뜻이라 재전송할 필요가 없고, 거절은 그 이벤트가 영영 저장되지 않는다는 뜻이다.
 *
 * @param accepted   새로 저장된 수
 * @param duplicated 이미 있어서 버린 수
 * @param rejected   검증에 걸려 버린 수
 */
public record JourneyTelemetryResult(int accepted, int duplicated, int rejected) {
}
