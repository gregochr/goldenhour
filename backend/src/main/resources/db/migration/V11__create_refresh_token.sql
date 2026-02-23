CREATE TABLE refresh_token (
    id          BIGSERIAL    PRIMARY KEY,
    token_hash  VARCHAR(64)  UNIQUE NOT NULL,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE
);
