#!/bin/bash
# Runs every scripts/test/*.test.sh and fails if any of them does.
# CI: the "Shell — script tests" job. Locally: bash scripts/test/run-all.sh

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
failed=0
for t in "$HERE"/*.test.sh; do
    echo "== $(basename "$t")"
    if ! bash "$t"; then
        failed=$((failed + 1))
    fi
done

if [ "$failed" -gt 0 ]; then
    echo ""
    echo "${failed} shell test file(s) failed."
    exit 1
fi
echo ""
echo "All shell test files passed."
