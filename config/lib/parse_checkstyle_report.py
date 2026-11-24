#!/usr/bin/env python

import json
import sys
import xml.sax
from collections import defaultdict
from pathlib import Path
from xml.sax.handler import ContentHandler, ErrorHandler


class Error:
    def __init__(self, severity, line, column, message):
        self.severity = severity
        self.line = line
        self.column = column if column is not None else "-"
        self.message = message


class CheckStyleHandler(ContentHandler):
    def __init__(self):
        self.errors = defaultdict(list)
        self.files = set()
        self.currentFile = None

    def startElement(self, name, attrs):
        if name == "file":
            self.currentFile = attrs.get("name")
            self.files.add(self.currentFile)
        elif name == "error":
            self.handleError(attrs)

    def can_ignore(self, err):
        if "Unable to get class information" in err.message:
            return True
        if "Redundant throws" in err.message:
            return True
        return "Unused @throws tag" in err.message

    def handleError(self, attrs):
        err = Error(
            attrs.get("severity"),
            attrs.get("line"),
            attrs.get("column"),
            attrs.get("message"),
        )
        if not self.can_ignore(err):
            self.errors[self.currentFile].append(err)


class NervousErrorHandler(ErrorHandler):
    def error(self, exception):
        raise exception

    def warning(self, exception):
        raise exception


ERROR_FMT = "  line: {0.line:>4}, column: {0.column:>3} - {0.severity} - {0.message}"

workspace_dir = Path(sys.argv[1])
build_roots = (
    "intermine",
    "bio",
    "bio/sources",
    "bio/postprocess",
    "bio/postprocess-test",
)

error_count = 0
report_count = 0

for build_root in build_roots:
    project_dir = workspace_dir / build_root
    manifest = project_dir / "build/checkstyle-reports.json"
    if not manifest.is_file():
        sys.exit(f"MISSING STYLE MANIFEST: {manifest}")
    reports = json.loads(manifest.read_text())["reports"]
    for report in reports:
        report_path = project_dir / report["path"]
        if not report_path.is_file():
            sys.exit(f"MISSING STYLE REPORT: {report_path}")
        expected_sources = set(report["sources"])
        if not expected_sources:
            sys.exit(f"EMPTY SOURCE MANIFEST: {report_path}")
        report_count += 1
        handler = CheckStyleHandler()
        with report_path.open() as stream:
            xml.sax.parse(stream, handler, NervousErrorHandler())
        missing_sources = expected_sources - handler.files
        for source in sorted(missing_sources):
            print(f"SOURCE MISSING FROM STYLE REPORT: {source}")
            error_count += 1
        for file_name, errors in handler.errors.items():
            print("FILE:", file_name)
            for e in errors:
                print(ERROR_FMT.format(e))
                error_count += 1

if error_count:
    print(f"{error_count} STYLE CHECKS FAILED")
    sys.exit(1)

if not report_count:
    print("NO STYLE REPORTS FOUND!")
    sys.exit(1)
