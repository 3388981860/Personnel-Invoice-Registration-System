# -*- coding: utf-8 -*-
"""
人数发票登记系统 —— 本机服务
------------------------
双击「启动.bat」后由 启动.bat 调用本文件：
    1. 只在 127.0.0.1 上监听，不联网、不对外
    2. 启动后自动打开浏览器
    3. 关闭本窗口即退出系统
"""
import base64
import datetime
import json
import mimetypes
import os
import socket
import subprocess
import sys
import threading
import traceback
import urllib.parse
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _find_dir(base, filename, preferred, max_depth=2):
    """
    定位带某个文件的目录。
    先按约定名字找，找不到就在项目里搜（防止压缩/解压工具把中文文件夹名搞乱）。
    """
    direct = os.path.join(base, preferred)
    if os.path.isfile(os.path.join(direct, filename)):
        return direct
    for root, dirs, files in os.walk(base):
        if root[len(base):].count(os.sep) >= max_depth:
            dirs[:] = []
            continue
        if filename in files:
            return root
    return direct


def _find_lib_dir(base):
    """定位运行库目录（里面应有 pymupdf 或 fitz 包）。"""
    for name in ("运行库", "libs", "runtime"):
        cand = os.path.join(base, name)
        if os.path.isdir(os.path.join(cand, "pymupdf")) or \
           os.path.isdir(os.path.join(cand, "fitz")):
            return cand
    for root, dirs, files in os.walk(base):
        if root[len(base):].count(os.sep) >= 2:
            dirs[:] = []
            continue
        if "pymupdf" in dirs or "fitz" in dirs:
            return root
    return os.path.join(base, "运行库")


APP_DIR = _find_dir(BASE, "server.py", "程序")
LIB_DIR = _find_lib_dir(BASE)
WEB_DIR = _find_dir(BASE, "index.html", "网页", max_depth=3)

# 新版前端（Vue + Element Plus）的构建产物。
# 存在就用它当主界面，不存在就自动退回旧版 网页/，部署不会因为忘了打包而白屏。
DIST_DIR = os.path.join(BASE, "前端", "dist")
HAS_SPA = os.path.isfile(os.path.join(DIST_DIR, "index.html"))


def dist_file(rel):
    """把 URL 相对路径安全地映射到 dist 目录下；越界返回 None。"""
    rel = str(rel or "").replace("\\", "/").lstrip("/")
    root = os.path.normpath(DIST_DIR)
    full = os.path.normpath(os.path.join(root, rel))
    if full != root and not full.startswith(root + os.sep):
        return None
    return full
sys.path.insert(0, LIB_DIR)
sys.path.insert(0, APP_DIR)

import reader      # noqa: E402
import store as store_mod  # noqa: E402
import audit as audit_mod  # noqa: E402
import auth as auth_mod    # noqa: E402

STORE = store_mod.Store(BASE)
AUDIT_RUNNER = audit_mod.Runner(STORE)
APP_TAG = "invoice-book"
BUILD = "20261118"       # 构建号，显示在右上角账号菜单里


# ----------------------------------------------------------------------
# 文件句柄（Linux）：跑久了句柄会被浏览器进程、连接等吃掉，
# 满了以后每个请求都会报 "Too many open files"，网页直接打不开。
# 这里只做观测和告警，真正的根治是 systemd 里把 LimitNOFILE 调大。
# ----------------------------------------------------------------------
def _fd_limit():
    try:
        import resource
        soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        return soft, hard
    except Exception:
        return None, None


def _open_fd_count():
    """返回 (已用, 上限, 是否告急)；非 Linux 返回 None。"""
    try:
        used = len(os.listdir("/proc/self/fd"))
    except OSError:
        return None
    soft, _hard = _fd_limit()
    limit = soft if isinstance(soft, int) and soft > 0 else 0
    warn = bool(limit) and used > limit * 0.8
    return used, (limit or "?"), warn


def _cleanup_stray_browsers():
    """
    上个进程如果被 kill -9 或者异常退出，可能留下 chromium 子进程，
    每个都占几百个句柄。启动时扫一遍 /proc，把属于 Playwright 的残留清掉。
    """
    try:
        import signal
        me = os.getpid()
        for name in os.listdir("/proc"):
            if not name.isdigit():
                continue
            pid = int(name)
            if pid == me:
                continue
            try:
                with open("/proc/%d/cmdline" % pid, "rb") as f:
                    cmd = f.read().decode("utf-8", "ignore")
            except OSError:
                continue
            if "ms-playwright" not in cmd:
                continue
            if not any(k in cmd for k in ("chrome", "chromium", "headless_shell")):
                continue
            try:
                os.kill(pid, signal.SIGKILL)
                print("[启动清理] 关掉上次残留的浏览器进程 pid=%d" % pid, flush=True)
            except OSError:
                pass
    except Exception:
        pass


def _file_error_hint(name, exc, folder):
    """把读文件失败的 errno 翻译成人话，别一律让人 chown。"""
    no = getattr(exc, "errno", None)
    text = getattr(exc, "strerror", None) or str(exc)
    if no in (24, 23):      # EMFILE / ENFILE：句柄耗尽
        fd = _open_fd_count()
        extra = ""
        if fd:
            extra = "（当前已用 %d / 上限 %s）" % (fd[0], fd[1])
        return ("服务器打开的文件太多，句柄用完了%s，所以连网页都读不出来。"
                "应急处置：重启服务 sudo systemctl restart invoice-ledger；"
                "根治：systemd 单元里加 LimitNOFILE=65536 后 daemon-reload 再重启"
                % extra)
    if no in (13, 1):       # EACCES / EPERM：权限
        return ("读不到 %s（%s）。文件权限不对，请执行："
                "sudo chown -R invoice:invoice %s" % (name, text, folder))
    return "读不到 %s（%s）" % (name, text)


def _audited_recently(stamp, days=30):
    """查验时间在 days 天以内 → 认为不用重复查（平台配额有限）。"""
    if not stamp:
        return False
    try:
        d = datetime.datetime.strptime(str(stamp)[:10], "%Y-%m-%d").date()
    except ValueError:
        return False
    return (datetime.date.today() - d).days < days

DEFAULT_PORT = 8765
ALLOWED_EXT = {".pdf", ".ofd"}

IS_WINDOWS = (sys.platform == "win32")
IS_MAC = (sys.platform == "darwin")

# 监听地址：默认只给本机用；部署到服务器时用 --host 0.0.0.0 或环境变量
DEFAULT_HOST = os.environ.get("INVOICE_HOST") or "127.0.0.1"
# 可选的口令保护：INVOICE_AUTH="用户名:密码"，不设就是不校验
AUTH = os.environ.get("INVOICE_AUTH") or ""


def can_open_local():
    """
    能不能在"服务器这台机器"上弹出文件/文件夹。
    纯 Linux 服务器没有图形界面，这个动作本来就没意义，
    前端会根据这个值把「打开文件夹」这类按钮藏起来。
    """
    if IS_WINDOWS or IS_MAC:
        return True
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def open_local_path(target):
    """在服务器本机打开文件或文件夹（仅在桌面环境有意义）。"""
    if IS_WINDOWS:
        os.startfile(target)          # noqa: 只在 Windows 存在
    elif IS_MAC:
        subprocess.Popen(["open", target])
    else:
        subprocess.Popen(["xdg-open", target])


class Handler(BaseHTTPRequestHandler):
    server_version = "InvoiceBook/1.0"

    # ------------------------------------------------------------------
    # 工具
    # ------------------------------------------------------------------
    def log_message(self, fmt, *args):
        pass  # 保持控制台干净

    def _send(self, code, body=b"", ctype="application/json; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if "Cache-Control" not in (extra or {}):
            self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False))

    def _err(self, msg, code=400, **extra):
        payload = {"ok": False, "error": msg}
        payload.update(extra)
        self._json(payload, code)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _json_body(self):
        raw = self._body()
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def _query(self):
        parsed = urllib.parse.urlparse(self.path)
        return urllib.parse.parse_qs(parsed.query), parsed.path

    # ------------------------------------------------------------------
    # 登录与会话
    # ------------------------------------------------------------------
    def _cookie_token(self):
        raw = self.headers.get("Cookie") or ""
        for part in raw.split(";"):
            k, _, v = part.strip().partition("=")
            if k == "sid":
                return v.strip()
        return None

    def _current_user(self):
        """当前登录用户；未登录/过期/被停用都返回 None。"""
        try:
            return STORE.get_session_user(self._cookie_token())
        except Exception:
            traceback.print_exc()
            return None

    def _need_login(self):
        return self._send(401, json.dumps(
            {"ok": False, "error": "请先登录", "need_login": True},
            ensure_ascii=False), "application/json; charset=utf-8")

    def _need_admin(self):
        return self._err("只有管理员能做这个操作", 403)

    def _redirect_login(self):
        return self._send(302, b"", "text/plain", {"Location": "/login"})

    def _set_cookie(self, token, days=None):
        days = days if days is not None else auth_mod.SESSION_DAYS
        return ("sid=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Lax"
                % (token, int(days * 86400)))

    def _is_admin(self, user):
        return bool(user) and user.get("role") == "admin"

    def _is_staff(self, user):
        """有没有"看全部/审查"的权限（目前只有管理员）。"""
        return self._is_admin(user)

    def _client_ip(self):
        return self.headers.get("X-Real-IP") or self.client_address[0]

    def _api_login(self):
        data = self._json_body()
        username = str(data.get("username") or "").strip()
        password = str(data.get("password") or "")
        key = "%s|%s" % (username.lower(), self._client_ip())

        wait = auth_mod.THROTTLE.blocked(key)
        if wait:
            return self._err("登录失败次数太多，请 %d 秒后再试" % wait, 429)

        user = STORE.get_user_by_name(username)
        if not user or not auth_mod.verify_password(password, user["password_hash"]):
            auth_mod.THROTTLE.fail(key)
            return self._err("用户名或密码不对", 401)
        if int(user["status"] or 0) != 1:
            return self._err("这个账号已被停用，请联系管理员", 403)

        auth_mod.THROTTLE.reset(key)
        token = STORE.create_session(user["id"], self._client_ip(),
                                     self.headers.get("User-Agent"))
        STORE.touch_login(user["id"])
        STORE.log_action(user["id"], user["username"], "login", "user", user["id"])
        return self._send(200, json.dumps({
            "ok": True,
            "user": self._public_user(user),
            "must_change_pw": bool(user["must_change_pw"]),
        }, ensure_ascii=False), "application/json; charset=utf-8",
            {"Set-Cookie": self._set_cookie(token)})

    def _api_register(self):
        """自助注册：只能注册成普通用户。"""
        data = self._json_body()
        username = str(data.get("username") or "").strip()
        password = str(data.get("password") or "")
        display = str(data.get("display_name") or "").strip() or username

        problem = auth_mod.username_problem(username)
        if problem:
            return self._err(problem)
        problem = auth_mod.password_problem(password)
        if problem:
            return self._err(problem)
        if STORE.get_user_by_name(username):
            return self._err("这个用户名已经被占用了")

        uid = STORE.create_user(username, auth_mod.hash_password(password),
                                display, role="user")
        STORE.log_action(uid, username, "register", "user", uid)
        STORE.notify_admins("account", "新用户注册",
                            "%s（%s）刚刚注册了账号" % (display, username))
        token = STORE.create_session(uid, self._client_ip(),
                                     self.headers.get("User-Agent"))
        return self._send(200, json.dumps({
            "ok": True, "user": self._public_user(STORE.get_user(uid)),
        }, ensure_ascii=False), "application/json; charset=utf-8",
            {"Set-Cookie": self._set_cookie(token)})

    def _api_logout(self, user):
        token = self._cookie_token()
        if token:
            STORE.delete_session(token)
        return self._send(200, b'{"ok": true}', "application/json; charset=utf-8",
                          {"Set-Cookie": "sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"})

    def _api_me(self, user):
        return self._json({
            "ok": True,
            "user": self._public_user(user),
            "unread": STORE.unread_count(user["id"]),
            "pending_reviews": STORE.count_pending_reviews() if self._is_admin(user) else 0,
            "build": BUILD,
            "can_open_local": can_open_local(),
        })

    def _api_password(self, user):
        data = self._json_body()
        old = str(data.get("old") or "")
        new = str(data.get("new") or "")
        if not auth_mod.verify_password(old, STORE.get_user(user["id"])["password_hash"]):
            return self._err("原密码不对")
        problem = auth_mod.password_problem(new)
        if problem:
            return self._err(problem)
        STORE.set_password(user["id"], auth_mod.hash_password(new), must_change_pw=0)
        STORE.log_action(user["id"], user["username"], "change_password", "user", user["id"])
        return self._json({"ok": True})

    @staticmethod
    def _public_user(u):
        if not u:
            return None
        return {
            "id": u["id"], "username": u["username"],
            "display_name": u.get("display_name") or u["username"],
            "role": u["role"], "must_change_pw": int(u.get("must_change_pw") or 0),
            "status": int(u.get("status") or 0),
            "created_at": u.get("created_at"),
            "last_login_at": u.get("last_login_at"),
        }

    # ------------------------------------------------------------------
    # GET
    # ------------------------------------------------------------------
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        try:
            qs, path = self._query()
            if path == "/_health":
                # 给 systemd / docker / 负载均衡做健康检查用，不需要登录
                # 顺带报一下打开的文件句柄数：快用完时就是 "Too many open files" 的前兆
                info = "ok"
                fd = _open_fd_count()
                if fd:
                    info = "ok fd=%d/%s" % (fd[0], fd[1])
                    if fd[2]:
                        info += " WARN 句柄快用完了，尽快重启服务"
                return self._send(200, info.encode("utf-8"),
                                  "text/plain; charset=utf-8")
            if path == "/favicon.ico":
                # 不给的话浏览器每次都会打一条 404 到控制台
                return self._send(204, b"", "image/x-icon")
            if path in ("/login", "/login.html") and not HAS_SPA:
                # 旧版登录页；装了新版前端后 /login 是前端路由，交给下面的 SPA 处理
                return self._serve_file(os.path.join(WEB_DIR, "login.html"))
            if path == "/api/ping":
                return self._json({
                    "ok": True, "app": APP_TAG,
                    "platform": sys.platform,
                    "can_open_local": can_open_local(),
                    "version": "2.0",
                    "build": BUILD,
                    "logged_in": bool(self._current_user()),
                })
            if path.startswith("/static/"):
                return self._serve_file(os.path.join(WEB_DIR, os.path.basename(path)))

            # ---------- 新版前端（Vue）静态托管 ----------
            # 装了新版就优先用新版；没有 dist 自动退回旧版网页，部署不会白屏。
            if HAS_SPA:
                if path == "/old" or path.startswith("/old/"):
                    # 留一个旧版入口，出问题可以立刻切回去
                    return self._serve_index()
                if path.startswith("/assets/") or path.endswith(
                        (".js", ".css", ".png", ".jpg", ".svg", ".ico", ".woff", ".woff2")):
                    return self._serve_dist(path)
                if not path.startswith("/api/") and "." not in os.path.basename(path):
                    # 前端路由（/entry、/ledger、/login…）统一交给单页应用
                    return self._serve_spa()

            user = self._current_user()
            if not user:
                if path.startswith("/api/"):
                    return self._need_login()
                return self._redirect_login()

            if path in ("/", "/index.html"):
                return self._serve_index()
            if path == "/api/me":
                return self._api_me(user)
            if path == "/api/list":
                return self._api_list(qs, user)
            if path == "/api/batches":
                return self._api_batches(qs, user)
            if path == "/api/batch":
                return self._api_batch(qs, user)
            if path == "/api/check":
                return self._api_check(qs, user)
            if path == "/api/file":
                return self._api_file(qs, user)
            if path == "/api/export":
                return self._api_export(user)
            if path == "/api/openfolder":
                return self._api_openfolder(qs)
            if path == "/api/notifications":
                return self._api_notifications(user)
            if path == "/api/logs":
                return self._api_logs(user)
            if path == "/api/users":
                return self._api_users(user)
            if path == "/api/stats/overview":
                return self._api_stats(user)
            if path == "/api/audit/status":
                return self._api_audit_status(user)
            if path == "/api/audit/shot":
                return self._api_audit_shot(qs, user)
            return self._err("未知地址", 404)
        except Exception as exc:
            traceback.print_exc()
            return self._err("服务端错误（%s）" % type(exc).__name__, 500)

    def _serve_spa(self):
        """新版前端是单页应用：所有前端路由都返回同一个 index.html。"""
        path = os.path.join(DIST_DIR, "index.html")
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError as exc:
            traceback.print_exc()
            return self._err(_file_error_hint("前端/dist/index.html", exc, DIST_DIR), 500)
        # 入口文件不能缓存，否则升级后浏览器还跑旧代码
        return self._send(200, data, "text/html; charset=utf-8",
                          {"Cache-Control": "no-store"})

    def _serve_dist(self, rel):
        """dist 里的静态资源（js/css/图片）。文件名带 hash，可以长缓存。"""
        full = dist_file(rel)
        if not full or not os.path.isfile(full):
            return self._err("文件不存在", 404)
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith((".js", ".css", ".html", ".svg", ".json")):
            ctype += "; charset=utf-8"
        try:
            with open(full, "rb") as f:
                data = f.read()
        except OSError as exc:
            traceback.print_exc()
            return self._err(_file_error_hint(os.path.basename(full), exc,
                                              os.path.dirname(full)), 500)
        cache = ("public, max-age=31536000, immutable"
                 if "/assets/" in ("/" + rel) else "no-cache")
        return self._send(200, data, ctype, {"Cache-Control": cache})

    def _serve_index(self):
        """
        把 style.css / app.js 的地址带上文件修改时间。
        这样以后升级替换了网页文件，浏览器一定会拿新的，
        不会因为缓存还显示旧样式、旧功能。
        """
        path = os.path.join(WEB_DIR, "index.html")
        if not os.path.isfile(path):
            return self._err("找不到网页文件 index.html", 404)
        try:
            with open(path, "rb") as f:
                html = f.read().decode("utf-8")
        except OSError as exc:
            traceback.print_exc()
            return self._err(_file_error_hint("index.html", exc, WEB_DIR), 500)
        except UnicodeDecodeError as exc:
            traceback.print_exc()
            return self._err("index.html 编码不对（%s），请用 UTF-8 保存后重传" % exc, 500)
        for name in ("style.css", "app.js", "echarts.min.js"):
            target = os.path.join(WEB_DIR, name)
            try:
                ver = int(os.path.getmtime(target))
            except OSError:
                ver = 0
            html = html.replace("/static/%s" % name, "/static/%s?v=%d" % (name, ver))
        return self._send(200, html.encode("utf-8"), "text/html; charset=utf-8")

    def _serve_file(self, path):
        if not os.path.isfile(path):
            return self._err("文件不存在：%s" % os.path.basename(path), 404)
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        if path.endswith((".html", ".js", ".css")):
            ctype += "; charset=utf-8"
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError as exc:
            traceback.print_exc()
            return self._err(_file_error_hint(os.path.basename(path), exc,
                                              os.path.dirname(path)), 500)
        return self._send(200, data, ctype)

    def _api_list(self, qs, user):
        def one(key):
            v = qs.get(key)
            return v[0] if v else None
        # 普通用户强制只看自己的；管理员可以按 owner 过滤，也可以看全部
        owner = None
        if not self._is_admin(user):
            owner = user["id"]
        elif one("owner"):
            owner = one("owner")
        records = STORE.list(
            q=one("q"), person=one("person"), month=one("month"),
            status=one("status"), batch_id=one("batch"), audit=one("audit"),
            owner_id=owner, review_status=one("review"),
            sort=one("sort") or "invoice_date",
            desc=(one("desc") or "1") != "0",
        )
        batches = STORE.list_batches(limit=500, owner_id=owner)
        return self._json({
            "ok": True,
            "records": records,
            "stats": STORE.stats(owner_id=owner),
            "persons": STORE.persons(),
            "months": STORE.months(),
            "batches": [{"id": b["id"], "batch_no": b["batch_no"],
                         "review_status": b.get("review_status")}
                        for b in batches],
        })

    def _api_batches(self, qs, user):
        def one(key):
            v = qs.get(key)
            return v[0] if v else None
        owner = None if self._is_admin(user) else user["id"]
        return self._json({"ok": True, "batches": STORE.list_batches(
            q=one("q"), status=one("status"), person=one("person"),
            owner_id=owner, review_status=one("review"))})

    def _api_batch(self, qs, user):
        bid = (qs.get("id") or [""])[0]
        if not bid.isdigit():
            return self._err("缺少报销单编号")
        b = STORE.batch_detail(int(bid))
        if not b:
            return self._err("找不到这张报销单", 404)
        # 普通用户只能看自己的单
        if not self._is_admin(user) and int(b.get("owner_id") or 0) != int(user["id"]):
            return self._err("这张报销单不属于你", 403)
        b["issues"] = STORE.review_issues(b)
        b["can_edit"] = self._batch_editable(b, user)
        return self._json({"ok": True, "batch": b})

    def _batch_editable(self, batch, user):
        """
        谁能在什么时候改这张单：
          管理员：随时（要改已通过的单，先退回）
          普通用户：只有自己的、且状态是 returned（已退回）
        """
        if not batch:
            return False
        st = str(batch.get("review_status") or "approved")
        if self._is_admin(user):
            return st != "approved"
        return (int(batch.get("owner_id") or 0) == int(user["id"])) and st == "returned"

    def _load_batch(self, bid, user, need_edit=False):
        """取单据 + 校验归属/可编辑性。返回 (batch, 错误响应)。"""
        try:
            b = STORE.batch_detail(int(bid))
        except (TypeError, ValueError):
            b = None
        if not b:
            return None, self._err("找不到这张报销单", 404)
        if not self._is_admin(user) and int(b.get("owner_id") or 0) != int(user["id"]):
            return None, self._err("这张报销单不属于你", 403)
        if need_edit and not self._batch_editable(b, user):
            return None, self._err("这张单当前状态不能修改（待审核或已通过）", 409)
        return b, None

    def _load_invoice(self, rid, user, need_edit=False):
        """取发票 + 校验归属/可编辑性。返回 (record, 错误响应)。"""
        rec = STORE.get(int(rid)) if str(rid or "").isdigit() else None
        if not rec:
            return None, self._err("找不到这张发票", 404)
        if not self._is_admin(user) and int(rec.get("owner_id") or 0) != int(user["id"]):
            return None, self._err("这张发票不属于你", 403)
        if need_edit and rec.get("batch_id"):
            b, err = self._load_batch(rec["batch_id"], user, need_edit=True)
            if err:
                return None, err
        return rec, None

    def _api_check(self, qs, user):
        no = (qs.get("no") or [""])[0]
        no = "".join(ch for ch in no if ch.isdigit())
        rec = STORE.get_by_no(no) if no else None
        # 普通用户只告诉他"有没有"，不回传别人那张票的明细
        if rec and not self._is_admin(user) and int(rec.get("owner_id") or 0) != int(user["id"]):
            return self._json({"ok": True, "exists": True, "record": None, "others": True})
        return self._json({"ok": True, "exists": bool(rec), "record": rec})

    def _api_file(self, qs, user):
        rid = (qs.get("id") or [""])[0]
        rec = STORE.get(int(rid)) if rid.isdigit() else None
        if not rec or not rec.get("file_rel"):
            return self._err("没有对应的发票原件", 404)
        if not self._is_admin(user) and int(rec.get("owner_id") or 0) != int(user["id"]):
            return self._err("这个原件不属于你", 403)
        path = STORE.rel_path(rec["file_rel"])
        if not os.path.isfile(path):
            return self._err("原件已不在原位置", 404)
        ext = os.path.splitext(path)[1].lower()
        ctype = "application/pdf" if ext == ".pdf" else "application/octet-stream"
        with open(path, "rb") as f:
            data = f.read()
        name = urllib.parse.quote(os.path.basename(path))
        return self._send(200, data, ctype, {"Content-Disposition": "inline; filename*=UTF-8''" + name})

    def _api_openfolder(self, qs):
        """在资源管理器里打开 发票存档 / 数据 文件夹（仅桌面系统）。"""
        which = (qs.get("which") or ["archive"])[0]
        target = STORE.archive_dir if which == "archive" else STORE.data_dir
        if not can_open_local():
            return self._err("服务器没有图形界面，请在浏览器里下载查看；"
                             "或用命令行进入：%s" % target, 501)
        try:
            open_local_path(target)
        except Exception as exc:
            return self._err("打开文件夹失败：%s" % exc)
        return self._json({"ok": True})

    def _api_export(self, user):
        import datetime
        name = "发票台账_%s.xlsx" % datetime.date.today().strftime("%Y%m%d")
        tmp = os.path.join(STORE.tmp_dir, name)
        # 普通用户只导出自己的
        STORE.sync_excel(tmp, owner_id=None if self._is_admin(user) else user["id"])
        if not os.path.isfile(tmp):
            return self._err("导出失败", 500)
        with open(tmp, "rb") as f:
            data = f.read()
        try:
            os.remove(tmp)
        except OSError:
            pass
        return self._send(
            200, data,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            {"Content-Disposition": "attachment; filename*=UTF-8''" + urllib.parse.quote(name)},
        )

    # ------------------------------------------------------------------
    # POST
    # ------------------------------------------------------------------
    def do_POST(self):
        try:
            _, path = self._query()
            # 登录、注册不需要会话
            if path == "/api/login":
                return self._api_login()
            if path == "/api/register":
                return self._api_register()

            user = self._current_user()
            if not user:
                return self._need_login()
            if path == "/api/logout":
                return self._api_logout(user)
            if path == "/api/password":
                return self._api_password(user)
            if path == "/api/parse":
                return self._api_parse(user)
            if path == "/api/add":
                return self._api_add(user)
            if path == "/api/batch_add":
                return self._api_batch_add(user)
            if path == "/api/batch/review":
                return self._api_batch_review(user)
            if path == "/api/batch_status":
                return self._api_batch_status(user)
            if path == "/api/batch_resubmit":
                return self._api_batch_resubmit(user)
            if path == "/api/batch_update":
                return self._api_batch_update(user)
            if path == "/api/attach":
                return self._api_attach(user)
            if path == "/api/detach":
                return self._api_detach(user)
            if path == "/api/update":
                return self._api_update(user)
            if path == "/api/delete":
                return self._api_delete(user)
            if path == "/api/batch_delete":
                return self._api_batch_delete(user)
            if path == "/api/status":
                return self._api_status(user)
            if path == "/api/openfile":
                return self._api_openfile(user)
            if path == "/api/notifications/read":
                return self._api_notifications_read(user)
            if path == "/api/users/create":
                return self._api_user_create(user)
            if path == "/api/users/update":
                return self._api_user_update(user)
            if path == "/api/users/reset_password":
                return self._api_user_reset(user)
            if path == "/api/audit/start":
                return self._api_audit_start(user)
            if path == "/api/audit/stop":
                return self._api_audit_stop(user)
            return self._err("未知地址", 404)
        except Exception as exc:
            traceback.print_exc()
            return self._err("服务端错误（%s）" % type(exc).__name__, 500)

    def _api_parse(self, user):
        """接收原始文件字节，落临时目录并自动识别。"""
        raw = self._body()
        if not raw:
            return self._err("没有收到文件内容")
        name_hdr = self.headers.get("X-File-Name") or ""
        try:
            filename = base64.b64decode(name_hdr).decode("utf-8")
        except Exception:
            filename = urllib.parse.unquote(name_hdr) or "发票"
        ext = os.path.splitext(filename)[1].lower()
        if ext not in ALLOWED_EXT:
            return self._err("只支持 PDF 和 OFD 发票文件，收到的是 %s" % (ext or "未知格式"))

        token = uuid.uuid4().hex + ext
        tmp_path = os.path.join(STORE.tmp_dir, token)
        with open(tmp_path, "wb") as f:
            f.write(raw)

        fields = reader.extract(tmp_path)
        no = fields.get("invoice_no")
        rec = STORE.get_by_no(no) if no else None
        token = token
        if rec:
            # 重复的发票存不了，临时文件立刻清掉，别在临时目录越堆越多
            try:
                os.remove(tmp_path)
            except OSError:
                pass
            token = None
        return self._json({
            "ok": True,
            "token": token,
            "file_name": filename,
            "fields": fields,
            "exists": bool(rec),
            "record": rec,
        })

    def _clean(self, data, only_present=False):
        """
        整理前端传来的字段。
        only_present=True 时只处理请求里真正带上的字段——
        修改记录必须是「改哪项动哪项」，没带上的一律不动，否则会把别的字段清空。
        """
        out = {}
        for k in ("invoice_no", "invoice_date", "seller", "buyer", "category",
                  "person", "payee", "reimburse_date", "note", "file_name", "source"):
            if only_present and k not in data:
                continue
            v = data.get(k)
            out[k] = (str(v).strip() if v is not None else None) or None
        for k in ("amount", "amount_net", "tax"):
            if only_present and k not in data:
                continue
            v = data.get(k)
            try:
                out[k] = round(float(v), 2) if v not in (None, "") else None
            except (TypeError, ValueError):
                out[k] = None
        if out.get("invoice_no"):
            out["invoice_no"] = "".join(ch for ch in out["invoice_no"] if ch.isdigit())
        return out

    def _api_add(self, user):
        data = self._json_body()
        rec = self._clean(data)
        rec["owner_id"] = user["id"]
        if not rec.get("invoice_no"):
            return self._err("请先填写发票号码")
        if not rec.get("person"):
            return self._err("请填写报销人", 400, field="person")
        if not rec.get("payee"):
            return self._err("请填写收款人", 400, field="payee")
        token = data.get("token")
        tmp_file = None
        if token:
            cand = os.path.join(STORE.tmp_dir, os.path.basename(token))
            if os.path.isfile(cand):
                tmp_file = cand
        try:
            rid = STORE.add(rec, tmp_file=tmp_file)
        except store_mod.DuplicateError as dup:
            return self._err("该发票号码已经登记过了", 409, duplicate=True, record=dup.record)
        except Exception as exc:
            return self._err("保存失败：%s" % exc, 500)
        self._apply_audit_preset([rid])
        return self._json({"ok": True, "id": rid, "record": STORE.get(rid)})

    def _apply_audit_preset(self, ids):
        """
        登记完就顺手判断"这张票平台本来就不给查"（比如超过 5 年），
        直接标成"无需查验"，省得以后排在待查列表里白跑一趟。
        """
        changed = False
        for rid in ids or []:
            try:
                rec = STORE.get(int(rid))
                if not rec or int(rec.get("audit_status") or 0) != 0:
                    continue
                preset = audit_mod.preset_skip(rec)
                if preset:
                    STORE.set_audit(int(rid), preset[0], preset[1], None, quiet=True)
                    changed = True
            except Exception:
                traceback.print_exc()
        if changed:
            try:
                STORE.sync_excel()
            except Exception:
                pass

    # ---------------- 报销单 ----------------
    def _tmp_of(self, token):
        if not token:
            return None
        cand = os.path.join(STORE.tmp_dir, os.path.basename(token))
        return cand if os.path.isfile(cand) else None

    def _api_batch_add(self, user):
        """一次登记多张发票，归入同一张报销单"""
        data = self._json_body()
        items_in = data.get("items") or []
        if not items_in:
            return self._err("本单还没有发票，请先拖入或手工加一行")
        person = (data.get("person") or "").strip()
        payee = (data.get("payee") or "").strip()
        if not person:
            return self._err("请填写报销人", 400, field="person")
        if not payee:
            return self._err("请填写收款人", 400, field="payee")

        items, bad = [], None
        for it in items_in:
            rec = self._clean(it)
            if not rec.get("invoice_no"):
                bad = "有发票号码是空的，请补上再提交"
                break
            rec["note"] = (it.get("note") or "").strip() or None
            items.append((rec, self._tmp_of(it.get("token"))))
        if bad:
            return self._err(bad)

        try:
            bid, ids = STORE.add_batch(
                items,
                batch_no=(data.get("batch_no") or "").strip() or None,
                note=(data.get("note") or "").strip() or None,
                person=person, payee=payee,
                reimburse_date=(data.get("reimburse_date") or "").strip() or None,
                owner_id=user["id"], review_status="submitted")
        except store_mod.DuplicateError as dup:
            rec = dup.record or {}
            if rec.get("in_batch"):
                return self._err("本单里有重复的发票号码：%s" % rec.get("invoice_no"),
                                 409, duplicate=True, record=rec)
            return self._err("发票号码 %s 之前已经登记过了" % rec.get("invoice_no", ""),
                             409, duplicate=True, record=rec)
        except Exception as exc:
            return self._err("提交失败：%s" % exc, 500)
        self._apply_audit_preset(ids)
        try:
            b = STORE.batch_detail(bid)
            STORE.notify_admins(
                "submit", "新单据 %s 待审核" % (b.get("batch_no") or ""),
                "%s 提交了 %d 张发票，合计 ¥%.2f" % (
                    user.get("display_name") or user["username"],
                    len(ids), float(b.get("amount") or 0)), bid)
            STORE.log_action(user["id"], user["username"], "submit", "batch", bid,
                             b.get("batch_no"))
        except Exception:
            traceback.print_exc()
        return self._json({"ok": True, "batch_id": bid, "ids": ids,
                           "batch": STORE.batch_detail(bid)})

    def _api_batch_status(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        bid = data.get("id")
        if not bid:
            return self._err("缺少报销单编号")
        try:
            b = STORE.set_batch_status(int(bid), data.get("status"))
        except Exception as exc:
            return self._err("切换失败：%s" % exc, 500)
        STORE.log_action(user["id"], user["username"], "batch_paid",
                         "batch", int(bid), "标记%s" % ("已报销" if data.get("status") else "未报销"))
        return self._json({"ok": True, "batch": b, "stats": STORE.stats()})

    def _api_batch_update(self, user):
        data = self._json_body()
        bid = data.get("id")
        if not bid:
            return self._err("缺少报销单编号")
        b0, err = self._load_batch(bid, user, need_edit=True)
        if err:
            return err
        payload = {}
        for k in ("batch_no", "note", "person", "payee", "reimburse_date"):
            if k in data:
                v = data.get(k)
                payload[k] = (str(v).strip() if v is not None else None) or None
        try:
            b = STORE.update_batch(int(bid), payload)
        except store_mod.DuplicateError as dup:
            return self._err("这个报销单号已经被别的单用了", 409, duplicate=True,
                             record=dup.record)
        except Exception as exc:
            return self._err("修改失败：%s" % exc, 500)
        return self._json({"ok": True, "batch": b})

    def _api_attach(self, user):
        """把已登记的发票补挂到某张报销单"""
        data = self._json_body()
        ids = [int(x) for x in (data.get("ids") or []) if str(x).isdigit()]
        if not ids:
            return self._err("请先选中要归单的发票")
        # 源发票必须是自己能改的（管理员不限）
        for rid in ids:
            _rec, err = self._load_invoice(rid, user, need_edit=True)
            if err:
                return err
        bid = data.get("batch_id")
        owner = user["id"]
        if bid:
            b0, err = self._load_batch(bid, user, need_edit=True)
            if err:
                return err
            owner = b0.get("owner_id") or user["id"]
        try:
            b = STORE.attach_to_batch(
                ids,
                batch_id=bid,
                batch_no=(data.get("batch_no") or "").strip() or None,
                note=(data.get("note") or "").strip() or None,
                owner_id=owner)
        except Exception as exc:
            return self._err("归单失败：%s" % exc, 500)
        return self._json({"ok": True, "batch": b, "stats": STORE.stats()})

    def _api_detach(self, user):
        data = self._json_body()
        ids = [int(x) for x in (data.get("ids") or []) if str(x).isdigit()]
        if not ids:
            return self._err("请先选中要拆出的发票")
        for rid in ids:
            _rec, err = self._load_invoice(rid, user, need_edit=True)
            if err:
                return err
        try:
            STORE.detach(ids)
        except Exception as exc:
            return self._err("拆出失败：%s" % exc, 500)
        return self._json({"ok": True, "stats": STORE.stats()})

    def _api_update(self, user):
        data = self._json_body()
        rid = data.get("id")
        if not rid:
            return self._err("缺少记录编号")
        old, err = self._load_invoice(rid, user, need_edit=True)
        if err:
            return err
        rec = self._clean(data, only_present=True)
        try:
            STORE.update(int(rid), rec)
        except store_mod.DuplicateError as dup:
            return self._err("这个发票号码已经被另一条记录用了，不能改成它", 409,
                             duplicate=True, record=dup.record)
        except Exception as exc:
            return self._err("修改失败：%s" % exc, 500)
        return self._json({"ok": True, "record": STORE.get(int(rid))})

    def _can_delete(self, rec, user):
        """
        删除规则：
          · 只有发起人（owner）能删
          · 待审核（可以撤回）、已退回 都能删；已通过不能删
          · 没归单的散票，只有本人能删
        返回 (True, None) 或 (False, 原因)
        """
        if int(rec.get("owner_id") or 0) != int(user["id"]):
            return False, "只有提交人自己才能删除"
        bid = rec.get("batch_id")
        if not bid:
            return True, None
        b = STORE.get_batch(int(bid))
        if not b:
            return True, None
        if int(b.get("owner_id") or 0) != int(user["id"]):
            return False, "只有提交人自己才能删除"
        st = str(b.get("review_status") or "approved")
        if st == "approved":
            return False, "单据已通过审核，不能删除"
        return True, None

    def _api_delete(self, user):
        data = self._json_body()
        rid = data.get("id")
        if not rid:
            return self._err("缺少记录编号")
        rec = STORE.get(int(rid)) if str(rid).isdigit() else None
        if not rec:
            return self._err("找不到这张发票", 404)
        ok, why = self._can_delete(rec, user)
        if not ok:
            return self._err(why, 403)
        STORE.delete(int(rid))
        STORE.log_action(user["id"], user["username"], "delete_invoice",
                         "invoice", int(rid), rec.get("invoice_no"))
        return self._json({"ok": True, "stats": STORE.stats()})

    def _api_batch_delete(self, user):
        """整单删除：一次请求、一次校验、一起删，不会删一半。
        待审核的单删掉等于"撤回"——会通知管理员。"""
        data = self._json_body()
        bid = data.get("id")
        if not str(bid or "").isdigit():
            return self._err("缺少报销单编号")
        b = STORE.batch_detail(int(bid))
        if not b:
            return self._err("找不到这张报销单", 404)
        if int(b.get("owner_id") or 0) != int(user["id"]):
            return self._err("只有提交人自己才能删除这张单", 403)
        st = str(b.get("review_status") or "approved")
        if st == "approved":
            return self._err("单据已通过审核，不能删除", 409)
        withdrawn = (st == "submitted")
        n = STORE.delete_batch(int(bid))
        STORE.log_action(user["id"], user["username"],
                         "withdraw" if withdrawn else "delete_batch",
                         "batch", int(bid), "%s｜%d 张" % (b.get("batch_no"), n))
        if withdrawn:
            # 管理员那边可能正等着审，撤回要说一声
            try:
                STORE.notify_admins(
                    "withdraw", "单据 %s 已撤回" % b.get("batch_no"),
                    "%s 撤回了这张单，不用再审了" % (
                        user.get("display_name") or user["username"]),
                    int(bid))
            except Exception:
                traceback.print_exc()
        return self._json({"ok": True, "deleted": n, "withdrawn": withdrawn,
                           "stats": STORE.stats()})

    def _api_status(self, user):
        """切换报销状态：0=未报销，1=已报销（仅管理员）"""
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        rid = data.get("id")
        if not rid:
            return self._err("缺少记录编号")
        rec = STORE.get(int(rid))
        if not rec:
            return self._err("找不到这条记录", 404)
        try:
            rec = STORE.set_status(int(rid), data.get("status"))
        except Exception as exc:
            return self._err("切换状态失败：%s" % exc, 500)
        return self._json({"ok": True, "record": rec, "stats": STORE.stats()})

    # ------------------------------------------------------------------
    # 审核流程
    # ------------------------------------------------------------------
    def _api_batch_review(self, user):
        """管理员审核：通过 / 退回（可勾选有问题的发票）"""
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        bid = data.get("id")
        action = str(data.get("action") or "").strip()
        if not bid:
            return self._err("缺少报销单编号")
        if action not in ("approve", "return"):
            return self._err("审核动作只能是 approve 或 return")
        b = STORE.batch_detail(int(bid))
        if not b:
            return self._err("找不到这张报销单", 404)
        if str(b.get("review_status") or "") != "submitted":
            return self._err("这张单不是待审核状态，不能重复审核", 409)

        note = str(data.get("note") or "").strip()
        issues = data.get("issues") or []
        if action == "return":
            if not note:
                return self._err("退回时请写明原因，方便提交人修改")
            try:
                issues = [int(x) for x in issues]
            except (TypeError, ValueError):
                issues = []
            status = "returned"
        else:
            status = "approved"
            issues = []

        b = STORE.set_review(int(bid), status, reviewer_id=user["id"],
                             note=note or None, issues=issues)
        # 通知提交人
        owner = b.get("owner_id")
        if owner:
            if status == "returned":
                STORE.add_notification(
                    owner, "return",
                    "单据 %s 被退回" % b.get("batch_no"),
                    note or "请修改后重新提交", int(bid))
            else:
                STORE.add_notification(
                    owner, "approve",
                    "单据 %s 已通过审核" % b.get("batch_no"),
                    note or "审核通过，单据已锁定", int(bid))
        STORE.log_action(user["id"], user["username"],
                         "review_" + status, "batch", int(bid),
                         "%s｜%s" % (b.get("batch_no"), note or ""))
        return self._json({"ok": True, "batch": b,
                           "stats": STORE.stats()})

    def _api_batch_resubmit(self, user):
        """用户把「已退回」的单改完后重新提交"""
        data = self._json_body()
        bid = data.get("id")
        if not bid:
            return self._err("缺少报销单编号")
        b, err = self._load_batch(bid, user, need_edit=True)
        if err:
            return err
        b = STORE.set_review(int(bid), "submitted", reviewer_id=None,
                             note=None, issues=[], )
        try:
            STORE.notify_admins("submit", "单据 %s 重新提交" % b.get("batch_no"),
                                "%s 修改后重新提交，请审核" % (user.get("display_name") or user["username"]),
                                int(bid))
        except Exception:
            traceback.print_exc()
        STORE.log_action(user["id"], user["username"], "resubmit", "batch", int(bid))
        return self._json({"ok": True, "batch": b})

    # ------------------------------------------------------------------
    # 消息通知
    # ------------------------------------------------------------------
    def _api_notifications(self, user):
        items = STORE.list_notifications(user["id"])
        return self._json({"ok": True, "items": items,
                           "unread": STORE.unread_count(user["id"])})

    def _api_notifications_read(self, user):
        data = self._json_body()
        ids = data.get("ids") or []
        STORE.mark_notifications_read(user["id"], ids or None)
        return self._json({"ok": True, "unread": STORE.unread_count(user["id"])})

    # ------------------------------------------------------------------
    # 用户管理（管理员）
    # ------------------------------------------------------------------
    def _api_users(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        rows = []
        for u in STORE.list_users():
            rows.append({
                "id": u["id"], "username": u["username"],
                "display_name": u.get("display_name"),
                "role": u["role"], "status": int(u["status"] or 0),
                "created_at": u.get("created_at"),
                "last_login_at": u.get("last_login_at"),
            })
        return self._json({"ok": True, "users": rows})

    def _api_user_create(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        username = str(data.get("username") or "").strip()
        password = str(data.get("password") or "") or auth_mod.new_password(10)
        display = str(data.get("display_name") or "").strip() or username
        role = "admin" if data.get("role") == "admin" else "user"

        problem = auth_mod.username_problem(username)
        if problem:
            return self._err(problem)
        if STORE.get_user_by_name(username):
            return self._err("这个用户名已经被占用了")
        # 管理员创建的账号，要求首次登录改密
        uid = STORE.create_user(username, auth_mod.hash_password(password),
                                display, role=role, must_change_pw=1)
        STORE.log_action(user["id"], user["username"], "user_create", "user", uid,
                         "%s（%s）" % (username, role))
        STORE.add_notification(uid, "account", "账号已开通",
                               "你的账号由管理员创建，请登录后修改密码")
        return self._json({"ok": True, "id": uid, "password": password})

    def _api_user_update(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        uid = data.get("id")
        target = STORE.get_user(uid) if str(uid or "").isdigit() else None
        if not target:
            return self._err("找不到这个用户", 404)

        fields = {}
        if "display_name" in data:
            fields["display_name"] = (str(data.get("display_name") or "").strip()
                                      or target["username"])
        if "status" in data:
            want = 1 if int(data.get("status") or 0) else 0
            # 不能把自己停用，也不能停用最后一个管理员
            if int(uid) == int(user["id"]) and want == 0:
                return self._err("不能停用自己")
            if target["role"] == "admin" and want == 0 and STORE.count_admins() <= 1:
                return self._err("这是最后一个管理员，不能停用")
            fields["status"] = want
        if "role" in data:
            want = "admin" if data.get("role") == "admin" else "user"
            if target["role"] == "admin" and want == "user" and STORE.count_admins() <= 1:
                return self._err("这是最后一个管理员，不能降级")
            fields["role"] = want

        STORE.update_user(int(uid), **fields)
        if fields.get("status") == 0:
            STORE.delete_user_sessions(int(uid))   # 停用后立刻掉线
        # 日志写成人话，别把 Python 字典直接塞进去
        desc = []
        if "display_name" in fields:
            desc.append("姓名→%s" % fields["display_name"])
        if "role" in fields:
            desc.append("角色→%s" % ("管理员" if fields["role"] == "admin" else "普通用户"))
        if "status" in fields:
            desc.append("状态→%s" % ("启用" if fields["status"] else "停用"))
        STORE.log_action(user["id"], user["username"], "user_update", "user", int(uid),
                         "%s｜%s" % (target["username"], "，".join(desc) or "无变化"))
        return self._json({"ok": True})

    def _api_user_reset(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        uid = data.get("id")
        target = STORE.get_user(uid) if str(uid or "").isdigit() else None
        if not target:
            return self._err("找不到这个用户", 404)
        pw = auth_mod.new_password(10)
        STORE.set_password(int(uid), auth_mod.hash_password(pw), must_change_pw=1)
        STORE.delete_user_sessions(int(uid))
        STORE.log_action(user["id"], user["username"], "user_reset_pw", "user", int(uid))
        STORE.add_notification(int(uid), "account", "密码已被重置",
                               "管理员重置了你的密码，请用新密码登录")
        return self._json({"ok": True, "password": pw})

    # ------------------------------------------------------------------
    # 操作日志
    # ------------------------------------------------------------------
    def _api_logs(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        return self._json({"ok": True, "logs": STORE.list_logs(300)})

    # ------------------------------------------------------------------
    # 数据总览
    # ------------------------------------------------------------------
    def _api_stats(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        import datetime as _dt
        month = _dt.date.today().strftime("%Y-%m")
        with STORE._conn() as conn:
            def scalar(sql, args=()):
                row = conn.execute(sql, args).fetchone()
                return row[0] if row else 0
            overview = {
                "pending": scalar("SELECT COUNT(*) FROM batches "
                                  "WHERE COALESCE(review_status,'approved')='submitted'"),
                "returned": scalar("SELECT COUNT(*) FROM batches "
                                   "WHERE review_status='returned'"),
                "approved": scalar("SELECT COUNT(*) FROM batches "
                                   "WHERE review_status='approved'"),
                "users": scalar("SELECT COUNT(*) FROM users WHERE status=1"),
                "claims": scalar("SELECT COUNT(*) FROM invoices"),
                "claims_amount": round(scalar("SELECT COALESCE(SUM(amount),0) FROM invoices"), 2),
                "month_count": scalar(
                    "SELECT COUNT(*) FROM invoices "
                    "WHERE substr(COALESCE(invoice_date, created_at),1,7) = ?", (month,)),
                "month_amount": round(scalar(
                    "SELECT COALESCE(SUM(amount),0) FROM invoices "
                    "WHERE substr(COALESCE(invoice_date, created_at),1,7) = ?", (month,)), 2),
            }
            # 平均审核时长（小时）：只算已经通过的单
            avg_h = conn.execute(
                "SELECT AVG((julianday(reviewed_at) - julianday(created_at)) * 24) "
                "FROM batches WHERE review_status = 'approved'"
                " AND reviewed_at IS NOT NULL AND created_at IS NOT NULL").fetchone()[0]
            overview["avg_review_hours"] = round(avg_h, 1) if avg_h else 0
            # 近 12 个月趋势
            trend = {}
            for r in conn.execute(
                    "SELECT substr(COALESCE(invoice_date, created_at),1,7) m, "
                    " COUNT(*), COALESCE(SUM(amount),0) FROM invoices "
                    "WHERE m IS NOT NULL AND m <> '' GROUP BY m ORDER BY m"):
                trend[r[0]] = {"count": r[1], "amount": round(r[2], 2)}
            months = sorted(trend.keys())[-12:]
            # 按报销人 / 销售方 TOP 10
            by_person = [{"name": r[0] or "未填", "count": r[1],
                          "amount": round(r[2], 2)} for r in conn.execute(
                "SELECT person, COUNT(*), COALESCE(SUM(amount),0) FROM invoices "
                "GROUP BY person ORDER BY SUM(amount) DESC LIMIT 10")]
            by_seller = [{"name": r[0] or "未填", "count": r[1],
                          "amount": round(r[2], 2)} for r in conn.execute(
                "SELECT seller, COUNT(*), COALESCE(SUM(amount),0) FROM invoices "
                "GROUP BY seller ORDER BY SUM(amount) DESC LIMIT 10")]
            by_audit = []
            for r in conn.execute("SELECT COALESCE(audit_status,0), COUNT(*) FROM invoices "
                                  "GROUP BY 1"):
                by_audit.append({"status": int(r[0]), "count": r[1]})
        return self._json({
            "ok": True,
            "overview": overview,
            "trend": [{"month": m, **trend[m]} for m in months],
            "by_person": by_person,
            "by_seller": by_seller,
            "by_audit": by_audit,
        })

    def _api_openfile(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        rid = data.get("id")
        rec = STORE.get(int(rid)) if rid else None
        if not rec or not rec.get("file_rel"):
            return self._err("没有对应的发票原件", 404)
        path = STORE.rel_path(rec["file_rel"])
        if not os.path.isfile(path):
            return self._err("原件已不在原位置", 404)
        if not can_open_local():
            return self._err("服务器没有图形界面，请用「查看原件」在浏览器里打开", 501)
        try:
            open_local_path(path)
        except Exception as exc:
            return self._err("打开失败：%s" % exc)
        return self._json({"ok": True})

    # ------------------------------------------------------------------
    # 发票查验
    # ------------------------------------------------------------------
    def _api_audit_status(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        return self._json({
            "ok": True,
            "state": AUDIT_RUNNER.snapshot(),
            "stats": STORE.stats(),
            "config": {
                "page_version": audit_mod.CONFIG["page_version"],
                "headless": audit_mod.CONFIG["headless"],
                "model_ready": audit_mod.model_ready(),
            },
        })

    def _pick_audit_ids(self, data):
        """挑出要查验的发票：给了 ids 就按 ids，否则按范围挑。"""
        ids = data.get("ids") or []
        if ids:
            out = []
            for x in ids:
                try:
                    out.append(int(x))
                except (TypeError, ValueError):
                    continue
            return out

        scope = (data.get("scope") or "unchecked").strip()
        force = bool(data.get("force"))
        out = []
        for r in STORE.list(limit=100000):
            st = int(r.get("audit_status") or 0)
            if scope == "unpaid":
                if int(r.get("status") or 0) == 0:
                    out.append(r["id"])
            elif scope == "failed":
                if st == 2:
                    out.append(r["id"])
            elif scope == "all":
                # 30 天内已经查通过的就不再打扰平台（配额有限），force=true 可强制重查
                if st == 1 and not force and _audited_recently(r.get("audit_at")):
                    continue
                out.append(r["id"])
            else:                       # unchecked：未查验 + 查验失败
                if st in (0, 2):
                    out.append(r["id"])
        return out

    def _api_audit_start(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        data = self._json_body()
        try:
            ids = self._pick_audit_ids(data)
        except Exception as exc:
            return self._err("挑选待查验发票失败：%s" % exc, 500)
        if not ids:
            return self._err("没有需要查验的发票")
        ok, msg = AUDIT_RUNNER.start(ids)
        if not ok:
            return self._err(msg, 409)
        return self._json({"ok": True, "count": len(ids),
                           "state": AUDIT_RUNNER.snapshot()})

    def _api_audit_stop(self, user):
        if not self._is_admin(user):
            return self._need_admin()
        AUDIT_RUNNER.stop()
        return self._json({"ok": True, "state": AUDIT_RUNNER.snapshot()})

    def _api_audit_shot(self, qs, user):
        rid = (qs.get("id") or [""])[0]
        rec = STORE.get(int(rid)) if rid.isdigit() else None
        # 查验结果只给管理员看；用户端不展示截图
        if not self._is_admin(user):
            return self._need_admin()
        rel = (rec or {}).get("audit_shot")
        if not rel:
            return self._err("这张发票还没有查验截图", 404)
        base = os.path.abspath(STORE.shot_dir)
        path = os.path.abspath(STORE.rel_path(rel))
        if not path.startswith(base + os.sep):
            return self._err("路径不合法", 400)
        if not os.path.isfile(path):
            return self._err("截图文件不在了", 404)
        with open(path, "rb") as f:
            data = f.read()
        return self._send(200, data, "image/png")


# ----------------------------------------------------------------------
# 启动
# ----------------------------------------------------------------------
def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


def pick_port():
    for p in range(DEFAULT_PORT, DEFAULT_PORT + 20):
        if not port_in_use(p):
            return p
    return DEFAULT_PORT


def _is_our_server(port):
    """确认占用端口的是本系统，而不是别的程序。"""
    import urllib.request
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/api/ping" % port, timeout=2) as r:
            return APP_TAG in r.read().decode("utf-8", "ignore")
    except Exception:
        return False


def _argv_value(flag):
    """取 --flag value 形式的参数值。"""
    if flag in sys.argv:
        i = sys.argv.index(flag)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return None


def main():
    # 输出到管道/重定向时，用系统默认编码并容错，避免中文报错
    try:
        if not sys.stdout.isatty():
            sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass

    # 监听地址与端口：服务器部署一般传 --host 0.0.0.0
    host = _argv_value("--host") or DEFAULT_HOST
    try:
        port = int(_argv_value("--port") or DEFAULT_PORT)
    except ValueError:
        port = DEFAULT_PORT
    open_browser = ("--no-browser" not in sys.argv) and (host in ("127.0.0.1", "localhost"))

    # 本地模式：系统已经在运行就把浏览器叫起来，不重复开第二个服务
    if host in ("127.0.0.1", "localhost") and port_in_use(port) and _is_our_server(port):
        url = "http://127.0.0.1:%d/" % port
        print("人数发票登记系统已经在运行，正在为你打开浏览器： %s" % url)
        try:
            sys.stdout.flush()
        except Exception:
            pass
        if open_browser:
            webbrowser.open(url)
        sys.exit(3)          # 3 = 已经在运行，启动脚本据此给出对应提示

    if host in ("127.0.0.1", "localhost") and not port_in_use(port):
        pass
    elif port_in_use(port):
        # 服务器模式：端口被占就直接退出，别悄悄换端口让运维找不到
        print("端口 %d 已被占用，请换一个：--port 8766" % port)
        sys.exit(1)

    show_host = "127.0.0.1" if host in ("0.0.0.0", "::") else host
    url = "http://%s:%d/" % (show_host, port)

    print("=" * 58)
    print("  人数发票登记台账  正在运行")
    if host in ("0.0.0.0", "::"):
        print("  监听地址： %s:%d（局域网/外网可访问）" % (host, port))
        print("  本机访问： %s" % url)
        if AUTH:
            print("  访问口令： 已开启（环境变量 INVOICE_AUTH）")
        else:
            print("  ⚠ 没有设访问口令：知道地址的人都能看台账。")
            print("    建议设置环境变量 INVOICE_AUTH=\"用户名:密码\" 后重启。")
    else:
        print("  请使用浏览器访问： %s" % url)
    print("  数据目录： %s" % STORE.data_dir)
    print("  发票存档： %s" % STORE.archive_dir)
    print("-" * 58)
    print("  运行平台： %s    可在本机打开文件： %s" % (sys.platform, can_open_local()))
    fd = _open_fd_count()
    if fd:
        print("  文件句柄： 已用 %d / 上限 %s%s"
              % (fd[0], fd[1], "  ⚠ 快用完了，建议把 LimitNOFILE 调大" if fd[2] else ""))
    print("=" * 58)

    # 上次异常退出可能留下浏览器进程，每个占几百个句柄，先清掉
    _cleanup_stray_browsers()

    # 启动时自检：PDF 解析组件在不在
    try:
        reader.import_fitz()
    except Exception as exc:
        print()
        print("!" * 58)
        print("  【重要】缺少 PDF 解析组件，发票号码将无法自动识别！")
        print("  原因： %s" % exc)
        print("  程序找的运行库目录： %s" % LIB_DIR)
        print("  该目录存在吗： %s" % os.path.isdir(LIB_DIR))
        print("  解决办法（Linux）：pip install pymupdf")
        print("  解决办法（Windows）：把完整的「运行库」文件夹放回项目目录，")
        print("            然后关掉本窗口，重新双击 启动.bat。")
        print("!" * 58)
    try:
        sys.stdout.flush()
    except Exception:
        pass

    httpd = ThreadingHTTPServer((host, port), Handler)
    if open_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
        print("已退出。")


if __name__ == "__main__":
    main()
