package watoo.grd.nextroute.domain.journey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 갈아타는 이음매 하나. 앞 단계에서 내려 다음 교통수단을 타기까지의 연결이다.
 *
 * <p>여정에서 실제로 깨지는 지점은 대개 여기다. 그래서 단계와 별개로 따로 행을 둔다 —
 * "몇 분 남았는지"와 "놓칠 것 같은지"를 이음매 단위로 계산하고 알려야 하기 때문이다.
 *
 * <p>이번 단계는 이음매의 <b>형태</b>(어느 단계에서 어느 단계로, 사이에 걷기가 있는지)만
 * 저장한다. 시각과 위험도는 Phase 3 의 평가가 채우고, 지금은 {@link TransferStatus#UNKNOWN}
 * 으로 들어간다.
 */
@Entity
@Table(name = "transfer_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferConnection extends BaseEntity {

    @Column(nullable = false)
    private UUID journeyId;

    @Column(nullable = false)
    private int routeRevision;

    @Column(nullable = false)
    private int transferIndex;

    /** 내리는 단계의 순번. */
    @Column(nullable = false)
    private int fromLegIndex;

    /**
     * 사이에 끼는 걷기 단계의 순번. 같은 정류장에서 갈아타면 비어 있다.
     *
     * <p>"걷지 않는 환승"을 0초짜리 걷기로 만들지 않는다. 없는 단계를 만들면 사용자가 지나지도
     * 않을 단계를 화면에서 보게 된다.
     */
    private Integer walkLegIndex;

    /** 타는 단계의 순번. */
    @Column(nullable = false)
    private int toLegIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    /** 지금 타고 있는 차량이 환승 지점에 닿는 시각(Phase 3). */
    private Instant currentLegArrivalAt;

    /** 내려서 걸어간 뒤 사용자가 탈 준비가 되는 시각(Phase 3). */
    private Instant userReadyAt;

    /** 갈아탈 차량이 오는 시각(Phase 3). */
    private Instant nextBusArrivalAt;

    /** 준비되는 시각과 차가 오는 시각 사이의 여유. 음수면 이미 늦었다는 뜻이다. */
    private Integer transferSlackSeconds;

    @Column(precision = 4, scale = 3)
    private BigDecimal successProbability;

    /** 위 값들을 무엇으로 계산했는지(실시간·시간표·모델 등). Phase 3 이 어휘를 정한다. */
    @Column(length = 20)
    private String source;

    @Column(length = 20)
    private String confidence;

    private Instant calculatedAt;

    /** 계산에 쓴 관측값이 만들어진 시각. 계산 시각과 다르면 낡은 데이터로 계산한 것이다. */
    private Instant dataObservedAt;

    @Column(length = 50)
    private String modelVersion;

    /** 마지막으로 알림을 보낸 시점의 상태. 같은 상태로 반복해 알리지 않기 위한 값(Phase 5). */
    @Column(length = 20)
    private String lastNotifiedStatus;

    @Builder
    public TransferConnection(UUID journeyId, int routeRevision, int transferIndex,
                              int fromLegIndex, Integer walkLegIndex, int toLegIndex,
                              TransferStatus status) {
        this.journeyId = journeyId;
        this.routeRevision = routeRevision;
        this.transferIndex = transferIndex;
        this.fromLegIndex = fromLegIndex;
        this.walkLegIndex = walkLegIndex;
        this.toLegIndex = toLegIndex;
        this.status = status;
    }
}
