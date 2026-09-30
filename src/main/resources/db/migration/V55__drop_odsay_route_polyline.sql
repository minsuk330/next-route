-- ODsay 지하철 폴리라인 캐시 폐기 (national-otp P7).
-- 이 세 테이블을 채우던 고리(검색 seed -> collection job -> ODsay loadLane)는 P7 에서 코드째 사라졌고,
-- 읽던 쪽(RoutePolylineEnricher)도 함께 없앴다. 대중교통 형상은 P3/P4 의 OTP graph 형상이 채운다.
-- V4 는 배포된 마이그레이션이라 손대지 않고, 폐기는 이 신규 번호로만 한다.
-- 참조 검증: 이 세 테이블을 가리키는 FK 없음(V4 에서 생성 후 어떤 마이그레이션도 참조하지 않음).
DROP TABLE IF EXISTS odsay_route_polyline_collection_job;
DROP TABLE IF EXISTS odsay_route_line_seed;
DROP TABLE IF EXISTS odsay_route_polyline;
