package watoo.grd.nextroute.application.journey.dto;

import java.time.LocalDateTime;

/**
 * 발송 직전 재확인을 통과한 한 건(#461 후속). 발송기에 넘길 명령과, 접수 기록 때 지표에 쓸 적재 시각을 같이 든다.
 *
 * <p>접수 기록이 알림 행을 다시 읽지 않게 하려고 둔다. 지표에 쓰는 값(채널·체크포인트·payload·만료·적재 시각)은 적재 뒤 바뀌지
 * 않으므로 재확인 때 읽은 값으로 충분하다. 상태 판단은 여전히 증서 조건부 UPDATE 가 한다.
 *
 * @param createdAt 적재 시각(시간대 없는 칼럼 — 적재 clock 의 시간대로 되돌려 쓴다). 없으면 null
 */
public record SendTask(NotificationSendCommand command, LocalDateTime createdAt) {
}
