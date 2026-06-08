package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.bus.dto.BusRidershipFetchResult;
import watoo.grd.nextroute.application.bus.exception.BusApiBlockedException;
import watoo.grd.nextroute.application.bus.service.BusApiCallBudget;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeoulBusApiAdapterTest {

	private static final Clock CLOCK = Clock.fixed(
			LocalDateTime.of(2026, 6, 5, 10, 0, 0).atZone(ClockConfig.KST).toInstant(),
			ClockConfig.KST
	);

	@Mock RestTemplate restTemplate;
	@Mock BusApiCallBudget arrivalBudget;
	@Mock BusApiCallBudget positionBudget;
	SeoulBusApiAdapter adapter;

	@BeforeEach
	void setUp() {
		adapter = new SeoulBusApiAdapter(
				restTemplate,
				new ObjectMapper(),
				CLOCK,
				arrivalBudget,
				positionBudget,
				50000,
				50000,
				"bus-key",
				"http://ws.bus.go.kr/api/rest",
				"route-key",
				"usage-key",
				"http://openapi.seoul.go.kr:8088"
		);
	}

	@Test
	void TC_CardBusTimeNew는_전체페이지를_조회하고_시간대별_승하차를_합산한다() {
		when(restTemplate.getForObject(any(URI.class), eq(String.class)))
				.thenReturn("""
						{
						  "CardBusTimeNew": {
						    "list_total_count": 2,
						    "RESULT": {"CODE": "INFO-000", "MESSAGE": "정상 처리되었습니다"},
						    "row": [
						      {
						        "USE_YM": "202603",
						        "RTE_NO": "143",
						        "RTE_NM": "143번",
						        "HR_0_GET_ON_TNOPE": "10",
						        "HR_0_GET_OFF_TNOPE": "3",
						        "HR_1_GET_ON_NOPE": "20",
						        "HR_1_GET_OFF_NOPE": "4",
						        "HR_23_GET_ON_TNOPE": "30",
						        "HR_23_GET_OFF_TNOPE": "5"
						      }
						    ]
						  }
						}
						""")
				.thenReturn("""
						{
						  "CardBusTimeNew": {
						    "list_total_count": 2,
						    "RESULT": {"CODE": "INFO-000", "MESSAGE": "정상 처리되었습니다"},
						    "row": [
						      {
						        "USE_YM": "202603",
						        "RTE_NO": "271",
						        "RTE_NM": "271번",
						        "HR_5_GET_ON_TNOPE": 7,
						        "HR_5_GET_OFF_TNOPE": 8
						      }
						    ]
						  }
						}
						""");

		BusRidershipFetchResult result = adapter.getBusRidershipByMonth("202603", 1);

		assertThat(result.totalRowCount()).isEqualTo(2);
		assertThat(result.rows()).hasSize(2);
		assertThat(result.rows().get(0).routeNo()).isEqualTo("143");
		assertThat(result.rows().get(0).getOnTotal()).isEqualTo(60);
		assertThat(result.rows().get(0).getOffTotal()).isEqualTo(12);
		assertThat(result.rows().get(1).routeNo()).isEqualTo("271");
		assertThat(result.rows().get(1).getOnTotal()).isEqualTo(7);
		assertThat(result.rows().get(1).getOffTotal()).isEqualTo(8);

		ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
		verify(restTemplate, times(2)).getForObject(uriCaptor.capture(), eq(String.class));
		List<String> uris = uriCaptor.getAllValues().stream().map(URI::toString).toList();
		assertThat(uris.get(0)).contains("/usage-key/json/CardBusTimeNew/1/1/202603/");
		assertThat(uris.get(1)).contains("/usage-key/json/CardBusTimeNew/2/2/202603/");
	}

	@Test
	void TC_CardBusTimeNew_비정상_RESULT는_예외를_던진다() {
		when(restTemplate.getForObject(any(URI.class), eq(String.class)))
				.thenReturn("""
						{
						  "CardBusTimeNew": {
						    "RESULT": {"CODE": "ERROR-500", "MESSAGE": "실패"}
						  }
						}
						""");

		assertThatThrownBy(() -> adapter.getBusRidershipByMonth("202603", 1000))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CardBusTimeNew");

		verify(restTemplate, times(3)).getForObject(any(URI.class), eq(String.class));
	}

	@Test
	void TC_도착정보는_노선번호_정류소명_첫막차시간을_매핑한다() {
		when(restTemplate.getForObject(any(URI.class), eq(String.class)))
				.thenReturn("""
						<ServiceResult>
						  <msgHeader>
						    <headerCd>0</headerCd>
						    <headerMsg>정상적으로 처리되었습니다.</headerMsg>
						    <itemCount>1</itemCount>
						  </msgHeader>
						  <msgBody>
						    <itemList>
						      <arrmsg1>출발대기</arrmsg1>
						      <arrmsg2>출발대기</arrmsg2>
						      <arsId>12390</arsId>
						      <busRouteAbrv>753</busRouteAbrv>
						      <busRouteId>100100118</busRouteId>
						      <firstTm>20230927041000</firstTm>
						      <lastTm>20230927222000</lastTm>
						      <mkTm>2023-09-27 16:51:36.0</mkTm>
						      <rtNm>753</rtNm>
						      <stId>111000299</stId>
						      <stNm>구상동사거리</stNm>
						    </itemList>
						  </msgBody>
						</ServiceResult>
						""");

		var result = adapter.getArrInfoByRouteAll("100100118");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).routeId()).isEqualTo("100100118");
		assertThat(result.get(0).routeAbrv()).isEqualTo("753");
		assertThat(result.get(0).routeName()).isEqualTo("753");
		assertThat(result.get(0).stopId()).isEqualTo("111000299");
		assertThat(result.get(0).stopName()).isEqualTo("구상동사거리");
		assertThat(result.get(0).firstBusTime()).isEqualTo("20230927041000");
		assertThat(result.get(0).lastBusTime()).isEqualTo("20230927222000");
	}

	@Test
	void TC_버스_API_호출제한_코드_7이면_다음_KST_자정까지_호출을_차단한다() {
		when(restTemplate.getForObject(any(URI.class), eq(String.class)))
				.thenReturn("""
						<ServiceResult>
						  <msgHeader>
						    <headerCd>7</headerCd>
						    <headerMsg>일일 호출건수를 초과하였습니다.</headerMsg>
						    <itemCount>0</itemCount>
						  </msgHeader>
						  <msgBody />
						</ServiceResult>
						""");

		assertThatThrownBy(() -> adapter.getArrInfoByRouteAll("100100118"))
				.isInstanceOf(BusApiBlockedException.class)
				.satisfies(error -> assertThat(((BusApiBlockedException) error).blockedUntil())
						.isEqualTo(LocalDateTime.of(2026, 6, 6, 0, 0, 0)
								.atZone(ClockConfig.KST)
								.toInstant()));

		assertThatThrownBy(() -> adapter.getBusPosByRtid("100100118"))
				.isInstanceOf(BusApiBlockedException.class);

		verify(restTemplate, times(1)).getForObject(any(URI.class), eq(String.class));
		verify(arrivalBudget, times(2)).getUsed();
		verify(positionBudget, times(2)).getUsed();
	}

	@Test
	void TC_버스위치정보는_응답필드를_모두_매핑한다() {
		when(restTemplate.getForObject(any(URI.class), eq(String.class)))
				.thenReturn("""
						<ServiceResult>
						  <msgHeader>
						    <headerCd>0</headerCd>
						    <headerMsg>정상적으로 처리되었습니다.</headerMsg>
						    <itemCount>1</itemCount>
						  </msgHeader>
						  <msgBody>
						    <itemList>
						      <nextStTm>158</nextStTm>
						      <sectOrd>12</sectOrd>
						      <sectDist>0.126</sectDist>
						      <rtDist>16</rtDist>
						      <stopFlag>1</stopFlag>
						      <sectionId>100100001</sectionId>
						      <dataTm>2026-06-06 13:00:01.0</dataTm>
						      <vehId>123456789</vehId>
						      <plainNo>서울70사1234</plainNo>
						      <busType>1</busType>
						      <lastStTm>2581</lastStTm>
						      <lastStnId>111000001</lastStnId>
						      <posX>126.982111</posX>
						      <posY>37.566611</posY>
						      <isFullFlag>0</isFullFlag>
						      <islastyn>1</islastyn>
						      <fullSectDist>0.883</fullSectDist>
						      <nextStId>111000002</nextStId>
						      <congetion>4</congetion>
						      <trnstnid>111000099</trnstnid>
						      <gpsX>126.982001</gpsX>
						      <gpsY>37.566500</gpsY>
						      <isrunyn>1</isrunyn>
						    </itemList>
						  </msgBody>
						</ServiceResult>
						""");

		var result = adapter.getBusPosByRtid("100100118");

		assertThat(result).hasSize(1);
		var info = result.get(0);
		assertThat(info.vehicleId()).isEqualTo("123456789");
		assertThat(info.nextStopTime()).isEqualTo(158);
		assertThat(info.sectionOrder()).isEqualTo(12);
		assertThat(info.sectionDistance()).isEqualTo(0.126);
		assertThat(info.routeDistance()).isEqualTo(16.0);
		assertThat(info.stopFlag()).isEqualTo("1");
		assertThat(info.sectionId()).isEqualTo("100100001");
		assertThat(info.dataTm()).isEqualTo("2026-06-06 13:00:01.0");
		assertThat(info.plainNo()).isEqualTo("서울70사1234");
		assertThat(info.busType()).isEqualTo(1);
		assertThat(info.lastStopTime()).isEqualTo(2581);
		assertThat(info.lastStopId()).isEqualTo("111000001");
		assertThat(info.posX()).isEqualTo(126.982111);
		assertThat(info.posY()).isEqualTo(37.566611);
		assertThat(info.isFullFlag()).isEqualTo("0");
		assertThat(info.isLastYn()).isEqualTo("1");
		assertThat(info.fullSectionDistance()).isEqualTo(0.883);
		assertThat(info.nextStopId()).isEqualTo("111000002");
		assertThat(info.congestion()).isEqualTo(4);
		assertThat(info.turnStopId()).isEqualTo("111000099");
		assertThat(info.gpsX()).isEqualTo(126.982001);
		assertThat(info.gpsY()).isEqualTo(37.566500);
		assertThat(info.isRunYn()).isEqualTo("1");
	}
}
