package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusArrivalSnapshotPort;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures.arrivalInfo;

@ExtendWith(MockitoExtension.class)
class BusArrivalServiceTest {

	@Mock BusApiPort busApiPort;
	@Mock BusDataService busDataService;
	@Mock BusCollectorProperties properties;
	@Mock BusApiCallBudget budget;
	@Mock BusArrivalSnapshotPort busArrivalSnapshotPort;
	@InjectMocks BusArrivalService service;

	@Test
	void TC_새_candidate는_DB저장_없이_Redis_active로_등록한다() {
		prepareRoute();
		given(busApiPort.getArrInfoByRouteAll("100100118"))
				.willReturn(List.of(arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후")));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(Map.of());

		service.execute();

		ArgumentCaptor<BusArrivalActiveSnapshot> snapshotCaptor =
				ArgumentCaptor.forClass(BusArrivalActiveSnapshot.class);
		verify(busArrivalSnapshotPort, times(2)).save(snapshotCaptor.capture());
		assertThat(snapshotCaptor.getAllValues())
				.extracting(BusArrivalActiveSnapshot::identityKey)
				.containsExactly("veh:veh-A", "veh:veh-B");
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
		verify(busArrivalSnapshotPort, never()).delete("100100118", "111000299", 1, "veh:veh-A");
	}

	@Test
	void TC_이전_active에서_사라진_candidate는_마지막_snapshot을_DB저장하고_Redis에서_제거한다() {
		prepareRoute();
		LocalDateTime previousCollectedAt = LocalDateTime.of(2026, 6, 4, 9, 59);
		List<BusArrivalCandidate> previousCandidates = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				previousCollectedAt
		);
		BusArrivalActiveSnapshot snapshotA = new BusArrivalActiveSnapshot(
				previousCandidates.get(0),
				previousCollectedAt.minusMinutes(2),
				previousCollectedAt,
				previousCollectedAt
		);
		BusArrivalActiveSnapshot snapshotB = new BusArrivalActiveSnapshot(
				previousCandidates.get(1),
				previousCollectedAt.minusMinutes(1),
				previousCollectedAt,
				previousCollectedAt
		);
		Map<String, BusArrivalActiveSnapshot> previousActive = new LinkedHashMap<>();
		previousActive.put(snapshotA.identityKey(), snapshotA);
		previousActive.put(snapshotB.identityKey(), snapshotB);

		BusArrivalInfo current = arrivalInfo("veh-B", "서울72사2222", "2분 후", "veh-C", "서울73사3333", "6분 후");
		given(busApiPort.getArrInfoByRouteAll("100100118")).willReturn(List.of(current));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(previousActive);

		service.execute();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BusArrivalCandidateRaw>> finalizedCaptor =
				(ArgumentCaptor<List<BusArrivalCandidateRaw>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
		verify(busDataService).saveAllArrivalCandidates(finalizedCaptor.capture());
		assertThat(finalizedCaptor.getValue()).hasSize(1);
		BusArrivalCandidateRaw finalized = finalizedCaptor.getValue().get(0);
		assertThat(finalized.getRouteId()).isEqualTo("100100118");
		assertThat(finalized.getStopId()).isEqualTo("111000299");
		assertThat(finalized.getArrivalOrder()).isEqualTo(1);
		assertThat(finalized.getVehicleIdentity()).isEqualTo("veh-A");
		assertThat(finalized.getVehicleIdentityType()).isEqualTo("VEH_ID");
		assertThat(finalized.getArrivalMsg()).isEqualTo("1분 후");
		assertThat(finalized.getPredictTime()).isEqualTo(60);
		assertThat(finalized.getCollectedAt()).isEqualTo(previousCollectedAt);
		assertThat(finalized.getFirstSeenAt()).isEqualTo(previousCollectedAt.minusMinutes(2));

		ArgumentCaptor<BusArrivalActiveSnapshot> savedCaptor =
				ArgumentCaptor.forClass(BusArrivalActiveSnapshot.class);
		verify(busArrivalSnapshotPort, times(2)).save(savedCaptor.capture());
		BusArrivalActiveSnapshot refreshedB = savedCaptor.getAllValues().stream()
				.filter(snapshot -> snapshot.identityKey().equals("veh:veh-B"))
				.findFirst()
				.orElseThrow();
		assertThat(refreshedB.firstSeenAt()).isEqualTo(snapshotB.firstSeenAt());
		assertThat(refreshedB.candidate().arrivalOrder()).isEqualTo(1);

		verify(busArrivalSnapshotPort).delete("100100118", "111000299", 1, "veh:veh-A");
		verify(busArrivalSnapshotPort, never()).delete("100100118", "111000299", 1, "veh:veh-B");
	}

	@Test
	void TC_API_호출_실패_route는_active_snapshot을_종료하지_않는다() {
		prepareRoute();
		given(busApiPort.getArrInfoByRouteAll("100100118"))
				.willThrow(new RuntimeException("api down"));

		service.execute();

		verifyNoInteractions(busArrivalSnapshotPort);
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
		verify(budget).recordCall();
	}

	private void prepareRoute() {
		BusRoute route = BusRoute.builder()
				.routeId("100100118")
				.routeName("753")
				.build();

		given(properties.getTargetRouteNames()).willReturn(List.of("753"));
		given(busDataService.findRoutesByNames(List.of("753"))).willReturn(List.of(route));
		given(properties.getDailyBudget()).willReturn(1000);
		given(budget.canMakeCall(1000)).willReturn(true);
	}
}
