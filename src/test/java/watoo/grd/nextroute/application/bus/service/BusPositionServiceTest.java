package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.exception.BusApiBlockedException;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.repository.BusPositionRawRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.common.lock.FixedLeaderLease;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BusPositionServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 6, 5, 10, 0, 0);

	@Mock BusApiPort busApiPort;
	@Mock BusRouteRepository busRouteRepository;
	@Mock BusPositionRawRepository busPositionRawRepository;
	@Mock TargetRouteProvider targetRouteProvider;
	@Mock BusApiQuotaPort quota;
	private final FixedLeaderLease leaderLease = FixedLeaderLease.leader();
	@Mock BusRealtimeCacheWritePort realtimeCacheWrite;

	private final Clock clock = Clock.fixed(FIXED_NOW.atZone(KST).toInstant(), KST);
	private BusPositionService service;

	@BeforeEach
	void setUp() {
		service = new BusPositionService(busApiPort, busRouteRepository, busPositionRawRepository, targetRouteProvider, quota, leaderLease, clock, realtimeCacheWrite);
	}

	@Test
	void TC_위치응답을_원본필드로_저장하고_호출예산을_기록한다() {
		prepareRoutes(route("100100118", "143"));
		given(quota.tryAcquire("collector-position", 50000)).willReturn(true);
		given(busApiPort.getBusPosByRtid("100100118")).willReturn(List.of(positionInfo()));

		service.execute();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BusPositionRaw>> positionsCaptor =
				(ArgumentCaptor<List<BusPositionRaw>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
		verify(busPositionRawRepository).saveAll(positionsCaptor.capture());
		BusPositionRaw saved = positionsCaptor.getValue().get(0);

		assertThat(saved.getCollectedAt()).isEqualTo(FIXED_NOW);
		assertThat(saved.getRouteId()).isEqualTo("100100118");
		assertThat(saved.getVehicleId()).isEqualTo("veh-1");
		assertThat(saved.getNextStopTime()).isEqualTo(158);
		assertThat(saved.getSectionOrder()).isEqualTo(12);
		assertThat(saved.getSectionDistance()).isEqualTo(0.126);
		assertThat(saved.getRouteDistance()).isEqualTo(16.0);
		assertThat(saved.getStopFlag()).isEqualTo("1");
		assertThat(saved.getSectionId()).isEqualTo("section-1");
		assertThat(saved.getDataTm()).isEqualTo("2026-06-05 10:00:01.0");
		assertThat(saved.getPlainNo()).isEqualTo("서울70사1234");
		assertThat(saved.getBusType()).isEqualTo(1);
		assertThat(saved.getLastStopTime()).isEqualTo(2581);
		assertThat(saved.getLastStopId()).isEqualTo("111000001");
		assertThat(saved.getPosX()).isEqualTo(126.982111);
		assertThat(saved.getPosY()).isEqualTo(37.566611);
		assertThat(saved.getIsFullFlag()).isEqualTo("0");
		assertThat(saved.getIsLastYn()).isEqualTo("1");
		assertThat(saved.getFullSectionDistance()).isEqualTo(0.883);
		assertThat(saved.getNextStopId()).isEqualTo("111000002");
		assertThat(saved.getCongestion()).isEqualTo(4);
		assertThat(saved.getTurnStopId()).isEqualTo("111000099");
		assertThat(saved.getGpsX()).isEqualTo(126.982001);
		assertThat(saved.getGpsY()).isEqualTo(37.566500);
		assertThat(saved.getIsRunYn()).isEqualTo("1");
		verify(quota).tryAcquire("collector-position", 50000);
		// 실시간 캐시 프라이밍(prime-realtime-cache 기본 on)
		verify(realtimeCacheWrite).putPositions("100100118", List.of(positionInfo()));
	}

	@Test
	void TC_예산이_소진되면_남은_노선은_호출하지_않는다() {
		prepareRoutes(route("100100118", "143"), route("100100119", "272"));
		given(quota.tryAcquire("collector-position", 50000)).willReturn(true, false);
		given(busApiPort.getBusPosByRtid("100100118")).willReturn(List.of(positionInfo()));

		service.execute();

		verify(busApiPort).getBusPosByRtid("100100118");
		verify(busApiPort, never()).getBusPosByRtid("100100119");
		verify(quota, times(2)).tryAcquire("collector-position", 50000);
	}

	@Test
	void TC_API_실패도_호출예산에_반영하고_다음노선을_계속한다() {
		prepareRoutes(route("100100118", "143"), route("100100119", "272"));
		given(quota.tryAcquire("collector-position", 50000)).willReturn(true);
		given(busApiPort.getBusPosByRtid("100100118")).willThrow(new IllegalStateException("boom"));
		given(busApiPort.getBusPosByRtid("100100119")).willReturn(List.of());

		service.execute();

		verify(busApiPort).getBusPosByRtid("100100118");
		verify(busApiPort).getBusPosByRtid("100100119");
		// 실패한 호출도 몫 1 — 호출 전 점유
		verify(quota, times(2)).tryAcquire("collector-position", 50000);
		verify(busPositionRawRepository, never()).saveAll(anyList());
		// 실패(예외)·빈 결과 노선은 캐시 프라이밍 안 함
		verifyNoInteractions(realtimeCacheWrite);
	}

	@Test
	void TC_API_차단_상태는_남은_노선을_호출하지_않는다() {
		prepareRoutes(route("100100118", "143"), route("100100119", "272"));
		given(quota.tryAcquire("collector-position", 50000)).willReturn(true);
		given(busApiPort.getBusPosByRtid("100100118"))
				.willThrow(new BusApiBlockedException(
						FIXED_NOW.plusDays(1).atZone(KST).toInstant(),
						"blocked"
				));

		service.execute();

		verify(busApiPort).getBusPosByRtid("100100118");
		verify(busApiPort, never()).getBusPosByRtid("100100119");
		verify(busPositionRawRepository, never()).saveAll(anyList());
	}

	@Test
	void TC_리더가_아니게_되면_몫을_점유하지_않고_남은_노선을_호출하지_않는다() {
		prepareRoutes(route("100100118", "143"), route("100100119", "272"));
		given(quota.tryAcquire("collector-position", 50000)).willReturn(true);
		given(busApiPort.getBusPosByRtid("100100118")).willAnswer(inv -> {
			leaderLease.set(false);
			return List.of();
		});

		service.execute();

		verify(busApiPort).getBusPosByRtid("100100118");
		verify(busApiPort, never()).getBusPosByRtid("100100119");
		verify(quota, times(1)).tryAcquire("collector-position", 50000);
	}

	private void prepareRoutes(BusRoute... routes) {
		List<String> routeNames = List.of("143", "272").subList(0, routes.length);
		given(targetRouteProvider.activeRouteNames()).willReturn(routeNames);
		given(busRouteRepository.findByRouteNameIn(routeNames)).willReturn(List.of(routes));
	}

	private BusRoute route(String routeId, String routeName) {
		return BusRoute.builder()
				.routeId(routeId)
				.routeName(routeName)
				.build();
	}

	private BusPositionInfo positionInfo() {
		return new BusPositionInfo(
				"veh-1",
				158,
				12,
				0.126,
				16.0,
				"1",
				"section-1",
				"2026-06-05 10:00:01.0",
				"서울70사1234",
				1,
				2581,
				"111000001",
				126.982111,
				37.566611,
				"0",
				"1",
				0.883,
				"111000002",
				4,
				"111000099",
				126.982001,
				37.566500,
				"1"
		);
	}
}
