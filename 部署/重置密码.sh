#!/usr/bin/env bash
# ==========================================================================
#  在服务器上重置某个账号的密码
#
#  应用里已经把「重置密码」按钮去掉了（管理员看不到别人的密码）。
#  真有人忘了密码，用这个脚本在服务器上重置——需要 SSH 权限，
#  也就是"能给服务器发命令的人"才能做，比点个按钮安全。
#
#  用法：
#      cd /opt/02-fapiaodengji
#      sudo bash 部署/重置密码.sh sxy            # 随机生成新密码
#      sudo bash 部署/重置密码.sh sxy 新密码123   # 指定新密码
# ==========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="$APP_DIR/数据/发票台账.db"
PY="$APP_DIR/.venv/bin/python"
[ -x "$PY" ] || PY="$(command -v python3)"

C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_END=$'\033[0m'

if [ $# -lt 1 ]; then
  echo "用法: sudo bash 部署/重置密码.sh <用户名> [新密码]"
  echo
  echo "现有账号："
  "$PY" - "$DB" <<'EOF'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
for r in c.execute("SELECT username, display_name, role, status FROM users ORDER BY id"):
    print("  %-14s %-10s %-6s %s" % (r[0], r[1] or "", r[2], "启用" if r[3] else "停用"))
EOF
  exit 1
fi

USER_NAME="$1"
NEW_PWD="${2:-}"

"$PY" - "$DB" "$USER_NAME" "$NEW_PWD" <<'EOF'
import hashlib
import secrets
import sqlite3
import sys

db, username, pwd = sys.argv[1], sys.argv[2], sys.argv[3]
c = sqlite3.connect(db)
row = c.execute("SELECT id, display_name FROM users WHERE username = ?", (username,)).fetchone()
if not row:
    print("\033[31m✘\033[0m 没有这个账号：%s" % username)
    sys.exit(1)

uid = row[0]
if not pwd:
    # 去掉容易看错的 0/O/1/l/I
    alphabet = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    pwd = "".join(secrets.choice(alphabet) for _ in range(10))

salt = secrets.token_bytes(16)
dk = hashlib.pbkdf2_hmac("sha256", pwd.encode("utf-8"), salt, 200_000)
h = "pbkdf2_sha256$200000$%s$%s" % (salt.hex(), dk.hex())

c.execute("UPDATE users SET password_hash = ?, must_change_pw = 1 WHERE id = ?", (h, uid))
# 旧会话全部失效，避免「密码改了旧页面还能用」
c.execute("DELETE FROM sessions WHERE user_id = ?", (uid,))
c.execute(
    "INSERT INTO audit_logs (user_id, username, action, target_type, target_id, detail, created_at) "
    "VALUES (0, 'server-cli', 'reset_password', 'user', ?, ?, datetime('now','localtime'))",
    (uid, username))
c.commit()

print()
print("\033[32m✔\033[0m 已重置「%s」（%s）的密码" % (username, row[1] or ""))
print()
print("   新密码： %s" % pwd)
print()
print("   对方登录后系统会要求改密码。旧会话已全部失效。")
EOF
