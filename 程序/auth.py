# -*- coding: utf-8 -*-
"""
登录与会话
----------
只用标准库，不引第三方：

    hash_password() / verify_password()   PBKDF2-SHA256，20 万轮
    new_token()                          会话令牌（secrets，不可预测）
    LoginThrottle                        登录失败限流（内存，够用）

数据库相关的部分（建表、查会话）在 store.py 里，这里只管算法和限流。
"""
import hashlib
import hmac
import os
import secrets
import threading
import time

PBKDF2_ROUNDS = 200_000
TOKEN_BYTES = 32

# 会话有效期（天）
SESSION_DAYS = 7

# 登录失败限流：5 次 / 5 分钟
FAIL_LIMIT = 5
FAIL_WINDOW = 300


def hash_password(password):
    """生成 `pbkdf2_sha256$轮数$盐$哈希`，盐随机，每个账号不同。"""
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ROUNDS)
    return "pbkdf2_sha256$%d$%s$%s" % (PBKDF2_ROUNDS, salt.hex(), dk.hex())


def verify_password(password, stored):
    """常量时间比较，避免按响应时间猜密码。"""
    try:
        algo, rounds, salt_hex, hash_hex = str(stored or "").split("$")
        if algo != "pbkdf2_sha256":
            return False
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                                 bytes.fromhex(salt_hex), int(rounds))
        return hmac.compare_digest(dk.hex(), hash_hex)
    except Exception:
        return False


def new_token():
    return secrets.token_urlsafe(TOKEN_BYTES)


def new_password(length=10):
    """生成好记一点的初始密码：去掉容易混淆的 0O1lI。"""
    alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(length))


def password_problem(password):
    """返回不合格的原因；合格返回空串。"""
    pw = str(password or "")
    if len(pw) < 8:
        return "密码至少 8 位"
    if len(pw) > 64:
        return "密码最长 64 位"
    if pw.strip() != pw:
        return "密码首尾不能有空格"
    if pw.lower() in ("12345678", "password", "88888888", "admin123"):
        return "这个密码太常见了，换一个"
    return ""


def username_problem(name):
    import re
    n = str(name or "").strip()
    if not n:
        return "请填写用户名"
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{2,19}", n):
        return "用户名要字母开头，3-20 位，只能是字母、数字、下划线"
    return ""


class LoginThrottle(object):
    """
    同一个用户名连续错 5 次，锁 5 分钟。

    放内存里就够了：重启清零的代价很小，而落库反而多一层写锁。
    """

    def __init__(self, limit=FAIL_LIMIT, window=FAIL_WINDOW):
        self.limit = limit
        self.window = window
        self.lock = threading.Lock()
        self.data = {}

    def _now(self):
        return time.time()

    def blocked(self, key):
        with self.lock:
            rec = self.data.get(key)
            if not rec:
                return 0
            count, first = rec
            if self._now() - first > self.window:
                self.data.pop(key, None)
                return 0
            if count < self.limit:
                return 0
            return int(self.window - (self._now() - first))

    def fail(self, key):
        with self.lock:
            now = self._now()
            count, first = self.data.get(key, (0, now))
            if now - first > self.window:
                count, first = 0, now
            self.data[key] = (count + 1, first)

    def reset(self, key):
        with self.lock:
            self.data.pop(key, None)

    def prune(self):
        """顺手清理过期记录，避免长期运行越积越多。"""
        with self.lock:
            now = self._now()
            for k in [k for k, (_, first) in self.data.items()
                      if now - first > self.window]:
                self.data.pop(k, None)


# 全局一份，server.py 直接用
THROTTLE = LoginThrottle()
