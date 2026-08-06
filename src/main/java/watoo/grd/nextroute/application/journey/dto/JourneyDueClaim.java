package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 평가하려고 선점한 여정 하나.
 *
 * <p>{@code leaseUntil} 은 선점할 때 여정 행에 심어 둔 다음 평가 시각이고, <b>소유권 증서로도
 * 쓰인다.</b> 평가를 마친 워커는 저장 전에 "그 여정의 다음 평가 시각이 아직 이 값인가"를 묻는다.
 * 값이 바뀌었다면 lease 가 만료돼 다른 워커가 가져간 것이므로 계산 결과를 버린다.
 *
 * <p>{@code dueAt} 은 <b>선점이 덮어쓰기 전의</b> 다음 평가 시각이다. 이 여정이 언제부터 평가를
 * 기다리고 있었는지를 나타내며, "예정 시각에서 얼마나 늦게 평가됐나"(due evaluator 지연)를 재는
 * 유일한 근거다. 선점 SQL 이 값을 덮어쓰기 때문에 나중에 다시 읽을 수 없어 여기에 실어 나른다.
 *
 * @param journeyId  선점한 여정
 * @param leaseUntil 선점 시각(= 심어 둔 다음 평가 시각)
 * @param dueAt      선점 전에 적혀 있던 다음 평가 시각. 지연 계측의 기준점
 */
public record JourneyDueClaim(Long journeyId, Instant leaseUntil, Instant dueAt) {
}
