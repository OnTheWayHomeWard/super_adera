-- The per-capability text shown to users in the permission dialog is the PURPOSE (one sentence, staff-approved).
-- It used to be called "reason"; same content, same languages.
ALTER TABLE app_capability RENAME COLUMN reason TO purpose;
