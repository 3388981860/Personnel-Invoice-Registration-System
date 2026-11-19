# -*- coding: utf-8 -*-
"""
重置某个账号的密码（忘记密码、被锁在外面时用）

用法：
    Windows：双击  重置密码.bat
    命令行：python 工具/重置密码.py [用户名]

重置后会：
  · 打印一个新密码（只显示这一次）
  · 把该账号的登录状态全部清掉
  · 下次登录要求改密码
"""
import os
import secrets
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE, "运行库"))
sys.path.insert(0, os.path.join(BASE, "程序"))

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import auth                       # noqa: E402
import store as store_mod         # noqa: E402


def new_password(n=10):
    alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(n))


def main():
    try:
        st = store_mod.Store(BASE)
    except Exception as exc:
        print("打不开数据库：%s" % exc)
        print("请确认本文件在项目的「工具」目录下，和「程序」「数据」在同一层。")
        return 1

    users = st.list_users()
    if not users:
        print("数据库里一个账号都没有。")
        return 1

    print("=" * 52)
    print("  现有账号")
    print("=" * 52)
    for u in users:
        print("  %-16s %-6s %s" % (
            u["username"],
            "管理员" if u["role"] == "admin" else "普通用户",
            "启用" if int(u["status"] or 0) else "停用"))
    print("")

    if len(sys.argv) > 1:
        name = sys.argv[1].strip()
    else:
        name = (input("要重置哪个账号（直接回车 = admin）：").strip() or "admin")

    user = st.get_user_by_name(name)
    if not user:
        print("找不到账号：%s" % name)
        return 1

    pw = new_password()
    st.set_password(user["id"], auth.hash_password(pw), must_change_pw=1)
    st.delete_user_sessions(user["id"])

    print("")
    print("=" * 52)
    print("  已重置「%s」的密码" % name)
    print("")
    print("     新密码：  %s" % pw)
    print("")
    print("  请立刻记下来。登录后系统会要求你改密码。")
    print("=" * 52)
    return 0


if __name__ == "__main__":
    sys.exit(main())
