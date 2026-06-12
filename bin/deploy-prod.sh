#!/bin/bash
#
# Deploy Wellness app to production environment
# Usage: ./bin/deploy-prod.sh /path/to/production/directory [/path/to/llm/directory]
#
# The optional LLM directory is where Claude Code CLI executes analysis queries.
# It must contain CLAUDE.md and .claude/settings.local.json with MCP configs.
# If not specified, the analysis module falls back to the project root.

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the script's directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Check for production directory argument
if [ -z "$1" ]; then
    echo -e "${RED}Error: Production directory not specified${NC}"
    echo ""
    echo "Usage: $0 /path/to/production/directory [/path/to/llm/directory]"
    echo ""
    echo "Arguments:"
    echo "  production-dir  Where to deploy the Wellness app files"
    echo "  llm-dir         (Optional) Where Claude Code CLI runs analysis queries"
    echo "                  Must have CLAUDE.md + .claude/ with MCP configs"
    echo ""
    echo "Example:"
    echo "  $0 /home/user/wellness-prod"
    echo "  $0 /home/user/wellness-prod /home/user/proj/health"
    exit 1
fi

PROD_DIR="$1"
LLM_DIR="$2"

# Expand tilde if present
PROD_DIR="${PROD_DIR/#\~/$HOME}"

# Convert to absolute path
PROD_DIR="$(cd "$(dirname "$PROD_DIR")" 2>/dev/null && pwd)/$(basename "$PROD_DIR")" 2>/dev/null || PROD_DIR="$1"

# Handle optional LLM directory
if [ -n "$LLM_DIR" ]; then
    LLM_DIR="${LLM_DIR/#\~/$HOME}"
    LLM_DIR="$(cd "$LLM_DIR" 2>/dev/null && pwd)" 2>/dev/null || LLM_DIR="$2"

    if [ ! -d "$LLM_DIR" ]; then
        echo -e "${RED}Error: LLM directory does not exist: $LLM_DIR${NC}"
        exit 1
    fi

    if [ ! -f "$LLM_DIR/CLAUDE.md" ] && [ ! -f "$LLM_DIR/AGENTS.md" ]; then
        echo -e "${YELLOW}Warning: No CLAUDE.md or AGENTS.md found in LLM directory${NC}"
        echo -e "${YELLOW}Claude Code CLI may not have the system prompt available${NC}"
    fi

    if [ ! -d "$LLM_DIR/.claude" ]; then
        echo -e "${YELLOW}Warning: No .claude/ directory found in LLM directory${NC}"
        echo -e "${YELLOW}MCP servers may not be configured${NC}"
    fi
fi

# Safety check: don't deploy to the dev directory
if [ "$PROD_DIR" = "$PROJECT_ROOT" ]; then
    echo -e "${RED}Error: Production directory cannot be the same as development directory${NC}"
    exit 1
fi

echo -e "${GREEN}Deploying Wellness app to production...${NC}"
echo "  Source:  $PROJECT_ROOT"
echo "  Target:  $PROD_DIR"
if [ -n "$LLM_DIR" ]; then
    echo "  LLM dir: $LLM_DIR"
fi
echo ""

# Create production directory if it doesn't exist
if [ ! -d "$PROD_DIR" ]; then
    echo -e "${YELLOW}Creating production directory...${NC}"
    mkdir -p "$PROD_DIR"
fi

# Function to sync a directory
sync_dir() {
    local src="$1"
    local dest="$2"
    local name="$3"

    if [ -d "$src" ]; then
        echo "  Syncing $name..."
        mkdir -p "$dest"
        # No blanket 'protect */' filter: it shielded every receiver-only
        # directory AND its contents from --delete, so removed/renamed code
        # packages stayed importable in prod forever. The shipped code dirs
        # (src/public/mcp_servers) have no prod-local content to protect; the
        # db excludes are belt-and-suspenders should a data dir ever ship.
        rsync -a --delete \
            --exclude='__pycache__' \
            --exclude='*.pyc' \
            --exclude='*.pyo' \
            --exclude='*.db' \
            --exclude='*.db-wal' \
            --exclude='*.db-shm' \
            "$src/" "$dest/"
    fi
}

# Function to copy a file
copy_file() {
    local src="$1"
    local dest="$2"
    local name="$3"

    if [ -f "$src" ]; then
        echo "  Copying $name..."
        cp "$src" "$dest"
    fi
}

# All deployment is driven by bin/deploy.manifest — the single source of truth
# for what ships to production. Nothing is copied unless it is listed there, so
# dev tooling, docs, and tests can never leak in by accident (and the guard
# test test/test_deploy_manifest.py fails if a tracked file isn't classified).
MANIFEST="$PROJECT_ROOT/bin/deploy.manifest"
if [ ! -f "$MANIFEST" ]; then
    echo -e "${RED}Error: deploy manifest not found: $MANIFEST${NC}"
    exit 1
fi

echo -e "${GREEN}Copying production files (per bin/deploy.manifest)...${NC}"
echo ""

SHIPPED_BIN=""

while read -r action target _rest; do
    # Skip blanks and comments
    [[ -z "$action" || "$action" == \#* ]] && continue
    case "$action" in
        ship-dir)
            sync_dir "$PROJECT_ROOT/$target" "$PROD_DIR/$target" "$target/"
            ;;
        ship-file)
            copy_file "$PROJECT_ROOT/$target" "$PROD_DIR/$target" "$target"
            ;;
        ship-bin)
            SHIPPED_BIN="$SHIPPED_BIN $target"
            mkdir -p "$PROD_DIR/bin"
            src="$PROJECT_ROOT/bin/$target"
            if [ ! -f "$src" ]; then
                echo -e "  ${YELLOW}Skipping bin/$target (not present locally)${NC}"
                continue
            fi
            # Hook templates are never clobbered — preserve prod customizations.
            if [[ "$target" == *-workout-hook.sh ]] && [ -f "$PROD_DIR/bin/$target" ]; then
                echo "    Skipping bin/$target (already exists in production)"
                continue
            fi
            echo "  Copying bin/$target..."
            cp "$src" "$PROD_DIR/bin/$target"
            chmod +x "$PROD_DIR/bin/$target"
            ;;
        exclude|exclude-bin)
            : # Documented as intentionally not deployed; enforced by the guard test.
            ;;
        *)
            echo -e "  ${YELLOW}Unknown manifest action '$action' (target: $target) — skipping${NC}"
            ;;
    esac
done < "$MANIFEST"

# The server expects data/ to exist (it creates the DB files itself; the dir
# is no longer a ship-dir so rsync --delete can never touch live databases).
mkdir -p "$PROD_DIR/data"

# Deploys never delete from prod bin/ (hooks may carry prod-local versions),
# so dead scripts used to accumulate silently. Warn about strays — anything
# that is neither a ship-bin target nor a known prod-local file — instead of
# deleting them.
KNOWN_LOCAL_BIN="quick-deploy.sh backup-gitignored.sh"
if [ -d "$PROD_DIR/bin" ]; then
    for f in "$PROD_DIR/bin"/*; do
        [ -f "$f" ] || continue
        name="$(basename "$f")"
        case " $SHIPPED_BIN $KNOWN_LOCAL_BIN " in
            *" $name "*) ;;
            *) echo -e "  ${YELLOW}Stray prod bin/$name — not in the manifest; remove it manually if dead${NC}" ;;
        esac
    done
fi

echo ""

# Write LLM directory config if specified
if [ -n "$LLM_DIR" ]; then
    echo "  Writing LLM directory config..."
    echo "$LLM_DIR" > "$PROD_DIR/.llm-dir"
fi

echo -e "${GREEN}Deployment complete!${NC}"
echo ""
echo -e "${YELLOW}Next steps for production setup:${NC}"
echo ""
echo "  1. Create virtual environment:"
echo "     cd $PROD_DIR"
echo "     python3 -m venv venv"
echo "     source venv/bin/activate"
echo ""
echo "  2. Install dependencies:"
echo "     pip install -r requirements.txt"
echo ""
echo "  3. Start the server:"
echo "     ./bin/server.sh start"
echo ""
echo "  4. (Optional) Set up as a systemd service for auto-restart"
echo ""

if [ -n "$LLM_DIR" ]; then
    echo -e "${YELLOW}LLM execution directory: $LLM_DIR${NC}"
    echo -e "  Saved to $PROD_DIR/.llm-dir"
    echo -e "  Override at runtime with: ANALYSIS_LLM_DIR=/other/path ./bin/server.sh start"
    echo ""
fi
