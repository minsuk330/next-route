package watoo.grd.nextroute.application.route.routing;

/** 이번 계산이 실제로 어디서 나왔는지. coverage(지원 능력)와 구분한다. */
public enum ActualSource {
    STATIC,
    REALTIME,
    MODEL
}
