#!/usr/bin/env bash
#
# setup-new-repo.sh
# Full onboarding script for a new repo created from this template.
#
# Steps performed:
#   1. Copy all repository rulesets from this template to the target repo
#   2. Assign the "API Marketplace" team with maintain access
#   3. Warn if any Actions secrets exist on the target repo
#   4. Check that the secrets-scanner workflow is present and last run passed
#   5. Make the repository public
#
# Usage:
#   ./setup-new-repo.sh <target-repo-name>
#
# Example:
#   ./setup-new-repo.sh api-hmcts-crime-new
#
# Source repo is always auto-detected from the git 'origin' remote of the repo
# this script lives in. Target is always under the hmcts/ organisation.
#
# Requires: gh (authenticated via `gh auth login`) and jq.

set -euo pipefail

TARGET_NAME="${1:-}"

if [[ -z "$TARGET_NAME" ]]; then
  echo "Error: target repo name required."
  echo "Usage: $0 <target-repo-name>"
  exit 1
fi

TARGET="hmcts/$TARGET_NAME"

for cmd in gh jq git; do
  command -v "$cmd" >/dev/null || { echo "Error: '$cmd' not found on PATH."; exit 1; }
done

# Auto-detect source from the origin remote of the repo this script sits in.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
origin="$(git -C "$script_dir" remote get-url origin 2>/dev/null || true)"
SOURCE="$(sed -E 's#^.*github\.com[:/]##; s#\.git$##' <<<"$origin")"
if [[ -z "$SOURCE" ]]; then
  echo "Error: could not detect source repo from git origin."
  exit 1
fi

TEAMS=("api-marketplace:maintain" "api-marketplace-admin:admin")
SECRETS_SCANNER_WORKFLOW="secrets-scanner.yml"
STEPS=6

echo "============================================"
echo " setup-new-repo"
echo "============================================"
echo " Source (template): $SOURCE"
echo " Target:            $TARGET"
echo "============================================"
echo

# ---------------------------------------------------------------------------
# 1. Rulesets
# ---------------------------------------------------------------------------
echo "[ 1/6 ] Copying repository rulesets..."

FILTER='{name, target, enforcement, conditions, rules, bypass_actors}'

EXISTING=()
while IFS= read -r line; do
  [[ -n "$line" ]] && EXISTING+=("$line")
done < <(gh api "repos/$TARGET/rulesets" --paginate -q '.[].name' 2>/dev/null || true)

RULESETS=()
while IFS= read -r line; do
  [[ -n "$line" ]] && RULESETS+=("$line")
done < <(gh api "repos/$SOURCE/rulesets" --paginate -q '.[] | "\(.id)\t\(.name)"')

if [[ ${#RULESETS[@]} -eq 0 ]]; then
  echo "  No rulesets found on $SOURCE — skipping."
else
  for row in "${RULESETS[@]}"; do
    id="${row%%$'\t'*}"
    name="${row#*$'\t'}"

    if printf '%s\n' "${EXISTING[@]}" | grep -Fxq "$name"; then
      echo "  Skipping '$name' — already exists on $TARGET"
      continue
    fi

    echo "  Importing ruleset: $name (source id $id)"
    body="$(gh api "repos/$SOURCE/rulesets/$id" | jq "$FILTER")"

    if gh api "repos/$TARGET/rulesets" --method POST --input - <<<"$body" >/dev/null; then
      echo "  ✓ created on $TARGET"
    else
      echo "  ✗ failed to create '$name'"
    fi
  done
fi

# ---------------------------------------------------------------------------
# 2. Team ownership
# ---------------------------------------------------------------------------
echo
echo "[ 2/6 ] Adding teams..."

existing_teams="$(gh api "repos/$TARGET/teams" --paginate -q '.[].slug' 2>/dev/null || true)"

for entry in "${TEAMS[@]}"; do
  slug="${entry%%:*}"
  permission="${entry#*:}"
  if grep -Fxq "$slug" <<<"$existing_teams"; then
    echo "  Skipping '$slug' — already has access to $TARGET"
  else
    if gh api "orgs/hmcts/teams/$slug/repos/$TARGET" \
        --method PUT \
        --field permission="$permission" >/dev/null 2>&1; then
      echo "  ✓ '$slug' granted '$permission' access"
    else
      echo "  ✗ failed to add '$slug' — check the team exists and you have org admin rights"
    fi
  fi
done

# ---------------------------------------------------------------------------
# 3. Remove individual direct-access collaborators
# ---------------------------------------------------------------------------
echo
echo "[ 3/6 ] Removing individual collaborators with direct access..."

direct_users=()
while IFS= read -r line; do
  [[ -n "$line" ]] && direct_users+=("$line")
done < <(gh api "repos/$TARGET/collaborators?affiliation=direct" --paginate -q '.[].login' 2>/dev/null || true)

if [[ ${#direct_users[@]} -eq 0 ]]; then
  echo "  ✓ No individual collaborators found"
else
  echo "  Found ${#direct_users[@]} direct collaborator(s):"
  for user in "${direct_users[@]}"; do
    echo "    - $user"
  done
  read -r -p "  Remove all of the above? [y/N] " confirm
  if [[ "$(tr '[:upper:]' '[:lower:]' <<<"$confirm")" == "y" ]]; then
    for user in "${direct_users[@]}"; do
      if gh api "repos/$TARGET/collaborators/$user" --method DELETE >/dev/null 2>&1; then
        echo "  ✓ removed $user"
      else
        echo "  ✗ failed to remove $user"
      fi
    done
  else
    echo "  Skipped — remove manually via Settings → Collaborators and teams → Direct access"
  fi
fi

# ---------------------------------------------------------------------------
# 4. Secrets check
# ---------------------------------------------------------------------------
echo
echo "[ 4/6 ] Checking for repository secrets..."

secrets="$(gh api "repos/$TARGET/actions/secrets" -q '.secrets[].name' 2>/dev/null || true)"
if [[ -z "$secrets" ]]; then
  echo "  ✓ No repository secrets found — safe to make public"
else
  echo "  ⚠ WARNING: the following secrets exist on $TARGET:"
  while IFS= read -r s; do
    echo "    - $s"
  done <<<"$secrets"
  echo "  Review these before making the repository public."
fi

# ---------------------------------------------------------------------------
# 4. Secrets scanner workflow
# ---------------------------------------------------------------------------
echo
echo "[ 5/6 ] Checking secrets-scanner workflow..."

if ! gh api "repos/$TARGET/contents/.github/workflows/$SECRETS_SCANNER_WORKFLOW" >/dev/null 2>&1; then
  echo "  ✗ $SECRETS_SCANNER_WORKFLOW not found in $TARGET"
  echo "  Copy it from $SOURCE before proceeding."
else
  echo "  ✓ $SECRETS_SCANNER_WORKFLOW is present"

  last_run="$(gh api "repos/$TARGET/actions/workflows/$SECRETS_SCANNER_WORKFLOW/runs" \
    -q '.workflow_runs[0] | "\(.conclusion)\t\(.status)\t\(.html_url)"' 2>/dev/null || true)"

  if [[ -z "$last_run" ]]; then
    echo "  ⚠ No workflow runs found — trigger a manual run before making the repo public"
  else
    conclusion="${last_run%%$'\t'*}"
    rest="${last_run#*$'\t'}"
    status="${rest%%$'\t'*}"
    url="${rest#*$'\t'}"

    if [[ "$conclusion" == "success" ]]; then
      echo "  ✓ Last run: $conclusion ($status)"
      echo "    $url"
    else
      echo "  ✗ Last run: $conclusion ($status) — resolve before making the repo public"
      echo "    $url"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 5. Make repository public
# ---------------------------------------------------------------------------
echo
echo "[ 6/6 ] Changing repository visibility to public..."

current_visibility="$(gh api "repos/$TARGET" -q '.visibility' 2>/dev/null || true)"

if [[ "$current_visibility" == "public" ]]; then
  echo "  Skipping — $TARGET is already public"
else
  read -r -p "  Make $TARGET public? This cannot be undone easily. [y/N] " confirm
  if [[ "$(tr '[:upper:]' '[:lower:]' <<<"$confirm")" == "y" ]]; then
    if gh api "repos/$TARGET" --method PATCH --field private=false >/dev/null; then
      echo "  ✓ $TARGET is now public"
    else
      echo "  ✗ failed to change visibility — you may need org owner permissions"
    fi
  else
    echo "  Skipped — run again or change manually via Settings → General → Danger Zone"
  fi
fi

# ---------------------------------------------------------------------------
# Cleanup: remove this script from the target repo if present
# ---------------------------------------------------------------------------
echo
script_name="$(basename "${BASH_SOURCE[0]}")"
if gh api "repos/$TARGET/contents/$script_name" >/dev/null 2>&1; then
  read -r -p "Remove $script_name from $TARGET now that setup is complete? [y/N] " confirm
  if [[ "$(tr '[:upper:]' '[:lower:]' <<<"$confirm")" == "y" ]]; then
    sha="$(gh api "repos/$TARGET/contents/$script_name" -q '.sha')"
    gh api "repos/$TARGET/contents/$script_name" --method DELETE \
      --field message="chore: remove $script_name after repo setup" \
      --field sha="$sha" >/dev/null
    echo "  ✓ $script_name deleted from $TARGET"
  fi
fi

echo
echo "Setup complete."
