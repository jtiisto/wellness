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
        rsync -a --delete --filter='protect */' \
            --exclude='__pycache__' \
            --exclude='*.pyc' \
            --exclude='*.pyo' \
            --exclude='*.db' \
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

echo -e "${GREEN}Copying production files...${NC}"
echo ""

# Copy source code
sync_dir "$PROJECT_ROOT/src" "$PROD_DIR/src" "src/"

# Copy public assets
sync_dir "$PROJECT_ROOT/public" "$PROD_DIR/public" "public/"

# Copy MCP tools
sync_dir "$PROJECT_ROOT/mcp" "$PROD_DIR/mcp" "mcp/"

# Copy data files
sync_dir "$PROJECT_ROOT/data" "$PROD_DIR/data" "data/"

# Copy bin scripts (excluding deploy script)
echo "  Syncing bin/..."
mkdir -p "$PROD_DIR/bin"
for script in "$PROJECT_ROOT/bin/"*; do
    script_name="$(basename "$script")"
    if [ "$script_name" != "deploy-prod.sh" ]; then
        cp "$script" "$PROD_DIR/bin/"
        chmod +x "$PROD_DIR/bin/$script_name"
    fi
done

# Copy requirements.txt
copy_file "$PROJECT_ROOT/requirements.txt" "$PROD_DIR/requirements.txt" "requirements.txt"

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
