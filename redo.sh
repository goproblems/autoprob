#!/usr/bin/env bash
# Run the redo command from WSL with this machine's paths (redo_local.properties).
# Usage: bash redo.sh path=sample_problems/gp-721.sgf [prop=value ...]
set -e
cd "$(dirname "$0")"
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
exec java -cp "target/classes:$(cat target/cp.txt)" autoprob.GoTool redo_local.properties cmd=redo "$@"
