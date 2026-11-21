#!/usr/bin/env bash
# ==========================================================================
#  切换到 Java 版 —— 第 2 步：正式切换
#
#  做的事：
#   1. 备份数据库
#   2. 停掉线上 Python 服务（8765）
#   3. Java 后端接管 8765
#   4. 健康检查 + 冒烟测试
#
#  出任何问题，一条命令回滚：
#      sudo bash 部署/java/rollback-python.sh
#
#  用法（项目根目录）：
#      sudo bash 部署/java/step2-switch.sh
# ==========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_USER="${RUN_USER:-invoice}"
PARSE_PORT="${PARSE_PORT:-8799}"
AUDIT_PORT="${AUDIT_PORT:-8798}"

C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_END=$'\033[0m'
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✔%s %s\n' "$C_OK" "$C_END" "$*"; }
warn() { printf '%s!%s %s\n' "$C_WARN" "$C_END" "$*"; }
die()  { printf '%s✘%s %s\n' "$C_ERR" "$C_END" "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 sudo 运行"

say "项目目录： $APP_DIR"

# ---------------------------------------------------------------- 1 备份
say ""
say "[1/5] 备份数据库"
STAMP="$(date +%Y%m%d-%H%M%S)"
BK_DIR="$APP_DIR/数据/备份"
mkdir -p "$BK_DIR"
cp -a "$APP_DIR/数据/发票台账.db" "$BK_DIR/发票台账_切换前_${STAMP}.db"
chown "$RUN_USER:$RUN_USER" "$BK_DIR/发票台账_切换前_${STAMP}.db" || true
ok "已备份到 数据/备份/发票台账_切换前_${STAMP}.db"

# ---------------------------------------------------------------- 2 预检
say ""
say "[2/5] 切换前检查"
curl -fsS "http://127.0.0.1:8766/_health" >/dev/null 2>&1 \
  || die "8766 上的 Java 版没在跑，请先执行 step1-parallel.sh"

PARSE="$(curl -s http://127.0.0.1:${PARSE_PORT}/_health || echo '')"
AUDIT="$(curl -s http://127.0.0.1:${AUDIT_PORT}/_health || echo '')"
[ "$PARSE" = "ok" ] || die "解析服务没起来，先修好再切"
[ "$AUDIT" = "ok" ] || die "查验服务没起来，先修好再切"
ok "解析 / 查验 小服务正常"

# 记录切换前的数据量，切换后对比
BEFORE="$(curl -s "http://127.0.0.1:8766/_health")"
say "  Java 版当前： $BEFORE"

# ---------------------------------------------------------------- 3 切换
say ""
say "[3/5] 停 Python、Java 接管 8765"

# 记下 Python 服务原来是否开机自启，回滚时要恢复
PY_ENABLED="$(systemctl is-enabled invoice-ledger 2>/dev/null || echo unknown)"
echo "$PY_ENABLED" > /tmp/invoice-ledger-was-enabled

systemctl stop invoice-ledger
ok "已停 invoice-ledger（Python）"

sed -i 's/^JAVA_PORT=.*/JAVA_PORT=8765/' /etc/default/invoice-java
systemctl restart invoice-java
ok "invoice-java 已切到 8765"

# ---------------------------------------------------------------- 4 检查
say ""
say "[4/5] 健康检查"

ready=0
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:8765/_health" >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  warn "切换后没起来，自动回滚！"
  bash "$(dirname "${BASH_SOURCE[0]}")/rollback-python.sh" || true
  die "切换失败，已尝试回滚"
fi

ok "健康检查： $(curl -s http://127.0.0.1:8765/_health)"

# ---------------------------------------------------------------- 5 冒烟
say ""
say "[5/5] 冒烟测试"

CODE_LOGIN="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' \
  -d '{"username":"__probe__","password":"__probe__"}' \
  http://127.0.0.1:8765/api/login || echo 000)"
say "  登录接口（故意用错账号）： HTTP $CODE_LOGIN （401 表示接口正常）"

CODE_HOME="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/ || echo 000)"
say "  首页： HTTP $CODE_HOME"

CODE_DEEP="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/ledger || echo 000)"
say "  深链接 /ledger： HTTP $CODE_DEEP"

[ "$CODE_HOME" = "200" ] || die "首页不是 200，请回滚"
[ "$CODE_DEEP" = "200" ] || die "/ledger 不是 200，请回滚"

# 让 Java 开机自启，Python 不再自启
systemctl enable invoice-java.service >/dev/null 2>&1 || true
systemctl disable invoice-ledger >/dev/null 2>&1 || true
# 小服务本来就用同一个库，确保它们跟着启动
systemctl enable invoice-parse.service invoice-audit.service >/dev/null 2>&1 || true

ok "已设为开机自启： invoice-java / invoice-parse / invoice-audit"
warn "Python 版已停止并取消自启（服务文件保留，随时能回滚）"

say ""
say "=============================================="
say "  切换完成： 8765 现在是 Spring Boot 版"
say ""
say "  浏览器打开 http://服务器IP:8765 按 Ctrl+F5 验证"
say ""
say "  回滚（30 秒）："
say "    sudo bash 部署/java/rollback-python.sh"
say "=============================================="
