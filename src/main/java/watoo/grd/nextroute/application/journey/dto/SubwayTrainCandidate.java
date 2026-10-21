package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.time.Instant;

/**
 * 역에서 고를 수 있는 열차 하나(#265). 행선지·급행은 사용자가 급행·다른 지선을 거르는 근거다.
 *
 * <p>{@link SubwayBindingView} 와 달리 <b>열차번호를 싣는다</b> — 선택 요청의 키다. 앱이 이 값으로 다른 판단을 하지
 * 않게 HANDOFF 에 적는다.
 *
 * <p><b>구버전 앱 호환</b>(subway-express PR-S3): 앞 아홉 칸의 이름·뜻은 그대로다. 특히 {@code kind} 는 열차 종류가 아니라
 * UPCOMING/DEPARTED 다(앱 {@code train-picker-model.ts} 가 DEPARTED 판정에 쓴다). 열차 종류는 새 칸 {@code trainKind} 다.
 *
 * @param express          급행인가. 이제 분류기({@code trainKind == EXPRESS})로 정한다 — 예전 {@code contains("급행")} 은 특급을
 *                         빠뜨리고 공항철도 직통을 급행으로, 1호선 구로 급행(모든 역 정차)을 급행으로 봤다
 * @param arrivalSeconds   지금 기준 남은 초. 원천이 미산출이면 null — 그때는 {@code stationsAway} 가 대신 답한다.
 *                         null 을 "곧 도착"으로 읽지 않는다(#489: 3개 역 밖 열차가 곧 도착으로 보였다)
 * @param trainType        원천 열차 종류 원문(일반·급행·특급·ITX). 떠난 열차의 위치 원천은 {@code directAt} 을 옮긴 값. 없으면 null
 * @param observedAt       원천 수신 시각. 떠난 열차는 출발 시각
 * @param kind             곧 올 열차인지, 이 역을 이미 떠난 열차인지. 떠난 열차는 {@code arrivalSeconds} 가 null 이고
 *                         {@code destination}·{@code trainType} 도 원천에 없으면 null 이다(위치 원천은 행선지를 주지 않는다)
 * @param stationsAway     승차역까지 남은 정거장 수. 진입·도착이면 0, 모르면 null. 역 도착 조회
 *                         ({@code /api/arrivals/subway})와 같은 계산({@code SubwayStationsAway})이라 같은 열차에 같은 값이다.
 *                         떠난 열차는 null
 * @param arrivalCode      서울 {@code arvlCd} 원문(0 진입·1 도착·2 출발·3~5 전역·99 운행 중). 역 도착 조회와 같은 값.
 *                         떠난 열차는 null
 * @param trainKind        열차 종류(NORMAL·EXPRESS·DIRECT·ITX·UNKNOWN). 행이 아니라 열차 단위로 판정한다
 * @param trainLabel       표시 이름(일반·급행·직통·ITX-청춘). UNKNOWN 은 null
 * @param trackable        골라서 실시간으로 따라갈 수 있는가. false 면 선택이 422 다
 *                         ({@code TRAIN_SKIPS_STATION} 또는 {@code TRAIN_NOT_TRACKABLE})
 * @param stopsAtBoarding  탑승역에 서는가. 해석된 급행만 참/거짓, 일반은 참, 그 밖은 null(모름)
 * @param stopsAtAlighting 하차역에 서는가(그 열차 종착 이후면 false). 해석된 급행만 참/거짓, 그 밖은 null
 * @param expressServiceId 해석된 급행 서비스 id(카탈로그, 예: {@code 1009-express}). 아니면 null
 */
public record SubwayTrainCandidate(String trainNo, String destination, boolean express, String trainType,
                                   Integer arrivalSeconds, Instant observedAt, Kind kind,
                                   Integer stationsAway, String arrivalCode,
                                   TrainKind trainKind, String trainLabel, boolean trackable,
                                   Boolean stopsAtBoarding, Boolean stopsAtAlighting, String expressServiceId) {

    /** 앱이 "이 열차 탈게요"/"이미 탔어요"를 가르는 값. */
    public enum Kind { UPCOMING, DEPARTED }
}
