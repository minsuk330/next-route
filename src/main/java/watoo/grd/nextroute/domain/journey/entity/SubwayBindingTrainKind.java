package watoo.grd.nextroute.domain.journey.entity;

/**
 * 묶인 열차의 종류(subway-express PR-S4, V72 {@code subway_leg_binding.train_kind}).
 *
 * <p>칼럼 null 은 배포 전 행(미분류)이다 — 일반으로 보지 않는다. 링 갱신이 한 번 분류해 채운다.
 * 직통·ITX 는 고를 수 없어(S3 422) 여기에 없다.
 */
public enum SubwayBindingTrainKind {
    NORMAL,
    /** 급행. {@code train_service_id} 가 반드시 있다(DB CHECK). */
    EXPRESS,
    /** 근거가 없다. 링은 계획 역 체인으로 돈다(예전 동작). */
    UNKNOWN
}
