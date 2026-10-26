-- D1 bills one row written per touched index on every INSERT/UPDATE. Challenges
-- are only ever looked up by primary key, and expiry is filtered after that
-- lookup, so the standalone expiry index is never read.
DROP INDEX IF EXISTS challenges_expiry;

-- The UNIQUE constraint on challenges.consumption_id creates an auto-index that
-- is never used: the guard reads challenges by challenge_id, and the single-use
-- rule comes from the guarded UPDATE (... AND consumption_id IS NULL) plus the
-- challenge_no_reuse trigger, not from the uniqueness of the random attempt ID.
-- Rebuild the table without the auto-index so each issued challenge costs one
-- row instead of two and each consumption update costs one row instead of two.
CREATE TABLE challenges_new (challenge_id TEXT PRIMARY KEY, actor_type TEXT NOT NULL CHECK(actor_type IN('REGISTRATION','CREDENTIAL')), actor_id TEXT NOT NULL, method TEXT NOT NULL, path TEXT NOT NULL, process_nonce TEXT NOT NULL, grant_id TEXT NOT NULL DEFAULT '', nonce TEXT NOT NULL, expires_at INTEGER NOT NULL, consumption_id TEXT, consumed_request_id TEXT, created_at INTEGER NOT NULL);
INSERT INTO challenges_new(challenge_id,actor_type,actor_id,method,path,process_nonce,grant_id,nonce,expires_at,consumption_id,consumed_request_id,created_at) SELECT challenge_id,actor_type,actor_id,method,path,process_nonce,grant_id,nonce,expires_at,consumption_id,consumed_request_id,created_at FROM challenges;
DROP TABLE challenges;
ALTER TABLE challenges_new RENAME TO challenges;
CREATE TRIGGER challenge_no_reuse BEFORE UPDATE OF consumption_id ON challenges WHEN OLD.consumption_id IS NOT NULL AND NEW.consumption_id IS NOT OLD.consumption_id BEGIN SELECT RAISE(ABORT,'challenge already consumed'); END;

-- An initialization row is the most expensive write in the system because the
-- table carried one index per traceable identity column. Keep the indexes used
-- by watermark tracing (self_wx_id/self_alias) and the user history page; the
-- admin list's sourceIp and installationId filters fall back to a scan.
DROP INDEX IF EXISTS initialization_ip_time;
DROP INDEX IF EXISTS initialization_installation_time;
