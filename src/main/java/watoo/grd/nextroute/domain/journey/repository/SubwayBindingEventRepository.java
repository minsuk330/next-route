package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface SubwayBindingEventRepository extends JpaRepository<SubwayBindingEvent, Long> {

    /**
     * 관측·판단 한 건 저장. <b>중복은 예외가 아니라 0행이다.</b>
     *
     * <p>{@code save()} 를 쓰지 않는 이유. 지하철에서 응답을 못 받은 앱이 같은 관측을 다시
     * 보내는 것은 <b>정상</b> 동작이다. 유일성 위반이 예외로 올라오면 그 정상 동작이 500 이 되고,
     * 앱은 영원히 재시도한다.
     *
     * <p>중재자에 {@code event_type} 이 들어가는 것이 중요하다. 이동 시작과 정차는 각각 1 부터
     * 세므로, 빼면 서로의 1 번이 충돌해 둘 중 하나가 조용히 사라진다.
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다.
     *
     * @return 생성된 행 id(1건). 중복이면 0건
     */
    @Query(value = """
            INSERT INTO subway_binding_event
                (journey_leg_id, journey_id, route_revision, leg_index,
                 kind, event_type, evidence_sequence, algorithm_version,
                 binding_version, reset_generation, occurred_at, received_at,
                 chosen_train_no, chosen_service_date, chosen_line_id, chosen_direction,
                 detail, created_at, updated_at)
            VALUES (:journeyLegId, :journeyId, :routeRevision, :legIndex,
                    :kind, :eventType, :evidenceSequence, :algorithmVersion,
                    :bindingVersion, :resetGeneration, :occurredAt, :receivedAt,
                    :chosenTrainNo, :chosenServiceDate, :chosenLineId, :chosenDirection,
                    cast(:detail as jsonb), :auditNow, :auditNow)
            ON CONFLICT (journey_leg_id, kind, event_type, evidence_sequence) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    List<Long> insertIgnoringDuplicate(
            @Param("journeyLegId") Long journeyLegId, @Param("journeyId") Long journeyId,
            @Param("routeRevision") int routeRevision, @Param("legIndex") int legIndex,
            @Param("kind") String kind, @Param("eventType") String eventType,
            @Param("evidenceSequence") Integer evidenceSequence,
            @Param("algorithmVersion") String algorithmVersion,
            @Param("bindingVersion") Integer bindingVersion,
            @Param("resetGeneration") int resetGeneration,
            @Param("occurredAt") Instant occurredAt, @Param("receivedAt") Instant receivedAt,
            @Param("chosenTrainNo") String chosenTrainNo,
            @Param("chosenServiceDate") LocalDate chosenServiceDate,
            @Param("chosenLineId") String chosenLineId,
            @Param("chosenDirection") String chosenDirection,
            @Param("detail") String detail, @Param("auditNow") LocalDateTime auditNow);

    /**
     * 그 단계의 최근 판단부터 거슬러 읽는다.
     *
     * <p>후보 목록을 되찾는 경로다. 캐시가 날아가도 여기서 "그때 무엇과 무엇 중에 골랐나"를
     * 읽어 교정할 수 있다.
     */
    List<SubwayBindingEvent> findByJourneyLegIdOrderByIdDesc(Long journeyLegId, Limit limit);
}
