#!/bin/bash
# Prints the SSH script of the deploy job (the `script: |` block of the "Deploy via SSH" step in
# .github/workflows/deploy.yml) to stdout, de-indented, so it can be syntax-checked, linted and run
# against stubs. Used by deploy-yml.test.sh, deploy-flow.test.sh and the ShellCheck job in ci.yml.
#
# Usage: scripts/test/extract-deploy-script.sh [path/to/deploy.yml]

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# --lintable: prefix a shebang and the one variable the action injects (RELEASE_VERSION), so ShellCheck
# can read the block as a script. The ShellCheck job pipes this into a file and lints it.
if [ "${1:-}" = "--lintable" ]; then
    printf '#!/bin/bash\nRELEASE_VERSION=v0.0.0\n'
    shift
fi
FILE="${1:-${HERE}/../../.github/workflows/deploy.yml}"

awk '
    inblock == 0 && /^          script: \|[[:space:]]*$/ { inblock = 1; next }
    inblock == 1 {
        if ($0 ~ /^[[:space:]]*$/) { print ""; next }
        if (substr($0, 1, 12) != "            ") { exit }
        print substr($0, 13)
    }
' "$FILE"
