package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 스냅샷에 얹는 예측 값들.
 *
 * <p>기본 스냅샷과 나눠 둔 이유는 <b>계산 비용과 트랜잭션 경계</b>가 다르기 때문이다. 기본
 * 스냅샷은 저장된 값을 읽는 것뿐이라 여정을 만드는 트랜잭션 안에서도 만들 수 있다. 반면 이
 * 값들은 실시간 도착 조회가 필요해 트랜잭션 밖에서 계산해야 한다.
 *
 * <p>모르는 값은 null 이다. 채워 넣지 않는다 — 그럴듯하게 틀린 시각은 사용자를 엉뚱한 때에
 * 움직이게 한다.
 *
 * @param currentLeg     지금 지나는 단계의 종료 예측
 * @param alighting      버스에서 내릴 시점. 버스를 타고 있지 않으면 null
 * @param activeTransfer 다음에 맞이할 환승. 남은 환승이 없으면 null
 * @param journeyEta     목적지 도착 예측. 중간에 모르는 값이 있으면 null
 * @param nextAction     사용자가 지금 할 일
 */
public record JourneyEtaView(
        CurrentLegEta currentLeg,
        AlightingEta alighting,
        ActiveTransferEta activeTransfer,
        Instant journeyEta,
        NextAction nextAction
) {

    /**
     * 현재 단계 종료 예측.
     *
     * @param arrivalAt        절대 시각. 앱이 이것으로 초 단위 countdown 을 돌린다
     * @param remainingSeconds 관측 시점 기준 남은 초
     * @param observedAt       계산의 기준이 된 관측 시각. 앱이 신선도를 판단한다
     */
    public record CurrentLegEta(
            Instant arrivalAt,
            Integer remainingSeconds,
            CurrentLegArrival.Source source,
            CurrentLegArrival.Quality quality,
            Instant observedAt
    ) {
    }

    /**
     * 하차 예측.
     *
     * <p>체크포인트({@code PREPARE_TO_ALIGHT}·{@code ALIGHT_NOW})는 담지 않는다. 그 판정과 표시는
     * 앱의 네이티브 위치 엔진이 소유한다 — 서버가 같이 판정하면 두 곳이 다른 시점에 알린다.
     * 서버는 남은 정류장과 예상 시각까지만 준다.
     */
    public record AlightingEta(
            Integer remainingStops,
            Instant predictedAlightingAt,
            CurrentLegArrival.Source source,
            CurrentLegArrival.Quality quality
    ) {
    }

    /**
     * 다음 환승의 위험도.
     *
     * @param successProbability 성공 확률. 캘리브레이션이 준비되기 전에는 null 이다
     */
    public record ActiveTransferEta(
            int transferIndex,
            TransferStatus status,
            Instant userReadyAt,
            Instant nextBusArrivalAt,
            Long transferSlackSeconds,
            CurrentLegArrival.Quality confidence,
            BigDecimal successProbability
    ) {
    }

    /**
     * 사용자가 지금 할 일.
     *
     * <p>앱이 단계 종류와 환승 상태를 조합해 문구를 만들지 않아도 되게 서버가 한 가지로 정해
     * 내려준다. 두 곳이 각자 정하면 같은 상황에서 화면과 알림이 다른 말을 한다.
     *
     * @param type     할 일의 종류
     * @param legIndex 그 일이 일어나는 단계
     * @param at       그 일이 예상되는 시각. 모르면 null
     */
    public record NextAction(Type type, int legIndex, JourneyLegType legType, Instant at) {

        public enum Type {
            /** 정류장·역까지 걸어간다. */
            WALK_TO_STOP,
            /** 목적지까지 걸어간다. */
            WALK_TO_DESTINATION,
            /** 차량을 기다린다. */
            WAIT_FOR_VEHICLE,
            /** 타고 간다. */
            RIDE,
            /** 내릴 준비를 한다. 환승 위험이 올라간 상태다. */
            PREPARE_TRANSFER,
            /** 여정이 끝났다. */
            NONE
        }
    }
}
