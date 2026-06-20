package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.BusApiResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.BusArrivalItem;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.BusPositionItem;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static watoo.grd.nextroute.common.util.ParseUtils.parseDouble;
import static watoo.grd.nextroute.common.util.ParseUtils.parseInteger;

/**
 * 경로검색 전용 버스 실시간 조회 어댑터.
 * 재시도 없음, ~1.5s timeout. 검색 레이턴시 보호용.
 * 기존 SeoulBusApiAdapter(3회 재시도/30s)는 그대로 유지.
 *
 * <p>collector와 동일 provider key를 쓰므로 공유 {@link BusApiBreakerPort}를 함께 본다.
 * 차단 중이면 외부 호출을 생략하고 빈 결과를 반환(검색은 깨지 않음). error code 7 수신 시 공유 차단을 건다.
 */
@Slf4j
@Component
public class SearchTimeBusAdapter implements SearchTimeBusQueryPort {

    private static final String API_LIMIT_EXCEEDED_CODE = "7";

    private final RestClient restClient;
    private final XmlMapper xmlMapper;
    private final String apiKey;
    private final String baseUrl;
    private final BusApiBreakerPort breaker;
    private final Clock clock;

    public SearchTimeBusAdapter(
            @Qualifier("busRealtimeRestClient") RestClient restClient,
            BusApiBreakerPort breaker,
            Clock clock,
            @Value("${seoul.api.bus-key}") String apiKey,
            @Value("${seoul.api.bus-base-url}") String baseUrl) {
        this.restClient = restClient;
        this.breaker = breaker;
        this.clock = clock;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.xmlMapper = new XmlMapper();
        this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.xmlMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    @Override
    public List<BusArrivalInfo> getArrInfoByStop(String stopId) {
        URI uri = URI.create(baseUrl + "/arrive/getArrInfoByStId"
                + "?serviceKey=" + apiKey
                + "&stId=" + stopId);
        return callApi(uri, BusArrivalItem.class).stream()
                .map(this::toArrivalInfo)
                .toList();
    }

    @Override
    public List<BusPositionInfo> getBusPosByRtid(String busRouteId) {
        URI uri = URI.create(baseUrl + "/buspos/getBusPosByRtid"
                + "?serviceKey=" + apiKey
                + "&busRouteId=" + busRouteId);
        return callApi(uri, BusPositionItem.class).stream()
                .map(this::toPositionInfo)
                .toList();
    }

    private <T> List<T> callApi(URI uri, Class<T> itemType) {
        // 공유 차단 중이면 외부 호출 생략(검색은 깨지 않음 — 빈 결과)
        Optional<Instant> blocked = breaker.getBlockedUntil();
        if (blocked.isPresent()) {
            log.debug("[SearchTimeAdapter] breaker blocked until {} — skip {}", blocked.get(), uri.getPath());
            return List.of();
        }
        try {
            String xml = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank()) {
                log.warn("[SearchTimeAdapter] Empty response: {}", uri.getPath());
                return List.of();
            }
            JavaType type = xmlMapper.getTypeFactory()
                    .constructParametricType(BusApiResponse.class, itemType);
            BusApiResponse<T> response = xmlMapper.readValue(xml, type);
            if (!response.isSuccess()) {
                String cd = response.getMsgHeader() == null ? null : response.getMsgHeader().getHeaderCd();
                String msg = response.getMsgHeader() == null ? null : response.getMsgHeader().getHeaderMsg();
                if (API_LIMIT_EXCEEDED_CODE.equals(cd == null ? null : cd.trim())) {
                    // 공유 차단(collector·search 함께 다음날 자정까지 차단)
                    breaker.tripUntil(nextMidnight());
                    log.error("[SearchTimeAdapter] API error code 7 — tripping shared breaker [{}]: {}",
                            uri.getPath(), msg);
                } else {
                    log.warn("[SearchTimeAdapter] API error [{}]: {} - {}", uri.getPath(), cd, msg);
                }
                return List.of();
            }
            return response.getItems();
        } catch (Exception e) {
            log.warn("[SearchTimeAdapter] Call failed [{}]: {}", uri.getPath(), e.getMessage());
            throw new RuntimeException("SearchTimeBus API call failed: " + uri.getPath(), e);
        }
    }

    private Instant nextMidnight() {
        return ZonedDateTime.now(clock)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ClockConfig.KST)
                .toInstant();
    }

    private BusArrivalInfo toArrivalInfo(BusArrivalItem item) {
        return new BusArrivalInfo(
                item.getBusRouteId(), item.getBusRouteAbrv(), item.getRtNm(),
                item.getStId(), item.getArsId(), item.getStNm(),
                parseInteger(item.getStaOrd()), item.getDir(),
                parseInteger(item.getRouteType()), parseInteger(item.getTerm()),
                item.getMkTm(), item.getDeTourAt(), item.getNextBus(),
                item.getFirstTm(), item.getLastTm(),
                item.getArrmsg1(), item.getVehId1(), item.getPlainNo1(),
                parseInteger(item.getBusType1()), parseInteger(item.getSectOrd1()),
                item.getStationNm1(), item.getIsArrive1(), item.getIsLast1(), item.getFull1(),
                parseInteger(item.getExps1()), parseInteger(item.getKals1()),
                parseInteger(item.getNeus1()), parseInteger(item.getGoal1()),
                parseDouble(item.getAvgCf1()), parseDouble(item.getExpCf1()),
                parseDouble(item.getKalCf1()), parseDouble(item.getNeuCf1()),
                parseInteger(item.getTraTime1()), parseDouble(item.getTraSpd1()),
                parseInteger(item.getBrdrdeNum1()), parseInteger(item.getBrerdDiv1()),
                parseInteger(item.getRerideNum1()), parseInteger(item.getRerdieDiv1()),
                item.getNstnId1(), parseInteger(item.getNstnOrd1()),
                parseInteger(item.getNstnSec1()), parseInteger(item.getNstnSpd1()),
                parseInteger(item.getNmainOrd1()), parseInteger(item.getNmainSec1()),
                item.getNmainStnid1(), parseInteger(item.getNmain2Ord1()),
                parseInteger(item.getNamin2Sec1()), item.getNmain2Stnid1(),
                parseInteger(item.getNmain3Ord1()), parseInteger(item.getNmain3Sec1()),
                item.getNmain3Stnid1(),
                item.getArrmsg2(), item.getVehId2(), item.getPlainNo2(),
                parseInteger(item.getBusType2()), parseInteger(item.getSectOrd2()),
                item.getStationNm2(), item.getIsArrive2(), item.getIsLast2(), item.getFull2(),
                parseInteger(item.getExps2()), parseInteger(item.getKals2()),
                parseInteger(item.getNeus2()), parseInteger(item.getGoal2()),
                parseDouble(item.getAvgCf2()), parseDouble(item.getExpCf2()),
                parseDouble(item.getKalCf2()), parseDouble(item.getNeuCf2()),
                parseInteger(item.getTraTime2()), parseDouble(item.getTraSpd2()),
                parseInteger(item.getBrdrdeNum2()), parseInteger(item.getBrerdDiv2()),
                parseInteger(item.getRerideNum2()), parseInteger(item.getRerdieDiv2()),
                item.getNstnId2(), parseInteger(item.getNstnOrd2()),
                parseInteger(item.getNstnSec2()), parseInteger(item.getNstnSpd2()),
                parseInteger(item.getNmainOrd2()), parseInteger(item.getNmainSec2()),
                item.getNmainStnid2(), parseInteger(item.getNmain2Ord2()),
                parseInteger(item.getNamin2Sec2()), item.getNmain2Stnid2(),
                parseInteger(item.getNmain3Ord2()), parseInteger(item.getNmain3Sec2()),
                item.getNmain3Stnid2()
        );
    }

    private BusPositionInfo toPositionInfo(BusPositionItem item) {
        return new BusPositionInfo(
                item.getVehId(), parseInteger(item.getNextStTm()),
                parseInteger(item.getSectOrd()), parseDouble(item.getSectDist()),
                parseDouble(item.getRtDist()), item.getStopFlag(), item.getSectionId(),
                item.getDataTm(), item.getPlainNo(), parseInteger(item.getBusType()),
                parseInteger(item.getLastStTm()), item.getLastStnId(),
                parseDouble(item.getPosX()), parseDouble(item.getPosY()),
                item.getIsFullFlag(), item.getIslastyn(),
                parseDouble(item.getFullSectDist()), item.getNextStId(),
                parseInteger(item.getCongetion()), item.getTrnstnid(),
                parseDouble(item.getGpsX()), parseDouble(item.getGpsY()),
                item.getIsrunyn()
        );
    }
}
