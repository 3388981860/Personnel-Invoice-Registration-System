#!/usr/bin/env bash
# ==========================================================================
#  把发布包里的文件放到正确位置
#
#  包里用全英文目录名（避免跨系统文件名乱码），
#  这个脚本负责映射成服务器上的实际路径：
#
#      backend/invoice-server.jar → 后端/invoice-server.jar
#      tools/*.py                 → 工具/
#      web/dist/                  → 前端/dist/
#      deploy/java/*              → 部署/java/
#
#  用法：
#      cd /opt/02-fapiaodengji
#      sudo bash 发布包/install.sh
# ==========================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${APP_DIR:-$(cd "$HERE/.." && pwd)}"

C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_END=$'\033[0m'
ok()  { printf '%s✔%s %s\n' "$C_OK" "$C_END" "$*"; }
die() { printf '%s✘%s %s\n' "$C_ERR" "$C_END" "$*" >&2; exit 1; }

echo "发布包： $HERE"
echo "项目目录： $APP_DIR"

[ -f "$APP_DIR/程序/server.py" ] || die "这好像不是项目目录（找不到 程序/server.py）。可用 APP_DIR=... 指定"
[ -d "$HERE/backend" ] || die "发布包不完整（缺 backend/）"

echo ""
mkdir -p "$APP_DIR/后端" "$APP_DIR/工具" "$APP_DIR/前端" "$APP_DIR/部署/java"

echo "[1/4] 后端 jar"
cp -f "$HERE/backend/invoice-server.jar" "$APP_DIR/后端/invoice-server.jar"
ok "后端/invoice-server.jar  $(( $(stat -c%s "$APP_DIR/后端/invoice-server.jar") / 1024 / 1024 )) MB"

echo "[2/4] 工具脚本"
for f in "$HERE/tools/"*.py; do
  base="$(basename "$f")"
  case "$base" in
    parse_service.py) cp -f "$f" "$APP_DIR/工具/解析服务.py" ;;
    audit_service.py) cp -f "$f" "$APP_DIR/工具/查验服务.py" ;;
    *) cp -f "$f" "$APP_DIR/工具/$base" ;;
  esac
done
ok "工具/解析服务.py 工具/查验服务.py"

echo "[3/4] 前端 dist"
rm -rf "$APP_DIR/前端/dist"
mkdir -p "$APP_DIR/前端"
cp -a "$HERE/web/dist" "$APP_DIR/前端/dist"
ok "前端/dist  $(find "$APP_DIR/前端/dist" -type f | wc -l) 个文件"

echo "[4/4] 部署脚本"
cp -f "$HERE/deploy/java/"*.sh "$APP_DIR/部署/java/" 2>/dev/null || true
cp -f "$HERE/deploy/java/README.md" "$APP_DIR/部署/java/" 2>/dev/null || true
chmod +x "$APP_DIR/部署/java/"*.sh
ok "部署/java/"

RUN_USER="${RUN_USER:-invoice}"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR/后端" "$APP_DIR/工具" "$APP_DIR/前端" 2>/dev/null || true

echo ""
echo "=============================================="
echo "  文件已就位，下一步："
echo ""
echo "   sudo bash 部署/java/step1-parallel.sh"
echo ""
echo "  然后浏览器打开 http://服务器IP:8766 验证"
echo "=============================================="
