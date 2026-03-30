# NextRoute MVP 앱 설계

**날짜:** 2026-03-30
**목적:** 공공데이터 API 운영계정 전환을 위한 MVP 웹 서비스 배포 (심사 URL 제출용)
**최종 목표:** React Native 앱

---

## 1. 배경 및 목표

공공데이터 API 호출량이 개발계정 한도에 근접하여 운영계정 전환이 시급하다.
운영계정 전환 심사 요건은 **배포된 웹 서비스 URL 제출**이므로, 웹으로 먼저 배포한 뒤 RN 앱으로 이어간다.

**MVP 필수 기능:**
- 경로 검색 (출발지/도착지 → ODSay API)
- 즐겨찾기 저장/조회
- 실시간 버스/지하철 도착정보

---

## 2. 전체 구조

```
[VPS] nextroute (Spring Boot)
  기존: 수집기, ODSay 경로검색 API
  추가: 실시간 도착정보 API, 즐겨찾기 API 완성

[신규] nextroute-app (Expo RN)
  심사: npx expo export --platform web → Vercel 배포 → URL 제출
  이후: EAS Build → 앱스토어/플레이스토어
```

---

## 3. 백엔드 API

### 3-1. 기존 (완성)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/route/search` | ODSay 경로 검색 |

### 3-2. 신규 구현

**실시간 도착정보**

```
GET /api/arrivals/bus/{stopId}
```
- `bus_arrival_raw`에서 최근 15분 내 `stop_id` 기준 노선별 최신 1건 조회
- 응답: `[{ routeId, arrivalMsg1, predictTime1, congestionNum1, arrivalMsg2, predictTime2, congestionNum2 }]`

```
GET /api/arrivals/subway/{stationId}
```
- `subway_arrival_raw`에서 최근 5분 내 `station_id` 기준 `arrivalSeconds` 오름차순
- 응답: `[{ lineId, direction, arrivalSeconds, currentMessage, destinationName, trainType, arrivalCode }]`

**즐겨찾기** (`FavoriteController` 완성)

```
POST   /api/route/fav         즐겨찾기 추가
GET    /api/route/fav         목록 조회
DELETE /api/route/fav/{id}    삭제
```

### 3-3. 인증 전략 (MVP)
- Spring Security 없이 `X-Device-Id` 헤더로 익명 사용자 구분
- `User` 테이블에 deviceId 기반 자동 생성 (첫 요청 시 upsert)
- 즐겨찾기는 deviceId 단위로 격리

### 3-4. 보완점
- 버스 도착정보는 현재 **10개 노선**만 수집 중 — MVP 단계에서 커버리지 제한 있음
- 버스 정류소 검색 API: 경로 검색 결과의 stopId로 도착정보를 조회하는 흐름이므로 별도 검색 불필요

---

## 4. Expo RN 앱

### 4-1. 프로젝트 구조

```
nextroute-app/
  app/
    (tabs)/
      index.tsx               경로검색 탭
      favorites.tsx           즐겨찾기 탭
    route-result.tsx          경로 결과 (스택)
    arrivals/
      bus/[stopId].tsx        버스 도착정보
      subway/[stationId].tsx  지하철 도착정보
  components/
    RouteCard.tsx             경로 결과 카드
    ArrivalList.tsx           도착정보 리스트
  lib/
    api.ts                    axios 기반 API 클라이언트
    storage.ts                deviceId 관리 (AsyncStorage)
```

### 4-2. 화면 흐름

```
[경로검색 탭]
  출발지/도착지 입력
    → 경로 결과 목록 (RouteCard)
      → 경유 정류소/역 탭 → 도착정보 화면

[즐겨찾기 탭]
  집/회사 즐겨찾기 목록
    → 탭하면 해당 목적지로 경로검색 바로 실행
```

### 4-3. 기술 스택
- Expo SDK + Expo Router (파일기반 라우팅)
- React Native Paper 또는 NativeBase (UI 컴포넌트)
- axios (API 클라이언트)
- AsyncStorage (deviceId 영속화)

---

## 5. 구현 순서

1. **Spring Boot**: 도착정보 API 2개 (`BusArrivalController`, `SubwayArrivalController`)
2. **Spring Boot**: 즐겨찾기 API 완성 + deviceId 기반 User upsert
3. **Expo**: 프로젝트 셋업 + `lib/api.ts` 작성
4. **Expo**: 경로검색 화면
5. **Expo**: 즐겨찾기 화면
6. **Expo**: 도착정보 화면
7. **배포**: `expo export --platform web` → Vercel → URL 제출

---

## 6. 기존 코드 보완점 (발견된 문제)

| 항목 | 문제 | 해결 |
|------|------|------|
| `FavoriteController` | 빈 껍데기 — 엔드포인트 없음 | CRUD 완성 |
| `FavoriteRoute.UserId` | User 연결이 있으나 인증 없음 | MVP: deviceId 기반 User 자동 생성 |
| 버스 도착 커버리지 | 10개 노선만 수집 중 | 운영계정 전환 후 확대 |
| 도착정보 서빙 | 수집기는 있으나 API 없음 | 신규 Controller 추가 |
