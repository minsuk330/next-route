package watoo.grd.nextroute.application.route.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.mapping.SeoulBusIdentity;
import watoo.grd.nextroute.application.route.mapping.SeoulSubwayIdentity;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.routing.ActualSource;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.SeqResolution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 버스 실시간 timing lookup: resolved 서울 id → 실시간 도착조회 → RealtimeLegTiming(승차 stop 도착=출발). */
class OtpBusRealtimeTimingLookupTest {

    private final OtpSeoulIdentityResolver identity = mock(OtpSeoulIdentityResolver.class);
    private final SearchTimeBusQueryPort busPort = mock(SearchTimeBusQueryPort.class);
    private final TransferStopResolver stopResolver = mock(TransferStopResolver.class);
    private final TransferArrivalProperties props = new TransferArrivalProperties();
    private final watoo.grd.nextroute.application.route.config.OtpRoutingProperties otpProps =
            new watoo.grd.nextroute.application.route.config.OtpRoutingProperties();
    private final OtpBusRealtimeTimingLookup lookup =
            new OtpBusRealtimeTimingLookup(identity, busPort, stopResolver, props, otpProps);

    // fixture dataTimestamp = "2023-09-27 16:51:36.0" KST = 07:51:36Z
    private final Instant base = Instant.parse("2023-09-27T07:51:36Z");

    private RoutingLeg busLeg() {
        return new RoutingLeg(RoutingMode.BUS, null, "1", "A1", "1:BR_TAGO_SEB100100022", "143",
                "trip", null, base, base.plusSeconds(1200), 1200, List.of(), List.of(), null, null, null);
    }

    private void enable() { props.setEnabled(true); otpProps.setRealtimeEnabled(true); }

    @Test
    @DisplayName("버스: resolve + 단일seq + 실시간 도착 → RealtimeLegTiming(출발=승차stop 도착, arrival=null, REALTIME)")
    void busResolved() {
        enable();
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulBusIdentity("101000041", "100100022", "143")));
        when(stopResolver.resolveSeq("100100022", "101000041")).thenReturn(new SeqResolution(List.of(5)));
        BusArrivalInfo ai = BusArrivalInfoFixtures.arrivalWithSectionTime(300, 900);
        when(busPort.getArrInfoByStop(eq("101000041"), eq("100100022"), eq(5), anyLong()))
                .thenReturn(BusQueryResult.ok(List.of(ai)));

        Optional<RealtimeLegTiming> r = lookup.timingFor(busLeg(), null, base);

        assertThat(r).isPresent();
        assertThat(r.get().realtimeDepartureAt()).isEqualTo(base.plusSeconds(300));
        assertThat(r.get().realtimeArrivalAt()).isNull();
        assertThat(r.get().source()).isEqualTo(ActualSource.REALTIME);
        assertThat(r.get().cancelled()).isFalse();
        assertThat(r.get().observedAt()).isEqualTo(base);
    }

    @Test
    @DisplayName("미매핑(resolve empty) → empty")
    void notResolvable() {
        enable();
        when(identity.resolve(any(), any())).thenReturn(Optional.empty());
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }

    @Test
    @DisplayName("지하철 identity → empty (버스 전용, 지하철 timing 은 후속)")
    void subwayDeferred() {
        enable();
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulSubwayIdentity("1004000427", "1004", 3.0)));
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }

    @Test
    @DisplayName("seq 단일 확정 실패 → empty (getArrInfoByStop 호출 불가)")
    void seqNotSingle() {
        enable();
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulBusIdentity("101000041", "100100022", "143")));
        when(stopResolver.resolveSeq(any(), any())).thenReturn(new SeqResolution(List.of(5, 9)));
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }

    @Test
    @DisplayName("실시간 조회 BLOCKED/LIMITED/ERROR → empty(정적 fallback)")
    void portNotOk() {
        enable();
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulBusIdentity("101000041", "100100022", "143")));
        when(stopResolver.resolveSeq(any(), any())).thenReturn(new SeqResolution(List.of(5)));
        when(busPort.getArrInfoByStop(any(), any(), anyInt(), anyLong())).thenReturn(BusQueryResult.blocked());
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }

    @Test
    @DisplayName("조회 OK 지만 버스 없음(빈 리스트) → empty")
    void portEmpty() {
        enable();
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulBusIdentity("101000041", "100100022", "143")));
        when(stopResolver.resolveSeq(any(), any())).thenReturn(new SeqResolution(List.of(5)));
        when(busPort.getArrInfoByStop(any(), any(), anyInt(), anyLong())).thenReturn(BusQueryResult.ok(List.of()));
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }

    @Test
    @DisplayName("기능 비활성(transfer-arrival.enabled=false) → empty(resolver 미호출)")
    void disabled() {
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }

    @Test
    @DisplayName("OTP 실시간 전용 킬스위치 off(realtime-enabled=false) → empty (transfer-arrival on 이어도)")
    void otpRealtimeDisabledIndependently() {
        props.setEnabled(true);
        otpProps.setRealtimeEnabled(false); // OTP 라우팅과 분리된 독립 레버
        assertThat(lookup.timingFor(busLeg(), null, base)).isEmpty();
    }
}
