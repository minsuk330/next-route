package watoo.grd.nextroute.application.journey.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 후보를 줄 세우는 <b>한 벌뿐인 규칙</b>.
 *
 * <pre>
 * 목적지 도착 시각(이른 것) → 환승 수(적은 것) → 걷는 시간(짧은 것) → 여유(넉넉한 것)
 * </pre>
 *
 * <h2>왜 개선폭이 아니라 도착 시각인가</h2>
 * 개선폭은 <b>기준선이 있을 때만</b> 존재하는 값이다. 기준선을 못 잰 요청(지금 차를 아예 못 타는
 * 상황 — 다른 길이 가장 필요한 사람이다)에서는 정렬 기준이 사라져 다른 규칙을 쓰게 되고, 그러면
 * 같은 후보 목록이 <b>상황에 따라 다른 순서</b>로 나간다. 도착 시각은 언제나 있다. 기준선이 고정된
 * 요청 안에서는 "도착 이른 순 = 개선폭 큰 순"이라 옛 정렬과 결과도 같다.
 *
 * <h2>동점을 시간으로 풀지 않는 이유</h2>
 * 도착 시각이 같으면 <b>덜 갈아타고 덜 걷는 길</b>이 낫다. 마지막으로 여유(차를 놓칠 위험의
 * 반대말)가 큰 쪽을 고른다 — 1분 차이로 갈아타야 하는 길과 5분 여유가 있는 길이 같은 시각에
 * 닿는다면 후자가 실제로 지켜질 확률이 높다.
 *
 * <h2>시간표 정적 변형</h2>
 * 실시간 overlay 는 비싸다. 정류장마다 여러 후보가 나오는 자리(PR-2)는 <b>overlay 하기 전에</b>
 * 시간표 값으로 한 번 줄을 세워 상위 하나만 overlay 한다. 그때 쓰는 것도 같은 비교기다
 * ({@link Scheduled}) — 거르는 규칙과 보여 주는 규칙이 다르면, 정작 좋은 후보가 overlay 전에
 * 잘려 나간다.
 */
public final class RerouteRanking {

    private RerouteRanking() {
    }

    /**
     * 줄 세우기에 필요한 값. 후보가 무엇으로 만들어졌는지(저장된 경로·검색 결과·정류장 묶음)는
     * 보지 않는다.
     */
    public interface Ranked {

        Instant destinationEtaAt();

        /** 갈아타는 횟수. 적을수록 좋다. */
        int transferCount();

        /** 걷는 시간(초). 짧을수록 좋다. */
        int walkingSec();

        /** 첫 차를 탈 때까지의 여유(초). 클수록 좋다. 모르면 0. */
        int slackSec();
    }

    public static final Comparator<Ranked> ORDER = Comparator
            .comparing(Ranked::destinationEtaAt)
            .thenComparingInt(Ranked::transferCount)
            .thenComparingInt(Ranked::walkingSec)
            .thenComparing(Comparator.comparingInt(Ranked::slackSec).reversed());

    /** 규칙대로 줄 세운 새 목록. 입력은 건드리지 않는다. */
    public static <T extends Ranked> List<T> rank(List<T> candidates) {
        return candidates.stream().sorted(ORDER).toList();
    }

    /** 가장 좋은 하나. 비었으면 null. */
    public static <T extends Ranked> T best(List<T> candidates) {
        return candidates.stream().min(ORDER).orElse(null);
    }

    /**
     * overlay 전 시간표 값으로 줄을 세울 때 쓰는 그릇.
     *
     * @param boardingAt 시간표상 첫 차를 타는 시각. 여유는 앵커와의 차로 잰다
     */
    public record Scheduled(Instant destinationEtaAt, int transferCount, int walkingSec,
                            int slackSec) implements Ranked {

        public static Scheduled of(Instant destinationEtaAt, int transferCount, int walkingSec,
                                   Instant anchorAt, Instant boardingAt) {
            long slack = anchorAt == null || boardingAt == null
                    ? 0 : Duration.between(anchorAt, boardingAt).getSeconds();
            return new Scheduled(destinationEtaAt, transferCount, walkingSec,
                    (int) Math.max(0, slack));
        }
    }
}
