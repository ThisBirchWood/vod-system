CREATE TABLE IF NOT EXISTS token_families
(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_token_families_user_id
    ON token_families (user_id);

CREATE TABLE IF NOT EXISTS refresh_tokens
(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES token_families (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE, -- unique automatically creates a B-tree index
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT refresh_tokens_hash_len CHECK (octet_length(token_hash) = 32)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id
    ON refresh_tokens (family_id);
