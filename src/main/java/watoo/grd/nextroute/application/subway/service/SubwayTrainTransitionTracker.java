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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

        Map<TrainIdentity, List<Sample>> byIdentity = new LinkedHashMap<>();
        for (SubwayPositionInfo row : result.rows()) {
            if (!row.isTrackable()) {
                continue;
            }
            Instant receivedAt = parseReceivedAt(row.receivedAt());
            if (receivedAt == null) {
                continue;
            }
            byIdentity.computeIfAbsent(identityOf(row, receivedAt), key -> new ArrayList<>())
                    .add(new Sample(row, receivedAt));
        }

        int created = 0;
        int resolved = 0;
        int quarantined = 0;
        for (Map.Entry<TrainIdentity, List<Sample>> entry : byIdentity.entrySet()) {
            List<Sample> samples = entry.getValue();
            Sample chosen = samples.size() == 1
                    ? samples.get(0)
                    : resolveDuplicate(entry.getKey(), samples);
            if (chosen == null) {
                quarantined++;
                continue;
            }
            if (samples.size() > 1) {
                resolved++;
            }
            created += observeOne(entry.getKey(), chosen.row(), result.fetchedAt());
        }
        if (resolved > 0 || quarantined > 0) {
            log.warn("[TrainTransition] 중복 열차 — lineId={}, 최신 채택 {}건, 격리 {}건",
                    result.lineId(), resolved, quarantined);
        }
        return created;
    }

    /**
     * 같은 열차가 한 응답에 여러 번 실려 왔을 때 어느 행을 쓸지 고른다.
     *
     * <p>실측: 1·3·4호선에서 열차의 <b>10~15%</b> 가 매 회차 중복으로 온다(2호선은 0). 두 행은
     * 대개 {@code recptnDt} 만 다른 같은 열차의 앞뒤 스냅샷이라 <b>최신이 명확히 있다</b>.
     *
     * <p>예전에는 이런 열차를 그 회차에서 통째로 뺐다. 그러면 {@link SubwayTrainLogPort#remember}
     * 까지 건너뛰어 다음 관측이 몇 분 묵은 관측과 비교되고, 그 사이 열차가 두 역 이상 움직였으면
     * {@link #movedStation}의 인접 판정에 막혀 <b>출발 사건이 아예 안 만들어진다</b>. 출발이 없으면
     * 그 열차는 출발 색인에 없고, 그 열차를 탄 사용자는 후보가 0이라 고정이 안 된다.
     *
     * <p>그래도 <b>열번이 겹친 서로 다른 열차</b>일 가능성은 남는다. 그때 최신을 고르면 두 열차
     * 사이를 오가는 가짜 이동이 생긴다. 그래서 두 가지 경우에만 예전처럼 격리한다 —
     * ①최신 행의 역이 다른 행의 역과 같지도 맞닿지도 않을 때 ②가장 최신이 둘인데 그 둘이 서로
     * 다른 스냅샷일 때(순서를 가릴 근거가 없다).
     *
     * @return 채택할 표본. 가릴 수 없으면 {@code null}
     */
    private Sample resolveDuplicate(TrainIdentity identity, List<Sample> samples) {
        Sample latest = samples.get(0);
        for (Sample sample : samples) {
            if (sample.receivedAt().isAfter(latest.receivedAt())) {
                latest = sample;
            }
        }
        for (Sample sample : samples) {
            if (sample == latest) {
                continue;
            }
            if (!sample.receivedAt().isBefore(latest.receivedAt())) {
                // 같은 시각이 둘이다. 같은 스냅샷이 복제된 것이면 어느 쪽을 써도 결과가 같다.
                if (!isSameSnapshot(sample.row(), latest.row())) {
                    return null;
                }
                continue;
            }
            if (!isSameTrainTrack(identity, latest.row(), sample.row())) {
                return null;
            }
        }
        return latest;
    }

    /** 역·상태가 모두 같으면 같은 스냅샷이다. */
    private boolean isSameSnapshot(SubwayPositionInfo a, SubwayPositionInfo b) {
        return Objects.equals(a.stationId(), b.stationId()) && a.status() == b.status();
    }

    /** 같은 역이거나 맞닿은 역이면 한 열차의 앞뒤 스냅샷으로 본다. */
    private boolean isSameTrainTrack(TrainIdentity identity,
                                     SubwayPositionInfo latest, SubwayPositionInfo older) {
        if (Objects.equals(latest.stationId(), older.stationId())) {
            return true;
        }
        return adjacency.isAdjacent(identity.lineId(), older.stationName(), latest.stationName())
                || adjacency.isAdjacentInOrder(identity.lineId(), identity.direction(),
                older.stationId(), latest.stationId());
    }

    /** 응답 한 행과 그 행의 {@code recptnDt}. 시각을 두 번 파싱하지 않으려고 같이 들고 다닌다. */
    private record Sample(SubwayPositionInfo row, Instant receivedAt) {
    }

    private int observeOne(TrainIdentity identity, SubwayPositionInfo row, Instant fetchedAt) {
        Instant receivedAt = parseReceivedAt(row.receivedAt());
        if (isStale(receivedAt, fetchedAt)) {
            // 오래된 관측이다. 전체 응답이 정상이어도 특정 열차만 갱신이 멈출 수 있다.
            return 0;
        }

        SubwayTrainObservation now = new SubwayTrainObservation(
                row.stationId(), row.stationName(), row.status(), receivedAt,
                row.terminalId(), row.express(), row.directAtRaw());

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
        if (!adjacency.isAdjacent(identity.lineId(), before.stationName(), now.stationName())
                && !adjacency.isAdjacentInOrder(identity.lineId(), identity.direction(),
                before.stationId(), now.stationId())) {
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
                before.terminalId(), before.express(), before.directAtRaw()));
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
                from, to, quality, at.terminalId(), at.express(), at.directAtRaw());
    }

    private SubwayTrainEvent arrival(SubwayTrainObservation at, Instant from, Instant to) {
        return new SubwayTrainEvent(SubwayTrainEvent.Type.ARRIVED, at.stationId(), at.stationName(),
                from, to, SubwayTrainEvent.Quality.CORROBORATED, at.terminalId(), at.express(), at.directAtRaw());
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
