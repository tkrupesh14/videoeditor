#!/usr/bin/env bash
# Secret scan. No external dependencies.
#   scripts/secret-scan.sh            -> scan staged files (pre-commit)
#   scripts/secret-scan.sh --all      -> scan every tracked file
#   scripts/secret-scan.sh --range A..B -> scan files changed in a commit range (pre-push)
# Exit code 1 if anything suspicious is found.
set -euo pipefail

mode="${1:-staged}"
case "$mode" in
  --all) files=$(git ls-files) ;;
  --range) files=$(git diff --name-only --diff-filter=ACMR "$2") ;;
  *) files=$(git diff --cached --name-only --diff-filter=ACMR) ;;
esac

fail=0

# 1. Forbidden file names.
forbidden='(^|/)(local\.properties|\.env(\..*)?|.*\.jks|.*\.keystore|.*\.p12|.*\.pem|.*\.key|google-services\.json|secrets?\.(properties|json|ya?ml))$'
allowed_env='(^|/)\.env\.example$'
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if echo "$f" | grep -Eq "$forbidden" && ! echo "$f" | grep -Eq "$allowed_env"; then
    echo "secret-scan: forbidden file: $f"
    fail=1
  fi
done <<< "$files"

# 2. Secret-shaped content.
patterns=(
  'sk-ant-[A-Za-z0-9_-]{20,}'                 # Anthropic API key
  'AKIA[0-9A-Z]{16}'                          # AWS access key id
  'gh[pousr]_[A-Za-z0-9]{30,}'                # GitHub tokens
  'github_pat_[A-Za-z0-9_]{30,}'
  'vedb1_[A-Za-z0-9_-]{30,}'                 # vedit-mcp bridge token
  'xox[baprs]-[A-Za-z0-9-]{10,}'              # Slack
  'AIza[0-9A-Za-z_-]{35}'                     # Google API key
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  'sk-[A-Za-z0-9]{40,}'                       # generic sk- keys
)
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || continue
  # Skip binaries and this script itself (it contains the patterns).
  [ "$f" = "scripts/secret-scan.sh" ] && continue
  grep -Iq . "$f" 2>/dev/null || continue
  if [ "$mode" = "staged" ]; then content=$(git show ":$f" 2>/dev/null || true); else content=$(cat "$f"); fi
  for p in "${patterns[@]}"; do
    if hits=$(printf '%s' "$content" | grep -nE -e "$p" | grep -v 'secret-scan:allow'); then
      echo "secret-scan: possible secret in $f:"
      echo "$hits" | cut -c1-120 | sed 's/^/    /'
      fail=1
    fi
  done
done <<< "$files"

if [ "$fail" -ne 0 ]; then
  echo "secret-scan: FAILED. Remove the secret or, for a known-fake test value, add 'secret-scan:allow' on that line."
  exit 1
fi
echo "secret-scan: clean ($(printf '%s\n' "$files" | grep -c . || true) files)"
