-- PHASE 7, "CONTRACT" STEP. RUN BY HAND, ONLY WHEN YOU ARE SURE.
--
-- Phase 7 moved the data of notifications, payments and event analytics out of the core database into the databases of the new
-- services (expand/contract migration). The new services copied the old rows on their first start, and the core stopped
-- reading or writing these tables. They are still there so that a rollback to the previous version stays possible.
--
-- Run this against ridex_db ONLY after:
--   1. notification-service, payment-service and analytics-service have run once with LEGACY_DB_URL set,
--      so their importers copied the old rows. Check the logs for "Imported N ... from the core database".
--   2. You compared the counts, for example:
--        in ridex_db:          select count(*) from notifications;   select count(*) from payments;
--        in notification_db:   select count(*) from notifications;
--        in payment_db:        select count(*) from payments;
--   3. You have a backup you could restore.
--
-- THIS IS IRREVERSIBLE. Dropping a table deletes its rows.
--
-- How to run (PowerShell, from the repository root):
--   docker exec -i ridex-postgres psql -U ridex -d ridex_db < docs/sql/phase-7-contract.sql
--
-- After running it, remove the names from LEGACY_TABLES_IN_CORE_PENDING_CONTRACT in
-- architecture-tests/.../DatabasePerServiceTest.java and add a core migration that drops them, so fresh installations never
-- create them.

BEGIN;

DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS mock_gateway_charges;
DROP TABLE IF EXISTS event_audit_log;
DROP TABLE IF EXISTS ride_status_counts_daily;
DROP TABLE IF EXISTS dead_letter_events;

COMMIT;
