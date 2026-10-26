-- A reopened report used to keep the staff id and time of its earlier handling. New code clears them on reopen;
-- this clears the same leftovers on existing OPEN rows.
UPDATE app_report SET handled_by = NULL, handled_at = NULL WHERE status = 'OPEN';
