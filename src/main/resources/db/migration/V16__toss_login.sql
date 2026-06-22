-- 토스 로그인: users에 토스 사용자 식별키 추가 + 토스 토큰 보관 테이블

ALTER TABLE users ADD COLUMN toss_user_key BIGINT;
ALTER TABLE users ADD CONSTRAINT uq_users_toss_user_key UNIQUE (toss_user_key);

CREATE TABLE toss_user_token (
    id                  BIGSERIAL PRIMARY KEY,
    user_key            BIGINT       NOT NULL,
    access_token        VARCHAR(2048) NOT NULL,
    refresh_token       VARCHAR(2048) NOT NULL,
    access_expires_at   TIMESTAMP,
    refresh_expires_at  TIMESTAMP,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    deleted_at          TIMESTAMP,
    CONSTRAINT uq_toss_user_token_user_key UNIQUE (user_key)
);
