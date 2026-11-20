"""Compare logical data in quiesced PostgreSQL databases before and after migration."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

SECTIONS = ("tables", "sequences", "large_objects")
FORMAT_VERSION = 2
SESSION_OPTIONS = (
    "-c default_transaction_read_only=on -c row_security=off -c timezone=UTC "
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
                f"SELECT row_text FROM (SELECT to_json(t::text)::text AS row_text "
                f'FROM {only}{name} t) rows ORDER BY row_text COLLATE "C"',
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
        "format_version": FORMAT_VERSION,
        **identity,
        "tables": snapshot_tables(database),
        "sequences": snapshot_sequences(database),
        "large_objects": {"metadata": metadata, "pages": pages},
    }


def require_fields(record, fields):
    if not isinstance(record, dict) or any(
        type(record.get(key)) is not expected for key, expected in fields.items()
    ):
        raise ValueError("Incomplete or invalid database snapshot record")


def validate_digest(record):
    require_fields(record, {"rows": int, "sha256": str})
    if record["rows"] < 0 or not re.fullmatch("[0-9a-f]{64}", record["sha256"]):
        raise ValueError("Invalid database snapshot row digest")


def validate_snapshot(snapshot):
    require_fields(
        snapshot,
        {
            "format_version": int,
            "database": str,
            "server_version": str,
            **{section: dict for section in SECTIONS},
        },
    )
    if snapshot["format_version"] != FORMAT_VERSION:
        raise ValueError("Unsupported database snapshot format")
    for name, table in snapshot["tables"].items():
        require_fields(table, {"owner": str, "kind": str})
        validate_digest(table)
        columns = table.get("columns")
        if (
            not isinstance(name, str)
            or not name
            or not table["owner"]
            or table["kind"] not in ("r", "p", "m")
            or "columns" not in table
            or (columns is not None and not isinstance(columns, list))
        ):
            raise ValueError("Invalid database snapshot table")
        for column in columns or []:
            if (
                not isinstance(column, list)
                or len(column) != 5
                or any(
                    type(value) is not expected
                    for value, expected in zip(column, (str, str, bool, str, str))
                )
            ):
                raise ValueError("Invalid database snapshot column")
    for name, sequence in snapshot["sequences"].items():
        require_fields(
            sequence,
            {
                "owner": str,
                "type": str,
                "cycle": bool,
                "is_called": bool,
                **{
                    key: int
                    for key in (
                        "start",
                        "increment",
                        "minimum",
                        "maximum",
                        "cache",
                        "last_value",
                    )
                },
            },
        )
        if not isinstance(name, str) or not name or not sequence["owner"]:
            raise ValueError("Invalid database snapshot sequence")
    large_objects = snapshot["large_objects"]
    require_fields(large_objects, {"metadata": list, "pages": dict})
    validate_digest(large_objects["pages"])
    for metadata in large_objects["metadata"]:
        if (
            not isinstance(metadata, list)
            or len(metadata) != 3
            or type(metadata[0]) is not int
            or metadata[0] <= 0
            or not isinstance(metadata[1], str)
            or not metadata[1]
            or (
                metadata[2] is not None
                and (
                    not isinstance(metadata[2], list)
                    or any(not isinstance(acl, str) for acl in metadata[2])
                )
            )
        ):
            raise ValueError("Invalid database snapshot large object")


def compare_snapshots(before, after):
    for snapshot in (before, after):
        validate_snapshot(snapshot)
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
