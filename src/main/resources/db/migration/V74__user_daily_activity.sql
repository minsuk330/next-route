-- 사용 통계(docs/plan/usage-stats): 인증된 사용자가 그날 앱을 썼다는 사실, 사용자당 하루 1행.
-- activity_date 는 KST 날짜. 쓰기는 ON CONFLICT DO NOTHING(첫 요청만 남는다). 탈퇴 시 물리 삭제.
CREATE TABLE user_daily_activity (
    activity_date date         NOT NULL,
    user_id       bigint       NOT NULL REFERENCES users (id),
    first_seen_at timestamptz  NOT NULL,
    app_build     varchar(32),
    PRIMARY KEY (activity_date, user_id)
);

CREATE INDEX idx_user_daily_activity_user ON user_daily_activity (user_id);
