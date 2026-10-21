-- subway-express PR-S4: 묶인 열차의 종류와 급행 서비스.
--
-- train_kind       NORMAL / EXPRESS / UNKNOWN. null = 배포 전 행(미분류). null 을 일반으로 보지 않는다 — S3 전에는 급행도
--                  바인딩됐다. 링 갱신이 실시간 근거로 한 번 분류해 채우고, 근거가 없으면 UNKNOWN(계획 역 체인, 예전 동작).
--                  DIRECT·ITX 는 고를 수 없어(S3 422) 여기에 오지 않는다. 옛 행에서 나오면 UNKNOWN 으로 적는다.
-- train_service_id 급행 카탈로그 서비스 id(예 1009-express). EXPRESS 면 반드시 있다.
-- catalog_version  바인딩 때 해석한 카탈로그 판. 여정 도중 카탈로그가 바뀌어도 그 서비스 id 를 계속 쓴다(기록용).
--
-- 칼럼 추가만 한다(전부 nullable) — 구버전 코드는 이 칼럼을 매핑하지 않으므로 롤백해도 그대로 돈다.
ALTER TABLE subway_leg_binding
    ADD COLUMN train_kind       varchar(10),
    ADD COLUMN train_service_id varchar(40),
    ADD COLUMN catalog_version  varchar(20);

ALTER TABLE subway_leg_binding
    ADD CONSTRAINT subway_leg_binding_train_kind_check
        CHECK (train_kind IS NULL OR train_kind IN ('NORMAL', 'EXPRESS', 'UNKNOWN'));

ALTER TABLE subway_leg_binding
    ADD CONSTRAINT subway_leg_binding_express_service_check
        CHECK (train_kind IS DISTINCT FROM 'EXPRESS' OR train_service_id IS NOT NULL);
