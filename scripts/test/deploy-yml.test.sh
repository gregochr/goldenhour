#!/bin/bash
# Static checks on .github/workflows/deploy.yml (docs/engineering/engineering-gaps-plan.md, Phase 4, R1, R4, R5, R8, R16, R17).
#
# appleboy/ssh-action forwards ONLY the variables named in `envs:`, and reads their values from the step's
# `env:`. A variable listed in one and not the other silently arrives empty on the host, and for the smoke
# password that means "skipped with a warning" forever. So every smoke variable must be in both.

# SC2016: the single-quoted needles contain ${{ }} and $VAR on purpose; they are matched literally.
# shellcheck disable=SC2016
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEPLOY="${REPO}/.github/workflows/deploy.yml"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

# The step's env: block is the one that holds RELEASE_VERSION; its envs: line is the `envs:` key.
ENV_BLOCK="$(awk '/^        env:/ { on = 1; next } on && /^        with:/ { exit } on { print }' "$DEPLOY")"
ENVS_LINE="$(grep -E '^          envs:' "$DEPLOY")"

for name in RELEASE_VERSION GITHUB_ACTIONS SMOKE_ENFORCE SMOKE_LOGIN_REQUIRED SMOKE_DEPLOY_PASSWORD; do
    if printf '%s\n' "$ENV_BLOCK" | grep -qE "^          ${name}:"; then
        ok "${name} is assigned in the step's env:"
    else
        bad "${name} is missing from the step's env: block"
    fi
    if printf '%s\n' "$ENVS_LINE" | sed 's/^ *envs: *//' | tr ',' '\n' | grep -qx "$name"; then
        ok "${name} is listed in envs:"
    else
        bad "${name} is missing from the envs: list"
    fi
done

assert_contains "the password comes from a secret" "$ENV_BLOCK" 'SMOKE_DEPLOY_PASSWORD: ${{ secrets.SMOKE_DEPLOY_PASSWORD }}'
assert_contains "SMOKE_ENFORCE comes from a repository variable" "$ENV_BLOCK" 'SMOKE_ENFORCE: ${{ vars.SMOKE_ENFORCE }}'
assert_contains "SMOKE_LOGIN_REQUIRED comes from a repository variable" "$ENV_BLOCK" 'SMOKE_LOGIN_REQUIRED: ${{ vars.SMOKE_LOGIN_REQUIRED }}'

assert_file_has "30 minute command timeout (R5)" "$DEPLOY" "command_timeout: 30m"
assert_file_has "one deploy at a time (R8): the group" "$DEPLOY" "group: prod-deploy"
assert_file_has "one deploy at a time (R8): never cancelled" "$DEPLOY" "cancel-in-progress: false"

# The runner-side step checks the secret's characters (the SSH forwarding mangles anything else) and never prints it.
assert_file_has "runner-side step reads the secret into SMOKE_PW" "$DEPLOY" 'SMOKE_PW: ${{ secrets.SMOKE_DEPLOY_PASSWORD }}'
assert_file_has "runner-side step tests the allowed character set" "$DEPLOY" "grep -q '[^A-Za-z0-9._-]'"
assert_file_lacks "the runner-side step never echoes the value" "$DEPLOY" 'echo "$SMOKE_PW'
assert_file_lacks "the runner-side step never prints it with printf either" "$DEPLOY" 'printf "%s" "$SMOKE_PW" >&'

# R16: nothing that could print a secret.
if grep -qE '^[[:space:]]*debug:' "$DEPLOY"; then bad "appleboy debug must stay off (it would print the script and the secrets)"; else ok "no appleboy debug"; fi
SCRIPT="$(bash "$HERE/extract-deploy-script.sh" "$DEPLOY")"
assert_not_contains "no set -x in the SSH script" "$SCRIPT" "set -x"
assert_not_contains "the password is never echoed" "$SCRIPT" 'echo "$SMOKE_DEPLOY_PASSWORD'
assert_not_contains "the password is never in argv" "$SCRIPT" '$SMOKE_DEPLOY_PASSWORD'

# The script block was found and is plausible bash.
assert_contains "the script block was extracted" "$SCRIPT" "set -e"
SCRIPT_FILE="$(mktemp)"
trap 'rm -f "$SCRIPT_FILE"' EXIT
printf '%s\n' "$SCRIPT" > "$SCRIPT_FILE"
assert_eq "the SSH script passes bash -n" 0 "$(bash -n "$SCRIPT_FILE" 2>/dev/null; echo $?)"

# Ordering: the backup must run BEFORE the new configuration is materialised (R6), and the PGDATA guard
# must come after the configuration it reads.
line_of() { printf '%s\n' "$SCRIPT" | grep -n -m1 -F -- "$1" | cut -d: -f1; }
BACKUP_LINE="$(line_of 'pre-release-backup.sh" "${RELEASE_VERSION}"')"
NGINX_LINE="$(line_of 'git show "${RELEASE_VERSION}:nginx.conf" > nginx.conf')"
COMPOSE_LINE="$(line_of 'git show "${RELEASE_VERSION}:docker-compose.yml" > docker-compose.yml')"
GUARD_LINE="$(line_of 'PGDATA_SRC="$(grep')"
PULL_LINE="$(line_of 'docker compose pull goldenhour-backend')"
UP_LINE="$(line_of 'docker compose up -d --no-deps goldenhour-backend goldenhour-frontend')"
RECORD_LINE="$(line_of 'record-deployed-images.sh')"
PIN_LINE="$(line_of 'if [ -f docker-compose.override.yml ]')"
for n in BACKUP_LINE NGINX_LINE COMPOSE_LINE GUARD_LINE PULL_LINE UP_LINE RECORD_LINE PIN_LINE; do
    [ -n "${!n}" ] || bad "could not find the line for ${n}"
done
assert_eq "the pin guard comes first" 1 "$([ "$PIN_LINE" -lt "$BACKUP_LINE" ] && echo 1 || echo 0)"
assert_eq "backup before nginx.conf is overwritten" 1 "$([ "$BACKUP_LINE" -lt "$NGINX_LINE" ] && echo 1 || echo 0)"
assert_eq "backup before docker-compose.yml is overwritten" 1 "$([ "$BACKUP_LINE" -lt "$COMPOSE_LINE" ] && echo 1 || echo 0)"
assert_eq "the PGDATA guard reads the NEW compose file" 1 "$([ "$COMPOSE_LINE" -lt "$GUARD_LINE" ] && echo 1 || echo 0)"
assert_eq "the PGDATA guard runs before the pull" 1 "$([ "$GUARD_LINE" -lt "$PULL_LINE" ] && echo 1 || echo 0)"
assert_eq "images are recorded only after up" 1 "$([ "$UP_LINE" -lt "$RECORD_LINE" ] && echo 1 || echo 0)"
assert_contains "the guard's own comment survived" "$SCRIPT" "Refuse to start Postgres against a directory that holds no database"
assert_contains "the pin guard names who wrote the pin (R11)" "$SCRIPT" "head -n 2 docker-compose.override.yml"

# R4: no bare command inside the check function.
FUNC="$(printf '%s\n' "$SCRIPT" | awk '/^post_up_checks\(\) \{/ { on = 1 } on { print } on && /^\}/ { exit }')"
assert_contains "the check function exists" "$FUNC" "post_up_checks()"
BARE="$(printf '%s\n' "$FUNC" | grep -E 'bash "' | grep -vE '\|\| [a-z_]*rc=\$\?' || true)"
assert_eq "every command in the check function captures its rc with || rc=\$?" "" "$BARE"
assert_contains "the caller captures the rc" "$SCRIPT" 'post_up_checks || check_rc=$?'
assert_contains "the rollback's 1 or 2 is passed through" "$SCRIPT" 'exit "$rollback_rc"'

finish_tests
