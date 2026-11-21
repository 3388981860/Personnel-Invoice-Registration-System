#!/usr/bin/env bash
# ==========================================================================
#  切换到 Java 版 —— 第 1 步：并行运行（不动线上）
#
#  做的事：
#   1. 检查/安装 JDK 17
#   2. 写入三个 systemd 服务：解析、查验、Java 后端（端口 8766）
#   3. 启动并健康检查
#
#  线上 8765（Python 版）完全不动，随时可以对比。
#
#  用法（项目根目录）：
#      sudo bash 部署/java/step1-parallel.sh
# ==========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_USER="${RUN_USER:-invoice}"
STAGING_PORT="${STAGING_PORT:-8766}"
PARSE_PORT="${PARSE_PORT:-8799}"
AUDIT_PORT="${AUDIT_PORT:-8798}"

C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_END=$'\033[0m'
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✔%s %s\n' "$C_OK" "$C_END" "$*"; }
warn() { printf '%s!%s %s\n' "$C_WARN" "$C_END" "$*"; }
die()  { printf '%s✘%s %s\n' "$C_ERR" "$C_END" "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 sudo 运行"

say "项目目录： $APP_DIR"
say "运行用户： $RUN_USER"
say "Java 端口： $STAGING_PORT（并行验证用，不影响线上 8765）"

# ---------------------------------------------------------------- 1 前置检查
say ""
say "[1/5] 前置检查"

[ -f "$APP_DIR/后端/target/invoice-server.jar" ] \
  || [ -f "$APP_DIR/后端/invoice-server.jar" ] \
  || die "找不到 jar：请先把 后端/target/invoice-server.jar 传到 $APP_DIR/后端/"

[ -d "$APP_DIR/前端/dist" ] || die "找不到 前端/dist：请先传前端构建产物"
[ -f "$APP_DIR/工具/解析服务.py" ] || die "找不到 工具/解析服务.py"
[ -f "$APP_DIR/工具/查验服务.py" ] || die "找不到 工具/查验服务.py"
[ -f "$APP_DIR/数据/发票台账.db" ] || die "找不到数据库"

JAR="$APP_DIR/后端/target/invoice-server.jar"
[ -f "$JAR" ] || JAR="$APP_DIR/后端/invoice-server.jar"
ok "jar / 前端 / 小服务 / 数据库 都在"

PY="$APP_DIR/.venv/bin/python"
[ -x "$PY" ] || PY="$(command -v python3)"
[ -n "$PY" ] || die "找不到 python3"
ok "Python： $PY"

# ---------------------------------------------------------------- 2 JDK 17
say ""
say "[2/5] 检查 JDK 17"

need_java=1
if command -v java >/dev/null 2>&1; then
  ver="$(java -version 2>&1 | head -1)"
  major="$(printf '%s' "$ver" | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
  [ -n "$major" ] && [ "$major" -ge 17 ] && need_java=0
  say "  当前： $ver"
fi

if [ "$need_java" -eq 0 ]; then
  ok "JDK 已满足"
else
  warn "没有 JDK 17+，开始安装"
  apt-get update -qq || true
  installed=0
  # Spring Boot 3.2 支持 17/21，优先 17；新版 Ubuntu 只有 21 也能用
  for pkg in openjdk-17-jre-headless openjdk-21-jre-headless default-jre-headless; do
    say "  尝试 $pkg …"
    if DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$pkg" >/dev/null 2>&1; then
      major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
      if [ -n "$major" ] && [ "$major" -ge 17 ]; then
        ok "已安装 $pkg（Java $major）"
        installed=1
        break
      fi
    fi
  done
  [ "$installed" -eq 1 ] || die "装不上 JDK。请手动安装 openjdk-17-jre-headless 或 openjdk-21-jre-headless 后重试"
fi

# 记录 java 的真实路径，写进服务文件（避免 /usr/bin/java 不存在的情况）
JAVA_BIN="$(command -v java || true)"
[ -n "$JAVA_BIN" ] || die "找不到 java 可执行文件"
ok "Java 路径： $JAVA_BIN"

# ---------------------------------------------------------------- 3 权限
say ""
say "[3/5] 目录权限"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR/程序" "$APP_DIR/工具" "$APP_DIR/前端" 2>/dev/null || true
mkdir -p "$APP_DIR/数据/临时"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR/数据"
ok "已把运行用户设成 $RUN_USER"

# ---------------------------------------------------------------- 4 写服务
say ""
say "[4/5] 写入 systemd 服务"

BROWSER_DIR="$APP_DIR/.cache/ms-playwright"

cat > /etc/systemd/system/invoice-parse.service <<UNIT
[Unit]
Description=发票解析小服务（PDF/OFD 识别）
After=network.target

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${APP_DIR}
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONIOENCODING=utf-8
ExecStart=${PY} ${APP_DIR}/工具/解析服务.py --port ${PARSE_PORT}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/invoice-audit.service <<UNIT
[Unit]
Description=发票查验小服务（Playwright + 验证码 OCR）
After=network.target

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${APP_DIR}
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONIOENCODING=utf-8
Environment=PLAYWRIGHT_BROWSERS_PATH=${BROWSER_DIR}
ExecStart=${PY} ${APP_DIR}/工具/查验服务.py --port ${AUDIT_PORT} --base ${APP_DIR}
Restart=on-failure
RestartSec=5
PrivateTmp=false

[Install]
WantedBy=multi-user.target
UNIT

# Java 端口从这里读，切换脚本改这一行即可
if [ ! -f /etc/default/invoice-java ]; then
  cat > /etc/default/invoice-java <<ENV
# Java 后端监听端口。并行验证阶段用 8766，正式切换时改成 8765
JAVA_PORT=${STAGING_PORT}
# 额外 JVM 参数（内存小就调小）
JAVA_OPTS=-Xms128m -Xmx512m
ENV
fi

cat > /etc/systemd/system/invoice-java.service <<UNIT
[Unit]
Description=人数发票登记台账（Spring Boot）
After=network.target invoice-parse.service invoice-audit.service
Wants=invoice-parse.service invoice-audit.service

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${APP_DIR}
# 端口从 /etc/default/invoice-java 读，切换脚本改那里即可
# 注意：这里不能再写 Environment=JAVA_PORT=...，否则会盖掉环境文件
EnvironmentFile=-/etc/default/invoice-java
ExecStart=${JAVA_BIN} \$JAVA_OPTS -jar ${JAR} --server.port=\${JAVA_PORT} --app.base-dir=${APP_DIR}
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable invoice-parse.service invoice-audit.service invoice-java.service >/dev/null 2>&1 || true
ok "三个服务已写入并设为开机启动"

# ---------------------------------------------------------------- 5 启动
say ""
say "[5/5] 启动并检查"

systemctl restart invoice-parse.service
systemctl restart invoice-audit.service
systemctl restart invoice-java.service

ready=0
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${STAGING_PORT}/_health" >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  warn "Java 服务没起来，最近日志："
  journalctl -u invoice-java -n 40 --no-pager || true
  die "启动失败"
fi

ok "Java 后端已就绪： http://127.0.0.1:${STAGING_PORT}/"
say ""
say "  解析服务  $(curl -s http://127.0.0.1:${PARSE_PORT}/_health || echo '无响应')"
say "  查验服务  $(curl -s http://127.0.0.1:${AUDIT_PORT}/_health || echo '无响应')"
say "  健康检查  $(curl -s http://127.0.0.1:${STAGING_PORT}/_health)"
say ""
say "  线上 8765 还是 Python 版，没有动。"
say "  下一步：浏览器打开 http://服务器IP:${STAGING_PORT} 验证一遍，"
say "  确认没问题后执行： sudo bash 部署/java/step2-switch.sh"
