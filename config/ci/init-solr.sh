#!/bin/bash

# set up solr for testmine
# testmine's setup script populates these empty indexes

set -euo pipefail

if [ "$#" != "1" ]; then
   echo "Usage: $0 <workspace_dir>"
   exit 2
fi

WORKSPACE_DIR=$1

cd "$WORKSPACE_DIR"

SOLR_VERSION=10.1.0
SOLR_SHA512=1e0cac2a3f5d415fbf67fdb5efe62944fbf88f009aaa41113862e63dc1e9264d3ad5bdf532eb185b18687ca93becc807600ceec957c5a890c3026c0ba8d951d8
SOLR_PACKAGE=solr-${SOLR_VERSION}.tgz
SOLR_DIR=solr-${SOLR_VERSION}
SOLR=${SOLR_DIR}/bin/solr

create_solr_core() {
    local core_name=$1

    local status core_exists
    status=$(curl -fsS "http://localhost:8983/solr/admin/cores?action=STATUS&core=${core_name}")
    core_exists=$(jq -r --arg core "${core_name}" '
        if .responseHeader.status != 0 or (.status | type) != "object"
        then error("Invalid Solr core status response")
        else .status[$core].name == $core end' <<< "$status")

    if [ "$core_exists" = "true" ]; then
        echo "Solr core ${core_name} already exists"
    else
        "${SOLR}" create -c "${core_name}"
    fi
}

if [ ! -d "$SOLR_DIR" ]; then
    if [ ! -f "$SOLR_PACKAGE" ]; then
        curl -fL --retry 3 -o "$SOLR_PACKAGE" \
            "https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/${SOLR_PACKAGE}"
    fi
    printf '%s  %s\n' "$SOLR_SHA512" "$SOLR_PACKAGE" | sha512sum --check -
    tar xzf "$SOLR_PACKAGE"
fi

"${SOLR}" restart --user-managed
create_solr_core intermine-search
create_solr_core intermine-autocomplete
