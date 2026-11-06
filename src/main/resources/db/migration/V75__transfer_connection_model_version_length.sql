-- 운영 ML 모델 이름은 56자라 varchar(50) 에 들어가지 않는다. 넘치면 여정 평가 저장 전체가 실패한다.
-- varchar 를 늘리는 변경은 표를 다시 쓰지 않는다.
ALTER TABLE transfer_connection ALTER COLUMN model_version TYPE varchar(100);
