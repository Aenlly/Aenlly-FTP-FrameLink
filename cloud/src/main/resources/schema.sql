CREATE TABLE IF NOT EXISTS accounts (
 id VARCHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 quota_bytes BIGINT NOT NULL, used_bytes BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_sessions (
 token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, expires_at BIGINT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES accounts(id)
);
CREATE TABLE IF NOT EXISTS projects (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, name VARCHAR(160) NOT NULL,
 state VARCHAR(24) NOT NULL, round_no INTEGER NOT NULL DEFAULT 1, selection_version INTEGER NOT NULL DEFAULT 0,
 confirmed_revision VARCHAR(36), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 FOREIGN KEY(owner_id) REFERENCES accounts(id)
);
CREATE TABLE IF NOT EXISTS media (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, source_key VARCHAR(200) NOT NULL,
 name VARCHAR(200) NOT NULL, kind VARCHAR(20) NOT NULL, parent_id VARCHAR(36), object_key VARCHAR(300) NOT NULL,
 mime VARCHAR(100) NOT NULL, bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY(project_id) REFERENCES projects(id), UNIQUE(project_id,source_key,kind)
);
CREATE TABLE IF NOT EXISTS shares (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
 code_hash VARCHAR(100) NOT NULL, expires_at BIGINT NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE,
 allow_download BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL,
 FOREIGN KEY(project_id) REFERENCES projects(id)
);
CREATE TABLE IF NOT EXISTS visitor_grants (
 token_hash VARCHAR(64) PRIMARY KEY, share_id VARCHAR(36) NOT NULL, expires_at BIGINT NOT NULL,
 FOREIGN KEY(share_id) REFERENCES shares(id)
);
CREATE TABLE IF NOT EXISTS selections (
 project_id VARCHAR(36) NOT NULL, round_no INTEGER NOT NULL, media_id VARCHAR(36) NOT NULL,
 selected BOOLEAN NOT NULL, comment_text VARCHAR(2000) NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(project_id,round_no,media_id), FOREIGN KEY(project_id) REFERENCES projects(id)
);
CREATE TABLE IF NOT EXISTS selection_revisions (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, round_no INTEGER NOT NULL,
 selection_version INTEGER NOT NULL, items_json TEXT NOT NULL, submitted_at BIGINT NOT NULL,
 FOREIGN KEY(project_id) REFERENCES projects(id), UNIQUE(project_id,round_no)
);
