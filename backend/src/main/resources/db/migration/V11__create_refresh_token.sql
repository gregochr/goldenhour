CREATE TABLE refresh_token (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_hash  VARCHAR(64)  UNIQUE NOT NULL,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE
);
