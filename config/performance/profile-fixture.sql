DO $$
BEGIN
    IF current_database() <> 'intermine_performance_fixture' THEN
        RAISE EXCEPTION 'This fixture requires the intermine_performance_fixture database';
    END IF;
END
$$;
CREATE EXTENSION pg_stat_statements;
CREATE TABLE benchmark_fixture AS SELECT i AS id FROM generate_series(1, 1000) i;
ANALYZE benchmark_fixture;
CREATE ROLE intermine_profile_reader;
GRANT pg_read_all_stats TO intermine_profile_reader;
CREATE ROLE intermine_profile_plain;
CREATE ROLE intermine_profile_noinherit NOINHERIT;
GRANT pg_read_all_stats TO intermine_profile_noinherit;
