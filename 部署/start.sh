#!/usr/bin/env bash
# ==========================================================================
#  前台启动（不装 systemd 时用）
#
#      bash 部署/start.sh                 # 0.0.0.0:8765
#      PORT=9000 bash 部署/start.sh        # 换端口
#      INVOICE_AUTH=财务:123456 bash 部署/start.sh
#
#  Ctrl+C 退出。生产环境建议用 deploy.sh 的 systemd 方式。
# ==========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8765}"
HOST="${HOST:-0.0.0.0}"

if [ -x "$APP_DIR/.venv/bin/python" ]; then
  PY="$APP_DIR/.venv/bin/python"
elif command -v python3 >/dev/null 2>&1; then
  PY="$(command -v python3)"
  echo "提示：没找到 .venv，用系统的 python3（依赖需自己装好）"
else
  echo "找不到 python3，请先执行 部署/deploy.sh" >&2
  exit 1
fi

exec "$PY" "$APP_DIR/程序/server.py" --host "$HOST" --port "$PORT"
