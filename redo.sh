#!/usr/bin/env bash
# Run the redo command from WSL with this machine's paths (redo_local.properties).
# Usage: bash redo.sh path=sample_problems/gp-721.sgf [prop=value ...]
exec bash "$(dirname "$0")/gotool.sh" cmd=redo "$@"
