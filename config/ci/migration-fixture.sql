CREATE SCHEMA migration_probe;
CREATE TABLE migration_probe."quoted""records" (
    id integer PRIMARY KEY,
    label text,
    amount numeric,
    payload bytea
);
INSERT INTO migration_probe."quoted""records" VALUES
    (3, 'café', 1.25, decode('000a7fff', 'hex')),
    (1, E'line\nbreak', NULL, NULL),
    (2, '日本語', -2.50, decode('', 'hex'));
CREATE SEQUENCE migration_probe.counter START 41;
SELECT setval('migration_probe.counter', 73, false);
CREATE TABLE migration_probe.large_objects (id oid PRIMARY KEY);
INSERT INTO migration_probe.large_objects VALUES (
    lo_from_bytea(0, decode('000a7fff', 'hex'))
);
