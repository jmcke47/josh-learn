#!/usr/bin/env bash
# One-command publish to GitHub Releases (jmcke47/josh-learn).
#
#   ./publish.sh [patch|minor|major|X.Y.Z|current] ["release notes"]
#
# Requires GH_TOKEN (or `gh auth login`) with Contents: read+write on the repo.
# The repo must already exist: this script does NOT create it.
# Steps: preflight (repo exists + push permission) -> build via release.sh -> commit ->
#        push source -> create GitHub release v<ver> with josh-learn.apk + update.json ->
#        verify the releases/latest/download URLs resolve.
set -euo pipefail
cd "$(dirname "$0")"
REPO="${REPO:-jmcke47/josh-learn}"
BUMP="${1:-patch}"; NOTES="${2:-Bug fixes and improvements.}"

gh auth status >/dev/null 2>&1 || { echo "ERROR: gh is not authenticated. Run 'gh auth login' or set GH_TOKEN." >&2; exit 1; }

# Safety: never publish signing secrets.
if git ls-files | grep -qE '(^|/)keystore/|\.jks$|keystore\.properties'; then
  echo "ERROR: keystore files are tracked by git — refusing to push." >&2; exit 1
fi

# Preflight BEFORE bumping the version: the repo must exist and we must be able to push.
if ! PERM=$(gh api "repos/$REPO" --jq '.permissions.push' 2>/dev/null); then
  echo "ERROR: GitHub repo $REPO doesn't exist or this token can't see it." >&2
  echo "       Create it at https://github.com/new (name: ${REPO#*/}, public), or grant the token access, then re-run." >&2
  exit 2
fi
if [[ "$PERM" != "true" ]]; then
  echo "ERROR: token has no push permission on $REPO." >&2; exit 3
fi

./release.sh "$BUMP" "$NOTES"
VER=$(grep '^VERSION_NAME=' version.properties | cut -d= -f2)
TAG="v$VER"

git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$REPO.git"
gh auth setup-git >/dev/null 2>&1 || true

git config user.name >/dev/null 2>&1 || git config user.name "jmcke47"
git config user.email >/dev/null 2>&1 || git config user.email "44462275+jmcke47@users.noreply.github.com"
git add -A
git diff --cached --quiet || git commit -qm "Release $TAG"
git branch -M main
git push -u origin main

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" release/latest/josh-learn.apk release/latest/update.json --repo "$REPO" --clobber
else
  gh release create "$TAG" release/latest/josh-learn.apk release/latest/update.json \
    --repo "$REPO" --title "Josh Learn $TAG" --notes "$NOTES" --target main --latest
fi

echo "==> Verifying public download URLs"
BASE="https://github.com/$REPO/releases/latest/download"
for f in update.json josh-learn.apk; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -L "$BASE/$f")
  echo "    $BASE/$f -> HTTP $code"
done
CODE=$(grep '^VERSION_CODE=' version.properties | cut -d= -f2)
for i in 1 2 3 4 5 6; do   # GitHub's CDN can serve the previous file for a few seconds
  J=$(curl -sL "$BASE/update.json?nocache=$(date +%s)")
  echo "$J" | grep -q "\"versionCode\": *$CODE\b" && break
  sleep 5
done
echo "$J"
echo "==> Published $TAG: https://github.com/$REPO/releases/tag/$TAG"
