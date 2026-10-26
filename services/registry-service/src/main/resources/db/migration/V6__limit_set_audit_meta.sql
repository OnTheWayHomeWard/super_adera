-- limit.set entries used to store meta.limits as the text of a Java object, e.g.
--   "LimitRow[channel=IN_APP, maxPerUserPerDay=20, maxPerDay=10000, maxDistinctUsersPerDay=10000, maxTotal=null]"
-- New entries store channel and the four limits as real fields (null = unlimited). Convert the old ones the same way.
UPDATE audit_event
SET meta = jsonb_build_object(
    'channel',                meta ->> 'channel',
    'maxPerUserPerDay',       CASE WHEN (regexp_match(meta ->> 'limits', 'maxPerUserPerDay=(null|[0-9]+)'))[1]       ~ '^[0-9]+$' THEN to_jsonb(CAST((regexp_match(meta ->> 'limits', 'maxPerUserPerDay=([0-9]+)'))[1] AS bigint)) ELSE 'null'::jsonb END,
    'maxPerDay',              CASE WHEN (regexp_match(meta ->> 'limits', 'maxPerDay=(null|[0-9]+)'))[1]              ~ '^[0-9]+$' THEN to_jsonb(CAST((regexp_match(meta ->> 'limits', 'maxPerDay=([0-9]+)'))[1] AS bigint)) ELSE 'null'::jsonb END,
    'maxDistinctUsersPerDay', CASE WHEN (regexp_match(meta ->> 'limits', 'maxDistinctUsersPerDay=(null|[0-9]+)'))[1] ~ '^[0-9]+$' THEN to_jsonb(CAST((regexp_match(meta ->> 'limits', 'maxDistinctUsersPerDay=([0-9]+)'))[1] AS bigint)) ELSE 'null'::jsonb END,
    'maxTotal',               CASE WHEN (regexp_match(meta ->> 'limits', 'maxTotal=(null|[0-9]+)'))[1]               ~ '^[0-9]+$' THEN to_jsonb(CAST((regexp_match(meta ->> 'limits', 'maxTotal=([0-9]+)'))[1] AS bigint)) ELSE 'null'::jsonb END)
WHERE action = 'limit.set' AND jsonb_typeof(meta -> 'limits') = 'string';
