#!/bin/bash

# Server control script for Wellness App
# Usage: ./server.sh {start|stop|status|restart|logs|follow}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENV_DIR="$PROJECT_ROOT/venv"
PID_FILE="$PROJECT_ROOT/.server.pid"
LOG_FILE="$PROJECT_ROOT/server.log"
SRC_DIR="$PROJECT_ROOT/src"

# Default port (9000 for production, 9001 for testing)
PORT=9000
TEST_MODE=""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    fi
}

is_running() {
    local pid=$(get_pid)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    if lsof -i :"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

start_server() {
    if is_running; then
        echo -e "${YELLOW}Wellness app is already running${NC}"
        status_server
        return 1
    fi

    echo -e "${GREEN}Starting Wellness app...${NC}"

    cd "$PROJECT_ROOT"

    if [ -d "$VENV_DIR" ]; then
        source "$VENV_DIR/bin/activate"
    fi

    nohup python3 "$SRC_DIR/server.py" --port "$PORT" $TEST_MODE > "$LOG_FILE" 2>&1 &
    local pid=$!
    echo $pid > "$PID_FILE"

    sleep 2

    if is_running; then
        echo -e "${GREEN}Wellness app started (PID: $pid)${NC}"
        echo -e "URL: http://localhost:$PORT"
    else
        echo -e "${RED}Failed to start. Check logs:${NC}"
        tail -20 "$LOG_FILE"
        rm -f "$PID_FILE"
        return 1
    fi
}

stop_server() {
    if ! is_running; then
        echo -e "${YELLOW}Wellness app is not running${NC}"
        rm -f "$PID_FILE"
        return 0
    fi

    echo -e "${YELLOW}Stopping Wellness app...${NC}"

    local pid=$(get_pid)
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null
        fi
    fi

    fuser -k "$PORT/tcp" 2>/dev/null
    rm -f "$PID_FILE"
    echo -e "${GREEN}Wellness app stopped${NC}"
}

status_server() {
    if is_running; then
        local pid=$(get_pid)
        echo -e "Wellness app: ${GREEN}running${NC} (PID: ${pid:-?}) — http://localhost:$PORT"
    else
        echo -e "Wellness app: ${RED}not running${NC}"
    fi
}

restart_server() {
    echo "Restarting..."
    stop_server
    sleep 1
    start_server
}

show_logs() {
    if [ -f "$LOG_FILE" ]; then
        echo -e "${YELLOW}=== Wellness App Logs (last 50 lines) ===${NC}"
        tail -50 "$LOG_FILE"
    else
        echo -e "${YELLOW}No log file found${NC}"
    fi
}

follow_logs() {
    if [ -f "$LOG_FILE" ]; then
        echo -e "${YELLOW}=== Following Wellness app logs (Ctrl+C to exit) ===${NC}"
        tail -f "$LOG_FILE"
    else
        echo -e "${YELLOW}No log file found${NC}"
    fi
}

usage() {
    echo "Usage: $0 [--test] {start|stop|status|restart|logs|follow}"
    echo ""
    echo "Options:"
    echo "  --test  - Run in testing mode (port 9001 instead of 9000)"
    echo ""
    echo "Commands:"
    echo "  start   - Start the Wellness app"
    echo "  stop    - Stop the Wellness app"
    echo "  status  - Show running state"
    echo "  restart - Restart the Wellness app"
    echo "  logs    - Show last 50 lines of logs"
    echo "  follow  - Follow logs in real-time"
}

# Parse --test flag
if [ "$1" = "--test" ]; then
    TEST_MODE="--test"
    PORT=9001
    shift
fi

case "$1" in
    start)   start_server ;;
    stop)    stop_server ;;
    status)  status_server ;;
    restart) restart_server ;;
    logs)    show_logs ;;
    follow)  follow_logs ;;
    *)       usage; exit 1 ;;
esac
