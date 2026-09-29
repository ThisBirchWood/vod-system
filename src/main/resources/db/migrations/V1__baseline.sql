-- Users Table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    google_id VARCHAR(64),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    profile_picture_url VARCHAR(255),
    role INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    stream_key VARCHAR(64) NOT NULL UNIQUE
);


-- Streams Table
CREATE TABLE IF NOT EXISTS streams (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    start_time TIMESTAMPTZ DEFAULT NOW(),
    end_time TIMESTAMPTZ DEFAULT NULL,
    last_seen TIMESTAMPTZ DEFAULT NULL,

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Clips Table
CREATE TABLE IF NOT EXISTS clips (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    fps FLOAT NOT NULL,
    duration FLOAT NOT NULL,
    file_size BIGINT NOT NULL,
    video_path VARCHAR(255) NOT NULL,
    thumbnail_path VARCHAR(255) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- VoDs Table
CREATE TABLE IF NOT EXISTS vods (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    fps FLOAT NOT NULL,
    duration FLOAT NOT NULL,
    file_size BIGINT NOT NULL,
    video_path VARCHAR(255) NOT NULL,
    thumbnail_path VARCHAR(255) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Markers Table
CREATE TABLE IF NOT EXISTS markers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    message VARCHAR(255),
    timestamp TIMESTAMPTZ DEFAULT NOW(),

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (stream_id) REFERENCES streams(id) ON DELETE CASCADE
);