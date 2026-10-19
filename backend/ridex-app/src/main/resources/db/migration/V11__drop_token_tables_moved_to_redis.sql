-- Phase 4: email-verification and password-reset tokens now live in Redis, where they expire by
-- themselves (a TTL). The tables that held them are no longer used.
DROP TABLE IF EXISTS email_verification_tokens;
DROP TABLE IF EXISTS password_reset_tokens;
