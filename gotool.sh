#!/usr/bin/env bash
# Run any GoTool command from WSL with this machine's paths (redo_local.properties).
# Usage: bash gotool.sh cmd=<command> [prop=value ...]
#   e.g. bash gotool.sh cmd=review run=latest
set -e
cd "$(dirname "$0")"
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
exec java -cp "target/classes:$(cat target/cp.txt)" autoprob.GoTool redo_local.properties "$@"
