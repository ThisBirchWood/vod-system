CREATE TABLE IF NOT EXISTS token_families
(
    id BIGSERIAL PRIMARY KEY NOT NULl,
    user_id BIGSERIAL NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_token_families_user_id
    ON token_families (user_id);

CREATE TABLE IF NOT EXISTS refresh_tokens
(
    id BIGSERIAL PRIMARY KEY NOT NULL,
    user_id BIGSERIAL NOT NULL,
    family_id BIGSERIAL NOT NULL,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT refresh_tokens_hash_len CHECK (octet_length(token_hash) = 32),

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (family_id) REFERENCES token_families(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id
    ON refresh_tokens (family_id);
