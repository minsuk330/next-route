package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;

/**
 * 잠금을 뚫고 들어온 동시 시작을 409 로 바꾼다. 일반 시작과 단독 추적 시작(#265)이 같이 쓴다.
 *
 * <p><b>모든 무결성 오류를 409 로 바꾸지 않는다.</b> 그러면 FK 위반이나 NOT NULL 위반 같은
 * 진짜 버그가 "이미 여정이 있습니다"로 둔갑해 조용히 묻힌다. 정확히 그 인덱스의 위반만 본다.
 *
 * <p>트랜잭션은 이미 되감긴 뒤라, 현재 진행 중인 여정은 별도 조회로 다시 읽는다. 그 사이에
 * 상대 여정이 끝났다면 번역할 대상이 없으므로 원래 예외를 그대로 올린다.
 */
@Component
@RequiredArgsConstructor
public class JourneyStartConflictTranslator {

    /** "사람당 진행 중인 여정 하나"를 지키는 인덱스(V35). 이 이름의 위반만 409 로 번역한다. */
    private static final String ACTIVE_JOURNEY_INDEX = "uq_journey_active_per_user";

    /** PostgreSQL 유일 제약 위반. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private final JourneyQueryService queryService;

    public RuntimeException translate(DataIntegrityViolationException e, Long userId) {
        if (!isActiveJourneyConflict(e)) {
            return e;
        }
        return queryService.getActive(userId)
                .<RuntimeException>map(ActiveJourneyExistsException::new)
                .orElse(e);
    }

    private boolean isActiveJourneyConflict(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cause
                && SQLSTATE_UNIQUE_VIOLATION.equals(cause.getSQLState())
                && ACTIVE_JOURNEY_INDEX.equalsIgnoreCase(cause.getConstraintName());
    }
}
