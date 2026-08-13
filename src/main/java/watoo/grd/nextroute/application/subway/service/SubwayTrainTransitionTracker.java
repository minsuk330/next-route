package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 위치 스냅샷 두 장을 견줘 "이 열차가 저 역을 출발했다·도착했다"를 만든다.
 *
 * <h2>왜 사라짐을 출발로 읽지 않는가</h2>
 *
 * <p>위치 목록에서 열차가 없어지는 것은 운행 종료이거나 결측이다. 출발과 아무 상관이 없다.
 * 출발은 <b>역이 바뀌는 것</b>으로만 읽는다.
 *
 * <h2>출발 표시 하나를 믿지 않는 이유</h2>
 *
 * <p>원천이 갱신을 멈추면 지난 {@code trainSttus=2} 가 계속 다시 읽힌다. 그래서 선행 근거를 본다.
 *
 * <pre>
 * ① A역 진입/도착  →  ② 더 최신 recptnDt 의 A역 출발      → CORROBORATED (하류 관측 불필요)
 * ①이 없는 ②                                            → OBSERVED (잠정)
 * A역에 있었다  →  인접한 B역에서 재관측                  → CORROBORATED
 * A역에 있었다  →  인접하지 않은 역에서 재관측            → 사건 없음(회차·차량기지·역행)
 * </pre>
 *
 * <p>①→②가 이미 확인됐다면 다음 역까지 기다릴 필연성이 없다. 지연을 대가 없이 치르지 않는다.
 *
 * <h2>시각을 점으로 쓰지 않는다</h2>
 *
 * <p>{@code recptnDt} 는 "최종수신시간"이라 실제 사건은 그보다 앞이다. 그래서 사건마다 구간을
 * 준다. 관측이 빌수록 구간이 넓어지고, 넓은 구간은 뒤(탑승 판정)에서 자동으로 보수적인 판정이 된다.
 *
 * <p><b>쓰는 쪽은 수집기 하나다.</b> 노선을 순차로 돌기 때문에 같은 열차를 두 스레드가 동시에
 * 건드리지 않는다. 저장소 갱신에 잠금을 두지 않은 것은 그 전제 위에서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayTrainTransitionTracker {

    private static final DateTimeFormatter RECEIVED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayTrainLogPort logPort;
    private final SubwayStationAdjacency adjacency;
    private final ServiceDateResolver serviceDateResolver;
    private final SubwayPositionProperties properties;

    /**
     * 노선 하나의 응답을 반영한다.
     *
     * @return 이번에 만든 사건 수. 지표·시험용이다
     */
    public int observe(SubwayPositionFetchResult result) {
        if (result == null || !result.isComplete()) {
            // 완결되지 않은 응답으로 전이를 만들면 "사라진" 열차가 대량으로 생긴다.
            return 0;
        }

        Map<TrainIdentity, SubwayPositionInfo> byIdentity = new HashMap<>();
        Set<TrainIdentity> conflicted = new HashSet<>();
        for (SubwayPositionInfo row : result.rows()) {
            if (!row.isTrackable()) {
                continue;
            }
            Instant receivedAt = parseReceivedAt(row.receivedAt());
            if (receivedAt == null) {
                continue;
            }
            TrainIdentity identity = identityOf(row, receivedAt);
            if (byIdentity.put(identity, row) != null) {
                // 같은 열차가 한 응답에 두 번 나왔다. 어느 쪽이 진짜인지 알 수 없으므로
                // 마지막 것으로 덮지 않고 이 열차를 이번 회차에서 통째로 뺀다.
                conflicted.add(identity);
            }
        }
        if (!conflicted.isEmpty()) {
            log.warn("[TrainTransition] identity 중복 — lineId={}, {}건 격리",
                    result.lineId(), conflicted.size());
        }

        int created = 0;
        for (Map.Entry<TrainIdentity, SubwayPositionInfo> entry : byIdentity.entrySet()) {
            if (conflicted.contains(entry.getKey())) {
                continue;
            }
            created += observeOne(entry.getKey(), entry.getValue(), result.fetchedAt());
        }
        return created;
    }

    private int observeOne(TrainIdentity identity, SubwayPositionInfo row, Instant fetchedAt) {
        Instant receivedAt = parseReceivedAt(row.receivedAt());
        if (isStale(receivedAt, fetchedAt)) {
            // 오래된 관측이다. 전체 응답이 정상이어도 특정 열차만 갱신이 멈출 수 있다.
            return 0;
        }

        SubwayTrainObservation now = new SubwayTrainObservation(
                row.stationId(), row.stationName(), row.status(), receivedAt,
                row.terminalId(), row.express());

        Optional<SubwayTrainObservation> previous = logPort.lastSeen(identity);
        if (previous.isEmpty()) {
            // 직전 관측이 없다. 여기서 사건을 만들면 재시작 직후 모든 열차가 방금 나타난 것처럼
            // 보인다. 첫 관측은 기준점으로만 쓴다.
            logPort.remember(identity, now);
            return 0;
        }

        SubwayTrainObservation before = previous.get();
        if (!now.isNewerThan(before)) {
            // 원천이 아직 갱신되지 않았거나 시각이 역행했다. 둘 다 새 사건이 아니다.
            return 0;
        }

        int created = now.isSameStation(before)
                ? atSameStation(identity, before, now)
                : movedStation(identity, before, now);

        logPort.remember(identity, now);
        return created;
    }

    /** 같은 역에서 상태만 바뀐 경우. */
    private int atSameStation(TrainIdentity identity,
                              SubwayTrainObservation before, SubwayTrainObservation now) {
        if (now.status() == SubwayTrainStatus.DEPARTED) {
            // ①(그 역에 있었다) 이 있으면 인과가 관측된 것이다. 하류 재관측을 기다리지 않는다.
            SubwayTrainEvent.Quality quality = before.status().isAtStation()
                    ? SubwayTrainEvent.Quality.CORROBORATED
                    : SubwayTrainEvent.Quality.OBSERVED;
            logPort.append(identity, departure(now, lowerBound(now.receivedAt()), now.receivedAt(), quality));
            return 1;
        }
        if (now.status().isAtStation() && !before.status().isAtStation()) {
            // 진입·도착으로 바뀌었다. 도착 시각은 두 관측 사이 어딘가다.
            logPort.append(identity, arrival(now, lowerBound(before.receivedAt()), now.receivedAt()));
            return 1;
        }
        return 0;
    }

    /**
     * 역이 바뀐 경우.
     *
     * <p>맞닿은 역으로 옮겨 갔을 때만 이전 역 출발을 확정한다. 그렇지 않으면 회차·차량기지·
     * 지선 분기·원천 역행 중 하나라 사건을 만들지 않는다.
     */
    private int movedStation(TrainIdentity identity,
                             SubwayTrainObservation before, SubwayTrainObservation now) {
        if (!adjacency.isAdjacent(identity.lineId(), before.stationName(), now.stationName())) {
            log.debug("[TrainTransition] 인접하지 않은 이동 — {} {}→{}",
                    identity.asKeySuffix(), before.stationName(), now.stationName());
            return 0;
        }

        int created = 0;
        // 이전 역 출발. 출발 표시를 봤든 놓쳤든, 다음 역에서 보였으므로 뒷받침된다.
        //
        // 상한이 갈린다. 직전 관측이 이미 "출발"이었다면 그 시점에 출발이 보고된 것이므로
        // 구간을 그 관측까지로 좁힌다. 놓쳤다면 두 관측 사이 어딘가라 넓게 둔다 —
        // 그 넓이가 그대로 불확실성이 되어 뒤 판정을 보수적으로 만든다.
        Instant upper = before.status() == SubwayTrainStatus.DEPARTED
                ? before.receivedAt()
                : now.receivedAt();
        logPort.append(identity, new SubwayTrainEvent(
                SubwayTrainEvent.Type.DEPARTED, before.stationId(), before.stationName(),
                lowerBound(before.receivedAt()), upper, SubwayTrainEvent.Quality.CORROBORATED,
                before.terminalId(), before.express()));
        created++;

        if (now.status().isAtStation()) {
            logPort.append(identity, arrival(now, lowerBound(before.receivedAt()), now.receivedAt()));
            created++;
        }
        return created;
    }

    private SubwayTrainEvent departure(SubwayTrainObservation at, Instant from, Instant to,
                                       SubwayTrainEvent.Quality quality) {
        return new SubwayTrainEvent(SubwayTrainEvent.Type.DEPARTED, at.stationId(), at.stationName(),
                from, to, quality, at.terminalId(), at.express());
    }

    private SubwayTrainEvent arrival(SubwayTrainObservation at, Instant from, Instant to) {
        return new SubwayTrainEvent(SubwayTrainEvent.Type.ARRIVED, at.stationId(), at.stationName(),
                from, to, SubwayTrainEvent.Quality.CORROBORATED, at.terminalId(), at.express());
    }

    /**
     * 구간의 하한. <b>과거로</b> 넓힌다.
     *
     * <p>{@code recptnDt} 는 최종수신시간이라 실제 사건은 그보다 앞이다. 상한을 미래로 미는 것은
     * 방향이 반대다 — 아직 일어나지 않은 일을 후보에 넣게 된다.
     */
    private Instant lowerBound(Instant reportedAt) {
        return reportedAt.minus(properties.getSourceLagBudget());
    }

    private boolean isStale(Instant receivedAt, Instant fetchedAt) {
        if (receivedAt == null || fetchedAt == null) {
            return true;
        }
        Duration age = Duration.between(receivedAt, fetchedAt);
        return age.isNegative() || age.compareTo(properties.getFreshnessWindow()) > 0;
    }

    private TrainIdentity identityOf(SubwayPositionInfo row, Instant receivedAt) {
        return new TrainIdentity(serviceDateResolver.resolve(receivedAt),
                row.lineId(), row.direction(), row.trainNo());
    }

    /** {@code "yyyy-MM-dd HH:mm:ss"} 를 KST 로 읽는다. 파싱 실패는 그 행을 버리는 사유다. */
    private Instant parseReceivedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), RECEIVED_AT_FORMAT).atZone(SEOUL).toInstant();
        } catch (Exception e) {
            log.debug("[TrainTransition] recptnDt 파싱 실패 — {}", raw);
            return null;
        }
    }
}
