package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.DetectionStamp;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 여정마다 "다음 평가가 소비할 처음 본 시각" 한 칸(#446 처음부터 끝까지 지표).
 *
 * <p>측정 전용이다. 실패는 전부 삼킨다 — 이 칸이 없으면 지표만 하한(평가 시작 시각)으로 떨어지고, 판정·알림은 그대로다.
 */
public interface DetectionStampPort {

    /**
     * 변화 피드가 깨우려는 여정들에 처음 본 시각을 남긴다. <b>이미 있으면 그대로 둔다</b> — 아직 평가가 소비하지 않은 더
     * 이른 관측이 있다는 뜻이다(미뤄진 깨우기가 다음 수집에 다시 와도 처음 본 시각은 첫 관측).
     */
    void stamp(Map<ReevaluationTier, ? extends Collection<Long>> journeysByTier, Instant observedAt);

    /** 평가를 시작하며 꺼내 지운다. 그 뒤에 오는 관측은 다음 평가 몫이다. */
    Optional<DetectionStamp> consume(long journeyId);

    /** 꺼냈는데 평가를 못 했으면(공급자 슬롯 없음) 되돌려 둔다. 그새 다른 관측이 들어왔어도 더 이른 쪽이 남는다. */
    void restore(long journeyId, DetectionStamp stamp);

    /** 아무것도 안 하는 구현(꺼짐·시험용). */
    DetectionStampPort NONE = new DetectionStampPort() {
        @Override
        public void stamp(Map<ReevaluationTier, ? extends Collection<Long>> journeysByTier, Instant observedAt) {
        }

        @Override
        public Optional<DetectionStamp> consume(long journeyId) {
            return Optional.empty();
        }

        @Override
        public void restore(long journeyId, DetectionStamp stamp) {
        }
    };
}
