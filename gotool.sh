#!/usr/bin/env bash
# Run any GoTool command from WSL with this machine's paths (redo_local.properties).
# Usage: bash gotool.sh cmd=<command> [prop=value ...]
#   e.g. bash gotool.sh cmd=review run=latest
#
# The tool is compiled first, but only when something has changed since the last time: Maven
# takes about 20 s to find that nothing has, and about a minute to compile, and prints nothing
# while it works. target/.gotool-built is as old as the start of the last compilation that went
# through, so a source, a resource or the pom that is newer, or a folder a file was added to or
# taken from, means compiling again. GOTOOL_BUILD=1 compiles whatever the dates say.
set -e
cd "$(dirname "$0")"
built=target/.gotool-built
why=""
if [ -n "$GOTOOL_BUILD" ]; then
  why="GOTOOL_BUILD is set"
elif [ ! -f "$built" ] || [ ! -f target/cp.txt ] || [ ! -d target/classes ]; then
  why="nothing here says when it was last compiled"
else
  # the first thing newer than the last compilation; a search that fails counts as a change
  newer=$(find src/main pom.xml -newer "$built" -print -quit 2>/dev/null || echo "something")
  if [ -n "$newer" ]; then why="$newer has changed since the last time"; fi
fi
if [ -n "$why" ]; then
  echo "compiling with Maven ($why): this can take a minute, and nothing is printed until it is done..."
  mkdir -p target
  # stamped before the compilation starts, so that an edit made during it is seen next time
  : > target/.gotool-building
  started=$SECONDS
  mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  mv target/.gotool-building "$built"
  echo "compiled in $((SECONDS - started)) s"
fi
exec java -cp "target/classes:$(cat target/cp.txt)" autoprob.GoTool redo_local.properties "$@"
