#!/usr/bin/env bash
# ==========================================================================
#  人数发票登记系统 —— Linux 一键部署
#
#  用法（在项目根目录执行）：
#      sudo bash 部署/deploy.sh
#
#  可选环境变量：
#      PORT=8765              端口
#      HOST=0.0.0.0           监听地址（默认对外开放）
#      INVOICE_AUTH=用户:密码  访问口令（强烈建议设）
#      RUN_USER=invoice       以哪个用户运行（默认创建一个专用用户）
#      PYPI_MIRROR=...        pip 源，默认清华
#
#  做的事：装系统依赖 → 建虚拟环境装 Python 包 → 装 chromium →
#          写 systemd 服务 → 启动 → 健康检查
# ==========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_NAME="invoice-ledger"
PORT="${PORT:-8765}"
HOST="${HOST:-0.0.0.0}"
RUN_USER="${RUN_USER:-invoice}"
PYPI_MIRROR="${PYPI_MIRROR:-https://pypi.tuna.tsinghua.edu.cn/simple}"
VENV="$APP_DIR/.venv"
PY="$VENV/bin/python"

C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_END=$'\033[0m'
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✔%s %s\n' "$C_OK" "$C_END" "$*"; }
warn() { printf '%s!%s %s\n' "$C_WARN" "$C_END" "$*"; }
die()  { printf '%s✘ %s%s\n' "$C_ERR" "$*" "$C_END" >&2; exit 1; }

say "============================================================"
say "  人数发票登记系统 · Linux 一键部署"
say "============================================================"
say "  项目目录： $APP_DIR"

# ---------------------------------------------------------------- 0 检查
[ "$(id -u)" -eq 0 ] || die "请用 root 执行：sudo bash 部署/deploy.sh"
[ -f "$APP_DIR/程序/server.py" ] || die "没找到 程序/server.py，请在项目根目录下运行本脚本"
[ -f "$APP_DIR/部署/requirements-linux.txt" ] || die "没找到 部署/requirements-linux.txt"

# 目录名里带中文时给个提醒（能用，但排错麻烦）
case "$APP_DIR" in
  *[!a-zA-Z0-9/._-]*) warn "路径里有非 ASCII 字符，能跑，但建议放到 /opt/invoice-ledger 这类纯英文路径" ;;
esac

# ---------------------------------------------------------------- 1 系统依赖
say ""
say "[1/6] 安装系统依赖"
if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  # libgl1/libglib2.0-0：opencv（rapidocr 的依赖）在无桌面环境导入要用
  # libgomp1：onnxruntime 需要
  # fonts-noto-cjk：查验截图的汉字才不会是方框
  apt-get install -y -qq python3 python3-venv python3-pip curl ca-certificates \
      libgl1 libglib2.0-0 libgomp1 fonts-noto-cjk >/dev/null
  ok "apt 依赖已就绪"
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y -q python3 python3-pip curl ca-certificates \
      mesa-libGL glib2 libgomp google-noto-sans-cjk-fonts >/dev/null
  ok "dnf 依赖已就绪"
elif command -v yum >/dev/null 2>&1; then
  yum install -y -q python3 python3-pip curl ca-certificates \
      mesa-libGL glib2 libgomp >/dev/null
  ok "yum 依赖已就绪"
else
  warn "没识别出 apt/dnf/yum，跳过系统依赖安装；请自行确认 python3 + venv + libGL 可用"
fi

python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)' \
  || die "需要 Python 3.10 以上，当前是 $(python3 -V 2>&1)"

# ---------------------------------------------------------------- 2 运行用户
say ""
say "[2/6] 准备运行用户：$RUN_USER"
if ! id "$RUN_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$RUN_USER" 2>/dev/null \
    || useradd -r -d "$APP_DIR" -s /sbin/nologin "$RUN_USER"
  ok "已创建系统用户 $RUN_USER"
else
  ok "用户已存在"
fi
# 数据和存档必须让运行用户可写
mkdir -p "$APP_DIR/数据" "$APP_DIR/发票存档"
chown -R "$RUN_USER":"$RUN_USER" "$APP_DIR/数据" "$APP_DIR/发票存档" 2>/dev/null || true

# ---------------------------------------------------------------- 3 Python 依赖
say ""
say "[3/6] 创建虚拟环境并安装 Python 依赖（几分钟，首次下载量大）"
say "      Python: $(python3 -V 2>&1)"
if [ ! -x "$PY" ]; then
  python3 -m venv "$VENV" || die "创建虚拟环境失败（Debian/Ubuntu 需要 python3-venv）"
fi
"$PY" -m pip install --upgrade pip -q -i "$PYPI_MIRROR"

# 必需的包：装不上就必须停，否则后面查不了票
"$PY" -m pip install -q -i "$PYPI_MIRROR" -r "$APP_DIR/部署/requirements-linux.txt" \
  || die "pip 安装失败。可换源重试：PYPI_MIRROR=https://pypi.org/simple sudo -E bash 部署/deploy.sh"

# 可选的包：装不上只警告，不影响主流程
for pkg in zxingcpp; do
  if "$PY" -m pip install -q -i "$PYPI_MIRROR" "$pkg" >/dev/null 2>&1; then
    ok "可选组件 $pkg 已安装（二维码识别更准）"
  else
    warn "可选组件 $pkg 装不上（常见于 Python 3.13），已跳过——用 opencv 兜底，不影响使用"
  fi
done
ok "Python 依赖安装完成"

# 装完先自检一遍：缺系统库（比如 libGL）时这里就会露出来，免得装到一半跳过
"$PY" - <<'PY_EOF' || die "依赖导入失败，见上面提示（多数是缺 libGL / libglib2.0-0）"
import sys
mods = ["cv2", "rapidocr", "onnxruntime", "pymupdf", "openpyxl", "PIL", "playwright"]
bad = []
for m in mods:
    try:
        __import__(m)
    except Exception as exc:
        bad.append((m, str(exc)[:120]))
for m, err in bad:
    print("    导入失败：%-12s %s" % (m, err))
if bad:
    sys.exit(1)
print("    依赖导入检查通过：", ", ".join(mods))
PY_EOF

# ---------------------------------------------------------------- 4 浏览器
say ""
say "[4/6] 安装 chromium（约 200MB，第一次慢）"
# 浏览器必须装到"服务运行时看得到"的地方。
# 不能用默认位置（$HOME/.cache）：deploy 是 root 跑、服务是 invoice 用户跑，
# 两边 HOME 不同，就会出现"装了但服务说 Executable doesn't exist"。
# 这里刻意选 $APP_DIR/.cache/ms-playwright —— 正好等于服务用户 HOME=$APP_DIR
# 时的默认路径，于是"显式环境变量"和"默认路径"指向同一处，怎么跑都不会错位。
BROWSER_DIR="$APP_DIR/.cache/ms-playwright"
mkdir -p "$BROWSER_DIR"
export PLAYWRIGHT_BROWSERS_PATH="$BROWSER_DIR"

"$PY" -m playwright install --with-deps chromium \
  || die "chromium 安装失败。常见原因：没网、apt 源不可用"
# 整个 .cache 都交给运行用户：浏览器只需要读+执行，但目录要能进去
chown -R "$RUN_USER":"$RUN_USER" "$APP_DIR/.cache" 2>/dev/null || true
ok "chromium 已装到 $BROWSER_DIR"

# 以运行用户的身份真的启动一次，避免"装好了但打不开"
sudo -u "$RUN_USER" env HOME="$APP_DIR" PLAYWRIGHT_BROWSERS_PATH="$BROWSER_DIR" \
  "$PY" - <<'PY_EOF' || die "chromium 以服务用户启动失败，见上面报错"
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    print("    以服务用户启动 chromium 成功，版本:", b.version)
    b.close()
PY_EOF

# ---------------------------------------------------------------- 5 systemd
say ""
say "[5/6] 写入 systemd 服务"
UNIT="/etc/systemd/system/${SERVICE_NAME}.service"
ENV_FILE="/etc/${SERVICE_NAME}.env"
AUTH_LINE=""
if [ -n "${INVOICE_AUTH:-}" ]; then
  # 口令单独放一个 600 的文件，别写进 .service（那个文件其他用户也能读）
  umask 077
  printf 'INVOICE_AUTH=%s\n' "${INVOICE_AUTH}" > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  AUTH_LINE="EnvironmentFile=-${ENV_FILE}"
  ok "口令已写入 ${ENV_FILE}（权限 600）"
else
  warn "没有设置 INVOICE_AUTH，服务对局域网是开放的（谁都能看台账）"
fi

cat > "$UNIT" <<UNIT_EOF
[Unit]
Description=人数发票登记台账（发票登记 + 查验）
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${APP_DIR}
Environment=INVOICE_HOST=${HOST}
Environment=PLAYWRIGHT_BROWSERS_PATH=${BROWSER_DIR}
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONIOENCODING=utf-8
${AUTH_LINE}
ExecStart=${PY} ${APP_DIR}/程序/server.py --host ${HOST} --port ${PORT} --no-browser
Restart=on-failure
RestartSec=5
# 浏览器进程需要 /tmp 和共享内存
PrivateTmp=false
NoNewPrivileges=false

[Install]
WantedBy=multi-user.target
UNIT_EOF
ok "已写入 $UNIT"

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true
systemctl restart "$SERVICE_NAME"

# ---------------------------------------------------------------- 6 健康检查
say ""
say "[6/6] 健康检查"
READY=0
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/_health" >/dev/null 2>&1; then READY=1; break; fi
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  warn "服务没起来，最近日志："
  journalctl -u "$SERVICE_NAME" -n 30 --no-pager || true
  die "部署失败，请把上面日志发回来"
fi
ok "服务已启动并通过健康检查"

IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
say ""
say "============================================================"
say "  部署完成"
say ""
say "  访问地址： http://${IP:-服务器IP}:${PORT}/"
[ -n "${INVOICE_AUTH:-}" ] && say "  访问口令： ${INVOICE_AUTH%%:*:*} / ******（环境变量 INVOICE_AUTH）"
say ""
say "  常用命令："
say "    systemctl status  ${SERVICE_NAME}     # 看状态"
say "    systemctl restart ${SERVICE_NAME}     # 重启"
say "    journalctl -u ${SERVICE_NAME} -f      # 实时日志"
say ""
say "  数据目录： ${APP_DIR}/数据"
say "  发票存档： ${APP_DIR}/发票存档"
say "  建议下一步：用 nginx 加 HTTPS 和更强的认证（见 部署/部署说明_Linux.md）"
say "============================================================"
