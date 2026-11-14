#!/data/data/com.termux/files/usr/bin/bash
# ChildGuard Termux dashboard launcher
# Usage:
#   ./dashboard.sh            → interactive dashboard
#   ./dashboard.sh --sync     → sync from server, then interactive
#   ./dashboard.sh --help     → all one-shot flags
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. python3 present?
if ! command -v python3 >/dev/null 2>&1; then
    echo "[!] python3 not found. Install it:"
    echo "    pkg update && pkg install python"
    exit 1
fi

# 2. create home dir
mkdir -p "${HOME}/.childguard/exports"

# 3. first-run config hint
if [[ ! -f "${HOME}/.childguard/config.json" ]]; then
    echo "[i] First run: configure your server/token in the menu (option 8)"
    echo "    or write ~/.childguard/config.json:"
    echo "    {\"server\":\"http://<host>:38080\",\"token\":\"<token>\"}"
    echo
fi

exec python3 "${DIR}/dashboard.py" "$@"
