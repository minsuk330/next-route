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
    private Long journeyId;

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
    public TransferConnection(Long journeyId, int routeRevision, int transferIndex,
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

    /**
     * 같은 환승을 <b>새 개정본으로 옮긴다</b>(재탐색 수락, Phase 6 ⑤).
     *
     * @param keepAssessment 지나온 구간이면 {@code true} — 판정·알림 기록까지 그대로 들고 간다.
     *                       경로가 바뀐 뒤쪽이면 {@code false} — 옛 노선의 예측이고,
     *                       특히 {@code lastNotifiedStatus} 가 남으면 <b>새 위험을 알리지 못한다</b>
     */
    public TransferConnection copyToRevision(int newRouteRevision, boolean keepAssessment) {
        TransferConnection copy = TransferConnection.builder()
                .journeyId(this.journeyId)
                .routeRevision(newRouteRevision)
                .transferIndex(this.transferIndex)
                .fromLegIndex(this.fromLegIndex)
                .walkLegIndex(this.walkLegIndex)
                .toLegIndex(this.toLegIndex)
                .status(keepAssessment ? this.status : TransferStatus.UNKNOWN)
                .build();
        if (!keepAssessment) {
            return copy;
        }
        copy.currentLegArrivalAt = this.currentLegArrivalAt;
        copy.userReadyAt = this.userReadyAt;
        copy.nextBusArrivalAt = this.nextBusArrivalAt;
        copy.transferSlackSeconds = this.transferSlackSeconds;
        copy.successProbability = this.successProbability;
        copy.source = this.source;
        copy.confidence = this.confidence;
        copy.calculatedAt = this.calculatedAt;
        copy.dataObservedAt = this.dataObservedAt;
        copy.modelVersion = this.modelVersion;
        copy.lastNotifiedStatus = this.lastNotifiedStatus;
        return copy;
    }

    /**
     * 위험도 판정 결과를 반영한다.
     *
     * <p>어떤 상태로 갈 수 있는지는 여기서 따지지 않는다. 떨림 방지를 포함한 판정은 여유와 직전
     * 상태를 함께 봐야 하므로 {@code TransferRiskPolicy} 가 정한다.
     *
     * <p>{@code lastNotifiedStatus} 는 건드리지 않는다. 그것은 "알렸는가"의 기록이고 알림을
     * 보내는 쪽(Phase 5)이 소유한다. 여기서 같이 바꾸면 판정만 하고 알림은 안 보낸 상태를
     * 이미 알린 것으로 오해한다.
     */
    public void applyAssessment(TransferStatus status,
                                Instant currentLegArrivalAt,
                                Instant userReadyAt,
                                Instant nextBusArrivalAt,
                                Integer transferSlackSeconds,
                                String source,
                                String confidence,
                                Instant calculatedAt,
                                Instant dataObservedAt) {
        this.status = status;
        this.currentLegArrivalAt = currentLegArrivalAt;
        this.userReadyAt = userReadyAt;
        this.nextBusArrivalAt = nextBusArrivalAt;
        this.transferSlackSeconds = transferSlackSeconds;
        this.source = source;
        this.confidence = confidence;
        this.calculatedAt = calculatedAt;
        this.dataObservedAt = dataObservedAt;
    }

    /**
     * 모델이 추정한 경우 어느 모델이었는지 남긴다. 실시간으로 잡았으면 null 이다.
     *
     * <p>판정 반영과 나눠 둔 이유 — 이 값은 예측 결과의 부속물이고, 나중에 모델을 바꿨을 때
     * 예측 품질을 견주려고만 쓴다. 판정 자체의 근거가 되는 값들과 섞지 않는다.
     */
    public void applyModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    /**
     * 이 상태로 알림을 쌓았다고 기록한다. {@code lastNotifiedStatus} 의 <b>유일한</b> writer 다.
     *
     * <p>이 값이 있으면 같은 상태로는 다시 알리지 않는다 — 평가가 10초마다 도는데 상태가 그대로면
     * 사용자는 같은 이야기를 반복해 듣는다. 나빠질 때만 다시 알린다.
     *
     * <p>알림 행을 실제로 넣었는지와 무관하게, <b>넣으려고 시도했으면</b> 쓴다. 중복이라 0행이
     * 들어간 것은 "사용자가 이미 봤다"는 뜻이고, 그때 마커를 안 쓰면 10초마다 영원히 헛삽입한다.
     *
     * <p>{@code UNKNOWN} 은 여기 절대 들어오지 않는다. 마커는 알림을 쌓을 때만 쓰는데
     * {@code UNKNOWN} 은 알릴 상태가 아니다. 들어오면 {@code AT_RISK → UNKNOWN → AT_RISK} 가
     * 재알림이 된다.
     */
    public void markNotified(String status) {
        this.lastNotifiedStatus = status;
    }

    /**
     * 알린 기록을 지운다. 상태가 알릴 값이 아닌 값으로 <b>좋아졌을 때만</b> 부른다.
     *
     * <p>안 지우면 나중에 다시 나빠져도 "이미 알렸다"로 걸려 알림이 안 나간다.
     *
     * <p>{@code UNKNOWN} 에서는 부르지 않는다 — 잠깐 모르는 것과 실제로 좋아진 것은 다르다.
     */
    public void clearNotified() {
        this.lastNotifiedStatus = null;
    }
}
