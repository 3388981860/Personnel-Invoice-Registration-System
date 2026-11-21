#!/usr/bin/env bash
# ==========================================================================
#  回滚到 Python 版（30 秒）
#
#  做的事：
#   1. 停 Java 和后端小服务
#   2. 恢复 Python 服务并启动（8765）
#   3. 恢复它原来的开机自启设置
#   4. 健康检查
#
#  用法（项目根目录）：
#      sudo bash 部署/java/rollback-python.sh
# ==========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_END=$'\033[0m'
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✔%s %s\n' "$C_OK" "$C_END" "$*"; }
warn() { printf '%s!%s %s\n' "$C_WARN" "$C_END" "$*"; }
die()  { printf '%s✘%s %s\n' "$C_ERR" "$C_END" "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 sudo 运行"

say "项目目录： $APP_DIR"

say ""
say "[1/4] 停 Java 版"
systemctl stop invoice-java.service 2>/dev/null || true
systemctl stop invoice-parse.service 2>/dev/null || true
systemctl stop invoice-audit.service 2>/dev/null || true
systemctl disable invoice-java.service >/dev/null 2>&1 || true
ok "Java 版已停"

say ""
say "[2/4] 恢复 Python 版"
if [ ! -f /etc/systemd/system/invoice-ledger.service ]; then
  die "找不到 invoice-ledger.service，无法回滚。请检查是否被删"
fi
systemctl start invoice-ledger.service
ok "invoice-ledger 已启动"

# 恢复原来的开机自启状态
if [ -f /tmp/invoice-ledger-was-enabled ]; then
  OLD="$(cat /tmp/invoice-ledger-was-enabled)"
  if [ "$OLD" = "enabled" ] || [ "$OLD" = "unknown" ]; then
    systemctl enable invoice-ledger.service >/dev/null 2>&1 || true
  fi
else
  systemctl enable invoice-ledger.service >/dev/null 2>&1 || true
fi

say ""
say "[3/4] 健康检查"
ready=0
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:8765/_health" >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  warn "Python 版没起来，最近日志："
  journalctl -u invoice-ledger -n 40 --no-pager || true
  die "回滚失败，需要人工介入"
fi
ok "健康检查： $(curl -s http://127.0.0.1:8765/_health)"

say ""
say "[4/4] 冒烟测试"
CODE_HOME="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/ || echo 000)"
say "  首页： HTTP $CODE_HOME"

say ""
say "=============================================="
say "  已回滚： 8765 现在是 Python 版"
say "  浏览器 Ctrl+F5 确认"
say "=============================================="
