#!/usr/bin/env bash
#
# Build -> verify signature -> publish a slim public update APK.
#
#   tools/release/release.sh "Notes shown in the in-app updater."
#   tools/release/release.sh --dry-run "…"     build + verify, publish nothing
#   KEEP=4 tools/release/release.sh "…"        keep the newest 4 releases
#
# The APK is signed with the SAME key as every previous release. Android refuses
# an in-place update when the signing certificate changes, so a mismatch would
# strand every existing install — the script therefore verifies the built APK's
# certificate against the currently published one and refuses to publish on a
# mismatch. Nothing else in this script matters as much as that check.
#
# Version comes from apps/android/app/build.gradle.kts (bump it before running).
set -euo pipefail

cd "$(dirname "$0")/../.."
REPO=kirtan05/land-records-releases
KEEP=${KEEP:-3}
DRY=0
[ "${1:-}" = "--dry-run" ] && { DRY=1; shift; }
NOTES=${1:-}

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# --- 0. preflight ------------------------------------------------------------
[ -n "$NOTES" ] || die "give the release notes as the first argument"
command -v gh >/dev/null || die "gh CLI not found"
gh auth status >/dev/null 2>&1 || die "gh is not logged in"
[ -z "$(git status --porcelain -- apps/android/ tools/ data/)" ] \
  || die "working tree is dirty — commit before releasing"

APKSIGNER=$(ls "$HOME"/Android/Sdk/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[ -x "$APKSIGNER" ] || die "apksigner not found in the Android SDK"

GRADLE=apps/android/app/build.gradle.kts
VCODE=$(grep -oP 'versionCode\s*=\s*\K\d+' $GRADLE)
VNAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' $GRADLE)
TAG="v$VNAME"
say "Releasing $TAG (versionCode $VCODE)"

PUB_CODE=$(curl -fsSL "https://raw.githubusercontent.com/$REPO/main/update.json" \
           | grep -oP '"versionCode"\s*:\s*\K\d+')
[ "$VCODE" -gt "$PUB_CODE" ] \
  || die "versionCode $VCODE is not greater than the published $PUB_CODE — bump it in $GRADLE"

# --- 1. build the slim APK ---------------------------------------------------
# The output directory is removed first: AGP packages incrementally, and building
# slim over a previous seeded APK leaves the old bytes as dead space (a 34 MB
# payload in a 162 MB file).
say "Building slim APK (no personal data seed)"
rm -rf apps/android/app/build/outputs/apk/debug
(cd apps/android && ./gradlew :app:assembleDebug -Pslim -q)
APK=apps/android/app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || die "build produced no APK"

# --- 2. verify what we are about to publish ----------------------------------
say "Verifying the APK"
SEEDED=$(unzip -l "$APK" | grep -c 'assets/seed/' || true)
[ "$SEEDED" -eq 0 ] || die "the APK contains $SEEDED personal seed files — do NOT publish it"

SIZE=$(stat -c%s "$APK")
[ "$SIZE" -lt 60000000 ] || die "APK is $((SIZE/1000000)) MB — too large for a slim build"

# THE check: the signing certificate must match what is already published.
say "Checking the signing certificate against the published APK"
PUB_APK=$(mktemp -t pubapk.XXXXXX.apk)
trap 'rm -f "$PUB_APK"' EXIT
curl -fsSL -o "$PUB_APK" \
  "https://github.com/$REPO/releases/latest/download/land-records.apk"
certof() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | grep -m1 -oE '[0-9a-f]{64}'; }
NEW_CERT=$(certof "$APK"); OLD_CERT=$(certof "$PUB_APK")
echo "  published: $OLD_CERT"
echo "  new:       $NEW_CERT"
[ -n "$NEW_CERT" ] || die "the new APK is not signed"
[ "$NEW_CERT" = "$OLD_CERT" ] || die \
"SIGNING KEY MISMATCH.

Publishing this would break the in-app update for every existing install —
Android only allows an in-place update when the certificate matches, so users
would have to uninstall (losing their records) and install again.

The key lives at ~/.android/debug.keystore and is pinned in $GRADLE.
If it was regenerated, restore the original from backup and rebuild."
echo "  signature matches — in-place update will work"

"$APKSIGNER" verify "$APK" >/dev/null || die "apksigner could not verify the APK"
printf '  %s, %s MB, %s files\n' "$TAG" "$((SIZE/1000000))" \
  "$(unzip -l "$APK" | tail -1 | awk '{print $2}')"

if [ "$DRY" = 1 ]; then say "Dry run — nothing published"; exit 0; fi

# --- 3. publish --------------------------------------------------------------
say "Publishing $TAG to $REPO"
OUT=$(mktemp -d); cp "$APK" "$OUT/land-records.apk"
gh release view "$TAG" -R "$REPO" >/dev/null 2>&1 \
  && gh release delete "$TAG" -R "$REPO" --yes --cleanup-tag
gh release create "$TAG" "$OUT/land-records.apk" -R "$REPO" -t "$TAG" -n "$NOTES"
rm -rf "$OUT"

say "Pointing update.json at $TAG"
WORK=$(mktemp -d)
git clone -q --depth 1 "https://github.com/$REPO.git" "$WORK/r"
python3 - "$WORK/r/update.json" "$VCODE" "$VNAME" "$NOTES" <<'PY'
import json, sys
path, code, name, notes = sys.argv[1:5]
o = json.load(open(path))
o.update(versionCode=int(code), versionName=name, notes=notes,
         apkUrl=f"https://github.com/kirtan05/land-records-releases/releases/latest/download/land-records.apk")
json.dump(o, open(path, "w"), indent=2, ensure_ascii=False)
open(path, "a").write("\n")
PY
git -C "$WORK/r" commit -qam "Release $TAG" && git -C "$WORK/r" push -q
rm -rf "$WORK"

# --- 4. prune old releases ---------------------------------------------------
say "Pruning old releases (keeping the newest $KEEP)"
gh release list -R "$REPO" --json tagName,createdAt \
  --jq "sort_by(.createdAt) | reverse | .[$KEEP:] | .[].tagName" \
  | while read -r old; do
      [ -n "$old" ] || continue
      echo "  deleting $old"
      gh release delete "$old" -R "$REPO" --yes --cleanup-tag
    done

say "Done — $TAG is live"
gh release list -R "$REPO" -L 5
