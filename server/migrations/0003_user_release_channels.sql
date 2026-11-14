ALTER TABLE users ADD COLUMN release_channel TEXT NOT NULL DEFAULT 'stable';
CREATE INDEX users_release_channel ON users(release_channel);
