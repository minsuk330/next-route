package watoo.grd.nextroute.application.subway.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * 열차가 어느 역에 <b>도착했다</b> 또는 <b>출발했다</b>는 사건.
 *
 * <p>시각이 점이 아니라 구간인 것이 핵심이다. 원천의 {@code recptnDt} 는 "최종수신시간"이라
 * 실제 사건은 그보다 앞에 있고, 관측이 빌수록 그 앞이 얼마나 앞인지 모른다. 점으로 적으면
 * 그 모름이 사라져 <b>가짜 정밀도</b>가 생긴다.
 *
 * <p>결측이 길수록 구간이 넓어지고, 넓은 구간은 뒤(탑승 판정)에서 자동으로 보수적인 판정을
 * 만든다. 이게 설계의 자동조절 장치다.
 *
 * @param from    사건이 일어났을 수 있는 가장 이른 시각
 * @param to      가장 늦은 시각
 * @param quality {@link Quality}
 * @param directAtRaw 위치 API {@code directAt} 원문(subway-express PR-S3). {@code express} 는 9(ITX)를 UNKNOWN 으로
 *                    접으므로 원문을 함께 싣는다. 옛 기록이면 null
 */
public record SubwayTrainEvent(
        Type type,
        String stationId,
        String stationName,
        Instant from,
        Instant to,
        Quality quality,
        String terminalId,
        SubwayExpressType express,
        String directAtRaw
) {

    /** {@code directAt} 원문 없이(옛 호출부·시험). */
    public SubwayTrainEvent(Type type, String stationId, String stationName, Instant from, Instant to,
                            Quality quality, String terminalId, SubwayExpressType express) {
        this(type, stationId, stationName, from, to, quality, terminalId, express, null);
    }

    public enum Type {
        ARRIVED,
        DEPARTED
    }

    /**
     * 이 사건을 얼마나 믿을 수 있는지.
     *
     * <p>둘을 가르는 이유: 원천이 갱신을 멈추면 지난 "출발" 표시가 계속 다시 읽힌다.
     * 그 역에 있었다는 선행 관측이나 다음 역에서의 재관측이 있어야 진짜 출발이다.
     */
    public enum Quality {
        /** 출발 표시를 보긴 했지만 앞뒤 정황이 없다. 잠정 판정에는 쓰되 확정 근거로는 약하다. */
        OBSERVED,
        /** 선행 관측(그 역에 있었다) 또는 하류 재관측으로 뒷받침됐다. */
        CORROBORATED;

        public boolean isStrongerThan(Quality other) {
            return other == OBSERVED && this == CORROBORATED;
        }
    }

    public SubwayTrainEvent {
        if (from != null && to != null && from.isAfter(to)) {
            // 구간이 뒤집히면 midpoint 가 구간 밖으로 나간다. 부르는 쪽 계산 실수를 여기서 잡는다.
            throw new IllegalArgumentException("event interval reversed: " + from + " > " + to);
        }
    }

    /** 구간의 가운데. 점 하나가 필요할 때만 쓰고, 판정은 구간으로 한다. */
    public Instant estimate() {
        return from.plus(Duration.between(from, to).dividedBy(2));
    }

    /** 이 사건이 얼마나 흐릿한지(초). 판정 관문이 이 값을 본다. */
    public long uncertaintySeconds() {
        return Duration.between(from, to).toSeconds() / 2;
    }

    /** 같은 사건인지. 품질만 다르면 교체 대상이다. */
    public boolean isSameOccurrence(SubwayTrainEvent other) {
        return other != null && type == other.type()
                && stationId != null && stationId.equals(other.stationId());
    }

    public SubwayTrainEvent corroborated(Instant narrowedFrom, Instant narrowedTo) {
        return new SubwayTrainEvent(type, stationId, stationName,
                narrowedFrom, narrowedTo, Quality.CORROBORATED, terminalId, express, directAtRaw);
    }
}
