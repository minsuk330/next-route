package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 평가하려고 선점한 여정 하나.
 *
 * <p>{@code leaseUntil} 은 선점할 때 여정 행에 심어 둔 다음 평가 시각이고, <b>소유권 증서로도
 * 쓰인다.</b> 평가를 마친 워커는 저장 전에 "그 여정의 다음 평가 시각이 아직 이 값인가"를 묻는다.
 * 값이 바뀌었다면 lease 가 만료돼 다른 워커가 가져간 것이므로 계산 결과를 버린다.
 *
 * @param journeyId  선점한 여정
 * @param leaseUntil 선점 시각(= 심어 둔 다음 평가 시각)
 */
public record JourneyDueClaim(Long journeyId, Instant leaseUntil) {
}
