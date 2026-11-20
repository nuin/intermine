"""Compare logical data in quiesced PostgreSQL databases before and after migration."""

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path

SECTIONS = ("tables", "sequences", "large_objects")
SESSION_OPTIONS = (
    "-c default_transaction_read_only=on -c timezone=UTC "
    "-c extra_float_digits=3 -c bytea_output=hex "
    "-c datestyle=ISO,YMD -c intervalstyle=postgres"
)
USER_RELATIONS = "n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'"


def qualified_name(schema, name):
    return ".".join('"' + value.replace('"', '""') + '"' for value in (schema, name))


def query_lines(database, sql):
    environment = os.environ.copy()
    environment["PGOPTIONS"] = environment.get("PGOPTIONS", "") + " " + SESSION_OPTIONS
    command = [
        "psql",
        "-X",
        "-Atq",
        "--no-password",
        "-v",
        "ON_ERROR_STOP=1",
        "--dbname",
        database,
        "-c",
        sql,
    ]
    with (
        tempfile.TemporaryFile() as errors,
        subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=errors, env=environment
        ) as process,
    ):
        yield from process.stdout
        if process.wait() != 0:
            errors.seek(0)
            raise RuntimeError(errors.read().decode("utf-8", errors="replace").strip())


def query_json(database, sql):
    return [json.loads(line) for line in query_lines(database, sql)]


def digest_query(database, sql):
    digest = hashlib.sha256()
    rows = 0
    for line in query_lines(database, sql):
        digest.update(line)
        rows += 1
    return {"rows": rows, "sha256": digest.hexdigest()}


def snapshot_tables(database):
    catalog = query_json(
        database,
        """
        SELECT json_build_object(
            'schema', n.nspname, 'name', c.relname, 'kind', c.relkind,
            'owner', pg_get_userbyid(c.relowner),
            'columns', (SELECT json_agg(json_build_array(
                a.attname, format_type(a.atttypid, a.atttypmod),
                a.attnotnull, a.attidentity, a.attgenerated) ORDER BY a.attnum)
                FROM pg_attribute a WHERE a.attrelid = c.oid
                AND a.attnum > 0 AND NOT a.attisdropped))
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r', 'p', 'm', 'f') AND """
        + USER_RELATIONS,
    )
    tables = {}
    for table in catalog:
        name = qualified_name(table.pop("schema"), table.pop("name"))
        if table["kind"] == "f":
            raise ValueError(f"Foreign table needs a separate migration check: {name}")
        only = "" if table["kind"] == "m" else "ONLY "
        table.update(
            digest_query(
                database,
                f"SELECT row_json FROM (SELECT row_to_json(t)::text AS row_json "
                f'FROM {only}{name} t) rows ORDER BY row_json COLLATE "C"',
            )
        )
        tables[name] = table
    return tables


def snapshot_sequences(database):
    catalog = query_json(
        database,
        """
        SELECT json_build_object(
            'schema', n.nspname, 'name', c.relname,
            'owner', pg_get_userbyid(c.relowner),
            'type', format_type(s.seqtypid, NULL), 'start', s.seqstart,
            'increment', s.seqincrement, 'minimum', s.seqmin, 'maximum', s.seqmax,
            'cache', s.seqcache, 'cycle', s.seqcycle)
        FROM pg_sequence s JOIN pg_class c ON c.oid = s.seqrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE """
        + USER_RELATIONS,
    )
    sequences = {}
    for sequence in catalog:
        name = qualified_name(sequence.pop("schema"), sequence.pop("name"))
        state = query_json(
            database,
            "SELECT json_build_object("
            f"'last_value', last_value, 'is_called', is_called) FROM {name}",
        )
        sequence.update(state[0])
        sequences[name] = sequence
    return sequences


def snapshot_database(database):
    """Read physical tables, sequences and large objects while all writers are stopped."""
    identity = query_json(
        database,
        "SELECT json_build_object("
        "'database', current_database(), 'server_version', current_setting('server_version'))",
    )[0]
    metadata = query_json(
        database,
        """
        SELECT json_build_array(oid::bigint, pg_get_userbyid(lomowner), lomacl)
        FROM pg_largeobject_metadata ORDER BY oid
    """,
    )
    pages = digest_query(
        database,
        """
        SELECT json_build_array(loid::bigint, pageno, encode(data, 'hex'))
        FROM pg_largeobject ORDER BY loid, pageno
    """,
    )
    return {
        "format_version": 1,
        **identity,
        "tables": snapshot_tables(database),
        "sequences": snapshot_sequences(database),
        "large_objects": {"metadata": metadata, "pages": pages},
    }


def compare_snapshots(before, after):
    for snapshot in (before, after):
        if snapshot.get("format_version") != 1:
            raise ValueError("Unsupported database snapshot format")
        if any(not isinstance(snapshot.get(section), dict) for section in SECTIONS):
            raise ValueError("Incomplete database snapshot")
    differences = []
    for section in SECTIONS:
        for name in sorted(before[section].keys() | after[section].keys()):
            if before[section].get(name) != after[section].get(name):
                differences.append(f"{section}: {name}")
    return differences


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot = commands.add_parser(
        "snapshot", help="Read a database with all writers stopped"
    )
    snapshot.add_argument("--database", required=True)
    snapshot.add_argument("--output", type=Path, required=True)
    compare = commands.add_parser("compare", help="Fail if logical contents differ")
    compare.add_argument("before", type=Path)
    compare.add_argument("after", type=Path)
    args = parser.parse_args()
    if args.command == "snapshot":
        result = snapshot_database(args.database)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        print(
            f"Captured {len(result['tables'])} tables and "
            f"{len(result['sequences'])} sequences from {result['database']}"
        )
    else:
        differences = compare_snapshots(
            json.loads(args.before.read_text()), json.loads(args.after.read_text())
        )
        if differences:
            raise SystemExit("Database contents differ:\n" + "\n".join(differences))
        print("Database tables, sequences and large objects match")


if __name__ == "__main__":
    main()
