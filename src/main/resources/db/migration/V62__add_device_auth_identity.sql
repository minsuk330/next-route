-- device-anonymous-auth. 독립 앱의 로그인을 없애고 기기 비밀값 하나로 계정을 받는다.
--
-- 신원 구조(V33)를 그대로 타고 provider 하나만 더한다. users 에 device_id 를 되살리지 않는다(V17 에서 지운 칼럼).
-- provider_subject 는 앱이 만든 256비트 비밀값의 sha256 hex 다. 원문은 저장하지 않는다.
--
-- DEVICE 는 두 "하나만" 규칙에서 빠진다.
--   * 앱 로그인 수단은 계정당 하나 — 업데이트한 애플·카카오 사용자가 기존 계정에 기기를 연결할 수 있어야 한다.
--   * 같은 회사는 계정당 하나 — 한 사용자가 기기 여러 대(아이폰+아이패드)를 각각 연결한다. 상한(3)은 코드가 지킨다.
-- (provider, provider_subject) 유니크는 그대로라 같은 비밀값은 한 사용자에게만 묶인다.

ALTER TABLE auth_identity DROP CONSTRAINT auth_identity_provider_check;
ALTER TABLE auth_identity ADD CONSTRAINT auth_identity_provider_check
    CHECK (provider IN ('TOSS', 'APPLE', 'KAKAO', 'DEVICE'));

DROP INDEX uq_auth_identity_single_app_provider;
CREATE UNIQUE INDEX uq_auth_identity_single_app_provider
    ON auth_identity (user_id) WHERE provider NOT IN ('TOSS', 'DEVICE') AND deleted_at IS NULL;

DROP INDEX uq_auth_identity_user_provider;
CREATE UNIQUE INDEX uq_auth_identity_user_provider
    ON auth_identity (user_id, provider) WHERE provider <> 'DEVICE' AND deleted_at IS NULL;
