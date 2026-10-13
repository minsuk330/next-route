package watoo.grd.nextroute.application.journey.load;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 부하 실험용 여정을 대량으로 만든다.
 *
 * <p>{@code StartJourneyUseCase} 로 만들면 여정 하나마다 스냅숏 조회와 다섯 테이블 쓰기가 일어나
 * 10,000건 준비에만 몇 분이 걸린다. 여기서는 JDBC 배치로 직접 넣는다.
 *
 * <h2>스키마가 강제하는 것</h2>
 *
 * <ul>
 *   <li><b>사용자당 ACTIVE 여정은 하나</b>({@code uq_journey_active_per_user}). 여정 10,000건은
 *       사용자 10,000명을 함께 만들어야 한다</li>
 *   <li>예측이 실제로 일어나려면 {@code journey_leg.start_stop_id} 와
 *       {@code journey_leg_route_option.boarding_seq} 가 채워져 있어야 한다</li>
 *   <li>{@code bus_route}·{@code bus_stop}·{@code bus_route_stop} 에 대응 행이 있어야 예측기의
 *       노선·정류장 검증을 통과한다</li>
 * </ul>
 *
 * <h2>관심을 흩뜨리는 방식</h2>
 *
 * <p>관심(정류장·노선 조합)은 여정 순서에 <b>번갈아</b> 배치한다({@code index % distinctInterests}).
 * 같은 관심을 연달아 두면 캐시 적중이 부풀려져 중복 제거 효과를 실제보다 좋게 읽는다.
 *
 * <p>다음 평가 시각도 한 점에 몰지 않고 창 안에 고르게 편다. 10,000건이 같은 순간에 만기가 되면
 * 그것은 정상 상태가 아니라 시작 직후의 몰림(burst)을 재는 것이 된다.
 */
public final class JourneyLoadFixture {

    /**
     * 관심 하나 = 노선 하나 + 정류장 하나.
     *
     * <p>노선을 공유하고 정류장만 나누는 방식은 쓸 수 없다 — {@code bus_route_stop} 에
     * {@code (route_id, seq)} 유니크 제약이 있어 같은 노선의 같은 순번에 정류장을 여러 개 둘 수 없다.
     */
    static final String ROUTE_PREFIX = "LOAD-ROUTE-";
    static final String STOP_PREFIX = "LOAD-STOP-";
    static final int BOARDING_SEQ = 3;

    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public JourneyLoadFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param journeys        만들 여정 수
     * @param distinctStops   서로 다른 관심(정류장) 수
     * @param dueFrom         첫 여정의 다음 평가 시각
     * @param dueSpread       만기를 흩뜨릴 창. 0 이면 전부 같은 시각(몰림 시나리오)
     * @param nearRatio       환승이 임박한 여정의 비율. 타는 단계를 짧게 둬 평가 간격이 최소(10초)가
     *                        되게 한다. 0 이면 전부 30초 간격이다
     * @param farRideSeconds  임박하지 않은 여정의 타는 단계 길이. 15분을 넘기면 환승이 예측 시야 밖이라
     *                        급하지 않은(긴 간격) 여정이 된다
     */
    public record Spec(int journeys, int distinctStops, Instant dueFrom, Duration dueSpread,
                       double nearRatio, int farRideSeconds) {

        public Spec(int journeys, int distinctStops, Instant dueFrom, Duration dueSpread) {
            this(journeys, distinctStops, dueFrom, dueSpread, 0.0, DEFAULT_RIDE_SECONDS);
        }

        /**
         * 이 순번의 여정이 임박 여정인가.
         *
         * <p>앞쪽에 몰지 않고 고르게 섞는다. 만기도 순번으로 펴므로 몰면 한 구간만 10초 간격이 된다.
         */
        public boolean isNear(int index) {
            if (nearRatio <= 0) {
                return false;
            }
            int every = (int) Math.max(1, Math.round(1 / nearRatio));
            return index % every == 0;
        }
    }

    /** 임박 여정의 타는 단계 길이. 하차 버퍼·환승 도보를 더해도 준비까지 5분 안이다. */
    static final int NEAR_RIDE_SECONDS = 120;
    static final int DEFAULT_RIDE_SECONDS = 600;

    public record Prepared(List<Long> journeyIds, List<Long> userIds, int distinctStops) {
        public int size() {
            return journeyIds.size();
        }
    }

    public Prepared prepare(Spec spec) {
        prepareRouteMaster(spec.distinctStops());
        List<Long> userIds = insertUsers(spec.journeys());
        List<Long> journeyIds = insertJourneys(userIds, spec);
        insertLegsAndTransfers(journeyIds, spec);
        return new Prepared(journeyIds, userIds, spec.distinctStops());
    }

    /** 예측기가 노선·정류장 조합을 DB 로 확인하므로 마스터 행이 있어야 한다. */
    private void prepareRouteMaster(int distinctStops) {
        cleanUpRouteMaster();
        jdbc.batchUpdate("""
                insert into bus_route (created_at, updated_at, route_id, route_name)
                values (now(), now(), ?, 'load-test')
                """, routeArgs(distinctStops));
        jdbc.batchUpdate("""
                insert into bus_stop (created_at, updated_at, stop_id, stop_name)
                values (now(), now(), ?, 'load-stop')
                """, stopArgs(distinctStops));
        jdbc.batchUpdate("""
                insert into bus_route_stop (created_at, updated_at, route_id, stop_id, seq)
                values (now(), now(), ?, ?, ?)
                """, routeStopArgs(distinctStops));
    }

    private List<Object[]> routeArgs(int distinctStops) {
        List<Object[]> args = new ArrayList<>(distinctStops);
        for (int i = 0; i < distinctStops; i++) {
            args.add(new Object[]{routeId(i)});
        }
        return args;
    }

    private List<Object[]> stopArgs(int distinctStops) {
        List<Object[]> args = new ArrayList<>(distinctStops);
        for (int i = 0; i < distinctStops; i++) {
            args.add(new Object[]{stopId(i)});
        }
        return args;
    }

    private List<Object[]> routeStopArgs(int distinctStops) {
        List<Object[]> args = new ArrayList<>(distinctStops);
        for (int i = 0; i < distinctStops; i++) {
            args.add(new Object[]{routeId(i), stopId(i), BOARDING_SEQ});
        }
        return args;
    }

    static String stopId(int interestIndex) {
        return STOP_PREFIX + interestIndex;
    }

    static String routeId(int interestIndex) {
        return ROUTE_PREFIX + interestIndex;
    }

    private List<Long> insertUsers(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int from = 0; from < count; from += BATCH) {
            int size = Math.min(BATCH, count - from);
            ids.addAll(jdbc.queryForList("""
                    insert into users (created_at, updated_at, token_generation)
                    select now(), now(), 0 from generate_series(1, ?)
                    returning id
                    """, Long.class, size));
        }
        return ids;
    }

    /**
     * 여정 본체. 사용자 한 명당 하나씩.
     *
     * <p>{@code route_snapshot} 은 크기가 곧 조회 비용이라 빈 객체로 두지 않는다. 실제 경로와
     * 비슷한 분량을 채워 엔티티 로드 비용이 실험에 반영되게 한다.
     */
    private List<Long> insertJourneys(List<Long> userIds, Spec spec) {
        String snapshot = routeSnapshotJson();
        List<Long> journeyIds = new ArrayList<>(userIds.size());
        for (int from = 0; from < userIds.size(); from += BATCH) {
            int to = Math.min(from + BATCH, userIds.size());
            StringBuilder sql = new StringBuilder("""
                    insert into journey_session (
                        created_at, updated_at, user_id, status, command_version, evaluation_version,
                        route_revision, current_leg_index, started_at, last_heartbeat_at,
                        guidance_lease_until, next_evaluation_at, route_snapshot)
                    values
                    """);
            List<Object> args = new ArrayList<>();
            for (int i = from; i < to; i++) {
                sql.append(i > from ? "," : "")
                        .append(" (now(), now(), ?, 'ACTIVE', 1, 0, 1, 1, now(), now(),"
                                + " now() + interval '3 minutes', ?, ?::jsonb)");
                args.add(userIds.get(i));
                // pgjdbc 는 Instant 를 직접 못 받는다. 시간대를 명시한 타입으로 넘긴다.
                args.add(java.time.OffsetDateTime.ofInstant(dueAt(spec, i), java.time.ZoneOffset.UTC));
                args.add(snapshot);
            }
            sql.append(" returning id");
            journeyIds.addAll(jdbc.queryForList(sql.toString(), Long.class, args.toArray()));
        }
        return journeyIds;
    }

    /** 만기를 창 안에 고르게 편다. 창이 0이면 전부 같은 시각이다. */
    private Instant dueAt(Spec spec, int index) {
        if (spec.dueSpread().isZero() || spec.journeys() <= 1) {
            return spec.dueFrom();
        }
        long offsetMillis = spec.dueSpread().toMillis() * index / spec.journeys();
        return spec.dueFrom().plusMillis(offsetMillis);
    }

    /**
     * 여정마다 네 단계와 환승 하나.
     *
     * <pre>
     * leg 0 WALK(완료) → leg 1 RIDE_BUS(진행 중) → leg 2 TRANSFER_WALK → leg 3 WAIT_BUS(환승 대상)
     * </pre>
     *
     * <p>진행 중인 단계가 있어야 "환승 정류장에 언제 닿는지"가 정해지고, 그 시각을 기준으로 갈아탈
     * 차량을 물어볼 수 있다.
     */
    private void insertLegsAndTransfers(List<Long> journeyIds, Spec spec) {
        for (int from = 0; from < journeyIds.size(); from += BATCH) {
            int to = Math.min(from + BATCH, journeyIds.size());
            List<Long> slice = journeyIds.subList(from, to);
            insertLegs(slice, from, spec);
            insertRouteOptions(slice);
            linkSelectedOptions(slice);
            insertTransfers(slice);
        }
    }

    private void insertLegs(List<Long> journeyIds, int offset, Spec spec) {
        List<Object[]> args = new ArrayList<>(journeyIds.size() * 4);
        for (int i = 0; i < journeyIds.size(); i++) {
            Long journeyId = journeyIds.get(i);
            int interest = (offset + i) % spec.distinctStops();
            String stopId = stopId(interest);
            String routeId = routeId(interest);
            args.add(new Object[]{journeyId, 0, "WALK", "COMPLETED", null, 120, null});
            int ride = spec.isNear(offset + i) ? NEAR_RIDE_SECONDS : spec.farRideSeconds();
            args.add(new Object[]{journeyId, 1, "RIDE_BUS", "ACTIVE", null, ride, routeId});
            args.add(new Object[]{journeyId, 2, "TRANSFER_WALK", "PENDING", null, 180, null});
            args.add(new Object[]{journeyId, 3, "WAIT_BUS", "PENDING", stopId, 300, routeId});
        }
        jdbc.batchUpdate("""
                insert into journey_leg (
                    created_at, updated_at, journey_id, route_revision, leg_index, type, state,
                    start_stop_id, planned_duration_sec, activated_at, route_id)
                values (now(), now(), ?, 1, ?, ?, ?, ?, ?, now(), ?)
                """, args);
    }

    private void insertRouteOptions(List<Long> journeyIds) {
        jdbc.update("""
                insert into journey_leg_route_option (
                    created_at, updated_at, journey_leg_id, option_index, lane_index,
                    route_id, route_name, boarding_seq)
                select now(), now(), l.id, 0, 0, l.route_id, 'load', ?
                  from journey_leg l
                 where l.journey_id = any (?) and l.leg_index = 3
                """, BOARDING_SEQ, journeyIds.toArray(Long[]::new));
    }

    /** 대기 단계가 자기 노선 후보를 가리켜야 예측기가 노선을 해석한다. */
    private void linkSelectedOptions(List<Long> journeyIds) {
        jdbc.update("""
                update journey_leg l
                   set selected_route_option_id = o.id
                  from journey_leg_route_option o
                 where o.journey_leg_id = l.id
                   and l.journey_id = any (?) and l.leg_index = 3
                """, (Object) journeyIds.toArray(Long[]::new));
    }

    private void insertTransfers(List<Long> journeyIds) {
        List<Object[]> args = new ArrayList<>(journeyIds.size());
        for (Long journeyId : journeyIds) {
            args.add(new Object[]{journeyId});
        }
        jdbc.batchUpdate("""
                insert into transfer_connection (
                    created_at, updated_at, journey_id, route_revision, transfer_index,
                    from_leg_index, walk_leg_index, to_leg_index, status)
                values (now(), now(), ?, 1, 0, 1, 2, 3, 'UNKNOWN')
                """, args);
    }

    /** 실제 경로 응답과 비슷한 분량. 엔티티 로드 비용을 실험에 반영하려는 것이다. */
    private String routeSnapshotJson() {
        StringBuilder legs = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            legs.append(i > 0 ? "," : "")
                    .append("{\"trafficType\":2,\"sectionTime\":10,\"distance\":1200,")
                    .append("\"startName\":\"정류장").append(i).append("\",")
                    .append("\"endName\":\"정류장").append(i + 1).append("\",")
                    .append("\"lane\":[{\"busNo\":\"721\",\"busLocalBlID\":\"LOAD-ROUTE-0\"}],")
                    .append("\"passStopList\":[");
            for (int s = 0; s < 20; s++) {
                legs.append(s > 0 ? "," : "")
                        .append("{\"index\":").append(s)
                        .append(",\"stationID\":\"S").append(s)
                        .append("\",\"stationName\":\"정류장이름").append(s).append("\"}");
            }
            legs.append("]}");
        }
        return "{\"pathType\":1,\"info\":{\"totalTime\":25,\"totalDistance\":8200},"
                + "\"subPath\":[" + legs + "]}";
    }

    public void cleanUp(Prepared prepared) {
        if (prepared.journeyIds().isEmpty()) {
            return;
        }
        Long[] journeyIds = prepared.journeyIds().toArray(Long[]::new);
        jdbc.update("update journey_leg set selected_route_option_id = null where journey_id = any (?)",
                (Object) journeyIds);
        jdbc.update("""
                delete from journey_leg_route_option
                 where journey_leg_id in (select id from journey_leg where journey_id = any (?))
                """, (Object) journeyIds);
        for (String table : List.of("journey_leg", "journey_revision", "transfer_connection",
                "journey_event")) {
            jdbc.update("delete from " + table + " where journey_id = any (?)", (Object) journeyIds);
        }
        // 알림 outbox 가 여정을 FK 로 잡는다. 평가가 위험을 감지하면 행이 생기므로 먼저 지운다.
        jdbc.update("delete from journey_notification where journey_id = any (?)", (Object) journeyIds);
        jdbc.update("delete from journey_session where id = any (?)", (Object) journeyIds);
        jdbc.update("delete from users where id = any (?)",
                (Object) prepared.userIds().toArray(Long[]::new));
        cleanUpRouteMaster();
    }

    private void cleanUpRouteMaster() {
        jdbc.update("delete from bus_route_stop where route_id like ?", ROUTE_PREFIX + "%");
        jdbc.update("delete from bus_route where route_id like ?", ROUTE_PREFIX + "%");
        jdbc.update("delete from bus_stop where stop_id like ?", STOP_PREFIX + "%");
    }
}
