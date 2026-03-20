#!/usr/bin/env bash
# build-plugin.sh — builds the OpenCode JetBrains plugin from source
# Usage: ./build-plugin.sh [--skip-opencode] [--skip-webapp]
#
# Output: packages/jetbrains-plugin/build/distributions/opencode-jetbrains-plugin-1.0.0.zip

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$ROOT/packages/jetbrains-plugin"
OPENCODE_DIR="$ROOT/packages/opencode"
APP_DIR="$ROOT/packages/app"
DIST="$PLUGIN_DIR/build/distributions/opencode-jetbrains-plugin-1.0.0.zip"

REQUIRED_BINS=(
  "$OPENCODE_DIR/dist/opencode-windows-arm64/bin/opencode.exe"
  "$OPENCODE_DIR/dist/opencode-windows-x64-baseline/bin/opencode.exe"
  "$OPENCODE_DIR/dist/opencode-darwin-arm64/bin/opencode"
  "$OPENCODE_DIR/dist/opencode-darwin-x64-baseline/bin/opencode"
  "$OPENCODE_DIR/dist/opencode-linux-arm64/bin/opencode"
  "$OPENCODE_DIR/dist/opencode-linux-arm64-musl/bin/opencode"
  "$OPENCODE_DIR/dist/opencode-linux-x64-baseline/bin/opencode"
  "$OPENCODE_DIR/dist/opencode-linux-x64-baseline-musl/bin/opencode"
)

SKIP_OPENCODE=false
SKIP_WEBAPP=false

for arg in "$@"; do
  case $arg in
    --skip-opencode) SKIP_OPENCODE=true ;;
    --skip-webapp)   SKIP_WEBAPP=true   ;;
  esac
done

# ── colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
step()  { echo -e "\n${GREEN}▶ $*${NC}"; }
warn()  { echo -e "${YELLOW}⚠ $*${NC}"; }
fatal() { echo -e "${RED}✖ $*${NC}"; exit 1; }

# ── prerequisites ─────────────────────────────────────────────────────────────
step "Checking prerequisites"

command -v bun  >/dev/null 2>&1 || fatal "bun not found. Install from https://bun.sh"
command -v java >/dev/null 2>&1 || fatal "java not found. Need JDK 21+."

BUN="$(command -v bun)"
echo "  bun  → $BUN ($(bun --version))"
echo "  java → $(java -version 2>&1 | head -1)"

check_bins() {
  for bin in "${REQUIRED_BINS[@]}"; do
    [ -f "$bin" ] || return 1
  done
}

if $SKIP_OPENCODE; then
  warn "Skipping opencode build (--skip-opencode)"
  check_bins || fatal "Bundled binaries not found. Run without --skip-opencode first."
else
  step "Building opencode standalone binaries"
  cd "$OPENCODE_DIR"
  bun run build
  check_bins || fatal "Build completed but required bundled binaries are missing"
fi

# ── step 2: build webapp ──────────────────────────────────────────────────────
if $SKIP_WEBAPP; then
  warn "Skipping webapp build (--skip-webapp)"
  ls "$APP_DIR/dist/assets/index-"*.js >/dev/null 2>&1 \
    || fatal "Webapp dist not found. Run without --skip-webapp first."
else
  step "Building webapp (VITE_TARGET=jetbrains)"
  cd "$APP_DIR"
  VITE_TARGET=jetbrains bun run build
  echo "  Webapp built → $APP_DIR/dist"
fi

# ── step 3: build plugin ──────────────────────────────────────────────────────
step "Building JetBrains plugin"
cd "$PLUGIN_DIR"

# Skip the gradle tasks that re-run bun (already done above)
./gradlew clean buildPlugin -x buildOpencode -x buildWebapp

[ -f "$DIST" ] || fatal "Plugin ZIP not found after build: $DIST"

SIZE=$(du -sh "$DIST" | cut -f1)
echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}✔ Plugin ready — $SIZE${NC}"
echo -e "${GREEN}  $DIST${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo "  To install:"
echo "  1. Open WebStorm"
echo "  2. Settings → Plugins → ⚙ → Install Plugin from Disk"
echo "  3. Select the ZIP above"
echo "  4. Restart WebStorm"
echo ""
