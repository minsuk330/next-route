package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.application.bus.dto.ArrivalScope;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusArrivalSnapshotPort;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures.arrivalInfo;

@ExtendWith(MockitoExtension.class)
class BusArrivalServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 6, 5, 10, 0, 0);
	private static final ArrivalScope SCOPE = new ArrivalScope("100100118", "111000299", 1);

	@Mock BusApiPort busApiPort;
	@Mock BusDataService busDataService;
	@Mock BusCollectorProperties properties;
	@Mock BusApiCallBudget budget;
	@Mock BusArrivalSnapshotPort busArrivalSnapshotPort;

	private final Clock clock = Clock.fixed(FIXED_NOW.atZone(KST).toInstant(), KST);
	private BusArrivalService service;

	@BeforeEach
	void setUp() {
		service = new BusArrivalService(busApiPort, busDataService, properties, budget, busArrivalSnapshotPort, clock);
	}

	@Test
	void TC_새_candidate는_1번_도착정보만_DB저장_없이_Redis_active로_등록한다() {
		prepareRoute();
		noRedisActiveScopes();
		given(busApiPort.getArrInfoByRouteAll("100100118"))
				.willReturn(List.of(arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후")));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(Map.of());

		service.execute();

		ArgumentCaptor<BusArrivalActiveSnapshot> snapshotCaptor =
				ArgumentCaptor.forClass(BusArrivalActiveSnapshot.class);
		verify(busArrivalSnapshotPort).save(snapshotCaptor.capture());
		assertThat(snapshotCaptor.getAllValues())
				.extracting(BusArrivalActiveSnapshot::identityKey)
				.containsExactly("veh:veh-A");
		assertThat(snapshotCaptor.getValue().missedCount()).isZero();
		assertThat(snapshotCaptor.getValue().lifecycleId()).isNotBlank();
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
		verify(busArrivalSnapshotPort, never()).delete("100100118", "111000299", 1, "veh:veh-A");
	}

	@Test
	void TC_출발대기와_2번_candidate는_active로_등록하지_않는다() {
		prepareRoute();
		noRedisActiveScopes();
		given(busApiPort.getArrInfoByRouteAll("100100118"))
				.willReturn(List.of(arrivalInfo("veh-A", "서울71사1111", "출발대기", "veh-B", "서울72사2222", "4분 후")));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(Map.of());

		service.execute();

		verify(busArrivalSnapshotPort, never()).save(any(BusArrivalActiveSnapshot.class));
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
	}

	@Test
	void TC_필터에서_제외된_기존_active는_DB저장_없이_Redis에서_정리한다() {
		prepareRoute();
		noRedisActiveScopes();
		LocalDateTime previousCollectedAt = FIXED_NOW.minusMinutes(1);
		BusArrivalCandidate previousOrder2 = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				previousCollectedAt
		).get(1);
		BusArrivalActiveSnapshot snapshotB = new BusArrivalActiveSnapshot(
				previousOrder2,
				previousCollectedAt.minusMinutes(1),
				previousCollectedAt,
				previousCollectedAt,
				1,
				"lc-B"
		);
		Map<String, BusArrivalActiveSnapshot> previousActive = new LinkedHashMap<>();
		previousActive.put(snapshotB.identityKey(), snapshotB);

		given(busApiPort.getArrInfoByRouteAll("100100118"))
				.willReturn(List.of(arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-C", "서울73사3333", "6분 후")));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(previousActive);

		service.execute();

		verify(busArrivalSnapshotPort).delete("100100118", "111000299", 1, "veh:veh-B");
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
	}

	@Test
	void TC_첫번째_미관측_candidate는_DB저장_없이_missed_count만_증가한다() {
		prepareRoute();
		noRedisActiveScopes();
		LocalDateTime previousCollectedAt = FIXED_NOW.minusMinutes(1);
		List<BusArrivalCandidate> previousCandidates = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				previousCollectedAt
		);
		BusArrivalActiveSnapshot snapshotA = new BusArrivalActiveSnapshot(
				previousCandidates.get(0),
				previousCollectedAt.minusMinutes(2),
				previousCollectedAt,
				previousCollectedAt,
				0,
				"lc-A"
		);
		Map<String, BusArrivalActiveSnapshot> previousActive = new LinkedHashMap<>();
		previousActive.put(snapshotA.identityKey(), snapshotA);

		BusArrivalInfo current = arrivalInfo("veh-B", "서울72사2222", "2분 후", "veh-C", "서울73사3333", "6분 후");
		given(busApiPort.getArrInfoByRouteAll("100100118")).willReturn(List.of(current));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(previousActive);

		service.execute();

		ArgumentCaptor<BusArrivalActiveSnapshot> savedCaptor =
				ArgumentCaptor.forClass(BusArrivalActiveSnapshot.class);
		verify(busArrivalSnapshotPort, times(2)).save(savedCaptor.capture());
		BusArrivalActiveSnapshot missedA = savedCaptor.getAllValues().stream()
				.filter(snapshot -> snapshot.identityKey().equals("veh:veh-A"))
				.findFirst()
				.orElseThrow();
		assertThat(missedA.missedCount()).isEqualTo(1);
		assertThat(missedA.lastSeenAt()).isEqualTo(previousCollectedAt);
		assertThat(missedA.lifecycleId()).isEqualTo("lc-A");
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
		verify(busArrivalSnapshotPort, never()).delete("100100118", "111000299", 1, "veh:veh-A");
	}

	@Test
	void TC_두번째_연속_미관측_candidate는_마지막_snapshot을_DB저장하고_Redis에서_제거한다() {
		prepareRoute();
		noRedisActiveScopes();
		LocalDateTime previousCollectedAt = FIXED_NOW.minusMinutes(1);
		List<BusArrivalCandidate> previousCandidates = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				previousCollectedAt
		);
		BusArrivalActiveSnapshot snapshotA = new BusArrivalActiveSnapshot(
				previousCandidates.get(0),
				previousCollectedAt.minusMinutes(2),
				previousCollectedAt,
				previousCollectedAt,
				1,
				"lc-A"
		);
		BusArrivalActiveSnapshot snapshotB = new BusArrivalActiveSnapshot(
				BusArrivalCandidate.from(
						arrivalInfo("veh-B", "서울72사2222", "2분 후", "veh-C", "서울73사3333", "6분 후"),
						previousCollectedAt
				).get(0),
				previousCollectedAt.minusMinutes(1),
				previousCollectedAt,
				previousCollectedAt,
				0,
				"lc-B"
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
		assertThat(finalized.getLifecycleId()).isEqualTo("lc-A");
		assertThat(finalized.getCollectedAt()).isEqualTo(previousCollectedAt);
		assertThat(finalized.getFinalizedAt()).isEqualTo(FIXED_NOW);
		assertThat(finalized.getFirstSeenAt()).isEqualTo(previousCollectedAt.minusMinutes(2));

		ArgumentCaptor<BusArrivalActiveSnapshot> savedCaptor =
				ArgumentCaptor.forClass(BusArrivalActiveSnapshot.class);
		verify(busArrivalSnapshotPort).save(savedCaptor.capture());
		BusArrivalActiveSnapshot refreshedB = savedCaptor.getValue();
		assertThat(refreshedB.firstSeenAt()).isEqualTo(snapshotB.firstSeenAt());
		assertThat(refreshedB.candidate().arrivalOrder()).isEqualTo(1);
		assertThat(refreshedB.missedCount()).isZero();
		assertThat(refreshedB.lifecycleId()).isEqualTo("lc-B");

		verify(busArrivalSnapshotPort).delete("100100118", "111000299", 1, "veh:veh-A");
		verify(busArrivalSnapshotPort, never()).delete("100100118", "111000299", 1, "veh:veh-B");
	}

	@Test
	void TC_응답에서_사라진_scope도_Redis_active를_통해_미관측_처리한다() {
		prepareRoute();
		LocalDateTime recent = FIXED_NOW.minusMinutes(1);
		BusArrivalCandidate previousA = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				recent
		).get(0);
		BusArrivalActiveSnapshot snapshotA = new BusArrivalActiveSnapshot(
				previousA, recent.minusMinutes(2), recent, recent, 0, "lc-A"
		);

		// 노선 응답이 통째로 비어도(빈 응답) Redis active scope를 통해 reconcile 대상에 포함된다.
		given(busApiPort.getArrInfoByRouteAll("100100118")).willReturn(List.of());
		given(busArrivalSnapshotPort.findActiveScopes("100100118")).willReturn(Set.of(SCOPE));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(Map.of(snapshotA.identityKey(), snapshotA));

		service.execute();

		ArgumentCaptor<BusArrivalActiveSnapshot> savedCaptor =
				ArgumentCaptor.forClass(BusArrivalActiveSnapshot.class);
		verify(busArrivalSnapshotPort).save(savedCaptor.capture());
		assertThat(savedCaptor.getValue().identityKey()).isEqualTo("veh:veh-A");
		assertThat(savedCaptor.getValue().missedCount()).isEqualTo(1);
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
		verify(busArrivalSnapshotPort, never()).delete(anyString(), anyString(), anyInt(), anyString());
	}

	@Test
	void TC_너무_오래_끊긴_active는_finalize하지_않고_폐기한다() {
		prepareRoute();
		LocalDateTime stale = FIXED_NOW.minusMinutes(10);
		BusArrivalCandidate previousA = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				stale
		).get(0);
		BusArrivalActiveSnapshot snapshotA = new BusArrivalActiveSnapshot(
				previousA, stale.minusMinutes(2), stale, stale, 1, "lc-A"
		);

		given(busApiPort.getArrInfoByRouteAll("100100118")).willReturn(List.of());
		given(busArrivalSnapshotPort.findActiveScopes("100100118")).willReturn(Set.of(SCOPE));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(Map.of(snapshotA.identityKey(), snapshotA));

		service.execute();

		verify(busArrivalSnapshotPort).delete("100100118", "111000299", 1, "veh:veh-A");
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
		verify(busArrivalSnapshotPort, never()).save(any(BusArrivalActiveSnapshot.class));
	}

	@Test
	void TC_예산_소진_route는_API없이_stale_active만_폐기한다() {
		BusRoute route = BusRoute.builder().routeId("100100118").routeName("753").build();
		given(properties.getTargetRouteNames()).willReturn(List.of("753"));
		given(busDataService.findRoutesByNames(List.of("753"))).willReturn(List.of(route));
		given(properties.getDailyBudget()).willReturn(1000);
		// 시작 가드는 통과, 루프 내 route 단위 체크에서 소진
		given(budget.canMakeCall(1000)).willReturn(true, false);

		LocalDateTime stale = FIXED_NOW.minusMinutes(10);
		BusArrivalCandidate previousA = BusArrivalCandidate.from(
				arrivalInfo("veh-A", "서울71사1111", "1분 후", "veh-B", "서울72사2222", "4분 후"),
				stale
		).get(0);
		BusArrivalActiveSnapshot snapshotA = new BusArrivalActiveSnapshot(
				previousA, stale.minusMinutes(2), stale, stale, 1, "lc-A"
		);
		given(busArrivalSnapshotPort.findActiveScopes("100100118")).willReturn(Set.of(SCOPE));
		given(busArrivalSnapshotPort.findActive("100100118", "111000299", 1))
				.willReturn(Map.of(snapshotA.identityKey(), snapshotA));

		service.execute();

		verify(busApiPort, never()).getArrInfoByRouteAll(anyString());
		verify(busArrivalSnapshotPort).delete("100100118", "111000299", 1, "veh:veh-A");
		verify(busDataService, never()).saveAllArrivalCandidates(anyList());
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

	private void noRedisActiveScopes() {
		given(busArrivalSnapshotPort.findActiveScopes("100100118")).willReturn(Set.of());
	}
}
