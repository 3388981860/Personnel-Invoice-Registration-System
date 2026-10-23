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
import json
import mimetypes
import os
import socket
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
sys.path.insert(0, LIB_DIR)
sys.path.insert(0, APP_DIR)

import reader      # noqa: E402
import store as store_mod  # noqa: E402

STORE = store_mod.Store(BASE)
APP_TAG = "invoice-book"

DEFAULT_PORT = 8765
ALLOWED_EXT = {".pdf", ".ofd"}


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
    # GET
    # ------------------------------------------------------------------
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        try:
            qs, path = self._query()
            if path in ("/", "/index.html"):
                return self._serve_index()
            if path.startswith("/static/"):
                name = os.path.basename(path)
                return self._serve_file(os.path.join(WEB_DIR, name))
            if path == "/api/ping":
                return self._json({"ok": True, "app": APP_TAG})
            if path == "/api/list":
                return self._api_list(qs)
            if path == "/api/batches":
                return self._api_batches(qs)
            if path == "/api/batch":
                return self._api_batch(qs)
            if path == "/api/check":
                return self._api_check(qs)
            if path == "/api/file":
                return self._api_file(qs)
            if path == "/api/export":
                return self._api_export()
            if path == "/api/openfolder":
                return self._api_openfolder(qs)
            return self._err("未知地址", 404)
        except Exception:
            traceback.print_exc()
            return self._err("服务端错误", 500)

    def _serve_index(self):
        """
        把 style.css / app.js 的地址带上文件修改时间。
        这样以后升级替换了网页文件，浏览器一定会拿新的，
        不会因为缓存还显示旧样式、旧功能。
        """
        path = os.path.join(WEB_DIR, "index.html")
        if not os.path.isfile(path):
            return self._err("找不到网页文件 index.html", 404)
        with open(path, "rb") as f:
            html = f.read().decode("utf-8")
        for name in ("style.css", "app.js"):
            target = os.path.join(WEB_DIR, name)
            try:
                ver = int(os.path.getmtime(target))
            except OSError:
                ver = 0
            html = html.replace("/static/%s" % name, "/static/%s?v=%d" % (name, ver))
        return self._send(200, html.encode("utf-8"), "text/html; charset=utf-8")

    def _serve_file(self, path):
        if not os.path.isfile(path):
            return self._err("文件不存在", 404)
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        if path.endswith((".html", ".js", ".css")):
            ctype += "; charset=utf-8"
        with open(path, "rb") as f:
            data = f.read()
        return self._send(200, data, ctype)

    def _api_list(self, qs):
        def one(key):
            v = qs.get(key)
            return v[0] if v else None
        records = STORE.list(
            q=one("q"), person=one("person"), month=one("month"),
            status=one("status"), batch_id=one("batch"),
            sort=one("sort") or "invoice_date",
            desc=(one("desc") or "1") != "0",
        )
        return self._json({
            "ok": True,
            "records": records,
            "stats": STORE.stats(),
            "persons": STORE.persons(),
            "months": STORE.months(),
            "batches": [{"id": b["id"], "batch_no": b["batch_no"]}
                        for b in STORE.list_batches(limit=500)],
        })

    def _api_batches(self, qs):
        def one(key):
            v = qs.get(key)
            return v[0] if v else None
        return self._json({"ok": True, "batches": STORE.list_batches(
            q=one("q"), status=one("status"), person=one("person"))})

    def _api_batch(self, qs):
        bid = (qs.get("id") or [""])[0]
        if not bid.isdigit():
            return self._err("缺少报销单编号")
        b = STORE.batch_detail(int(bid))
        if not b:
            return self._err("找不到这张报销单", 404)
        return self._json({"ok": True, "batch": b})

    def _api_check(self, qs):
        no = (qs.get("no") or [""])[0]
        no = "".join(ch for ch in no if ch.isdigit())
        rec = STORE.get_by_no(no) if no else None
        return self._json({"ok": True, "exists": bool(rec), "record": rec})

    def _api_file(self, qs):
        rid = (qs.get("id") or [""])[0]
        rec = STORE.get(int(rid)) if rid.isdigit() else None
        if not rec or not rec.get("file_rel"):
            return self._err("没有对应的发票原件", 404)
        path = os.path.join(BASE, rec["file_rel"])
        if not os.path.isfile(path):
            return self._err("原件已不在原位置", 404)
        ext = os.path.splitext(path)[1].lower()
        ctype = "application/pdf" if ext == ".pdf" else "application/octet-stream"
        with open(path, "rb") as f:
            data = f.read()
        name = urllib.parse.quote(os.path.basename(path))
        return self._send(200, data, ctype, {"Content-Disposition": "inline; filename*=UTF-8''" + name})

    def _api_openfolder(self, qs):
        """在资源管理器里打开 发票存档 / 数据 文件夹。"""
        which = (qs.get("which") or ["archive"])[0]
        target = STORE.archive_dir if which == "archive" else STORE.data_dir
        try:
            os.startfile(target)
        except Exception as exc:
            return self._err("打开文件夹失败：%s" % exc)
        return self._json({"ok": True})

    def _api_export(self):
        import datetime
        name = "发票台账_%s.xlsx" % datetime.date.today().strftime("%Y%m%d")
        tmp = os.path.join(STORE.tmp_dir, name)
        STORE.sync_excel(tmp)
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
            if path == "/api/parse":
                return self._api_parse()
            if path == "/api/add":
                return self._api_add()
            if path == "/api/batch_add":
                return self._api_batch_add()
            if path == "/api/batch_status":
                return self._api_batch_status()
            if path == "/api/batch_update":
                return self._api_batch_update()
            if path == "/api/attach":
                return self._api_attach()
            if path == "/api/detach":
                return self._api_detach()
            if path == "/api/update":
                return self._api_update()
            if path == "/api/delete":
                return self._api_delete()
            if path == "/api/status":
                return self._api_status()
            if path == "/api/openfile":
                return self._api_openfile()
            return self._err("未知地址", 404)
        except Exception:
            traceback.print_exc()
            return self._err("服务端错误", 500)

    def _api_parse(self):
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

    def _api_add(self):
        data = self._json_body()
        rec = self._clean(data)
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
        return self._json({"ok": True, "id": rid, "record": STORE.get(rid)})

    # ---------------- 报销单 ----------------
    def _tmp_of(self, token):
        if not token:
            return None
        cand = os.path.join(STORE.tmp_dir, os.path.basename(token))
        return cand if os.path.isfile(cand) else None

    def _api_batch_add(self):
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
                reimburse_date=(data.get("reimburse_date") or "").strip() or None)
        except store_mod.DuplicateError as dup:
            rec = dup.record or {}
            if rec.get("in_batch"):
                return self._err("本单里有重复的发票号码：%s" % rec.get("invoice_no"),
                                 409, duplicate=True, record=rec)
            return self._err("发票号码 %s 之前已经登记过了" % rec.get("invoice_no", ""),
                             409, duplicate=True, record=rec)
        except Exception as exc:
            return self._err("提交失败：%s" % exc, 500)
        return self._json({"ok": True, "batch_id": bid, "ids": ids,
                           "batch": STORE.batch_detail(bid)})

    def _api_batch_status(self):
        data = self._json_body()
        bid = data.get("id")
        if not bid:
            return self._err("缺少报销单编号")
        try:
            b = STORE.set_batch_status(int(bid), data.get("status"))
        except Exception as exc:
            return self._err("切换失败：%s" % exc, 500)
        return self._json({"ok": True, "batch": b, "stats": STORE.stats()})

    def _api_batch_update(self):
        data = self._json_body()
        bid = data.get("id")
        if not bid:
            return self._err("缺少报销单编号")
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

    def _api_attach(self):
        """把已登记的发票补挂到某张报销单"""
        data = self._json_body()
        ids = data.get("ids") or []
        if not ids:
            return self._err("请先选中要归单的发票")
        try:
            b = STORE.attach_to_batch(
                ids,
                batch_id=data.get("batch_id"),
                batch_no=(data.get("batch_no") or "").strip() or None,
                note=(data.get("note") or "").strip() or None)
        except Exception as exc:
            return self._err("归单失败：%s" % exc, 500)
        return self._json({"ok": True, "batch": b, "stats": STORE.stats()})

    def _api_detach(self):
        data = self._json_body()
        ids = data.get("ids") or []
        if not ids:
            return self._err("请先选中要拆出的发票")
        try:
            STORE.detach(ids)
        except Exception as exc:
            return self._err("拆出失败：%s" % exc, 500)
        return self._json({"ok": True, "stats": STORE.stats()})

    def _api_update(self):
        data = self._json_body()
        rid = data.get("id")
        if not rid:
            return self._err("缺少记录编号")
        rec = self._clean(data, only_present=True)
        try:
            STORE.update(int(rid), rec)
        except store_mod.DuplicateError as dup:
            return self._err("这个发票号码已经被另一条记录用了，不能改成它", 409,
                             duplicate=True, record=dup.record)
        except Exception as exc:
            return self._err("修改失败：%s" % exc, 500)
        return self._json({"ok": True, "record": STORE.get(int(rid))})

    def _api_delete(self):
        data = self._json_body()
        rid = data.get("id")
        if not rid:
            return self._err("缺少记录编号")
        STORE.delete(int(rid))
        return self._json({"ok": True, "stats": STORE.stats()})

    def _api_status(self):
        """切换报销状态：0=未报销，1=已报销"""
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

    def _api_openfile(self):
        data = self._json_body()
        rid = data.get("id")
        rec = STORE.get(int(rid)) if rid else None
        if not rec or not rec.get("file_rel"):
            return self._err("没有对应的发票原件", 404)
        path = os.path.join(BASE, rec["file_rel"])
        if not os.path.isfile(path):
            return self._err("原件已不在原位置", 404)
        try:
            os.startfile(path)
        except Exception as exc:
            return self._err("打开失败：%s" % exc)
        return self._json({"ok": True})


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


def main():
    # 输出到管道/重定向时，用系统默认编码并容错，避免中文报错
    try:
        if not sys.stdout.isatty():
            sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass

    # 如果系统已经在运行，直接把浏览器叫起来，不重复开第二个服务
    if port_in_use(DEFAULT_PORT) and _is_our_server(DEFAULT_PORT):
        url = "http://127.0.0.1:%d/" % DEFAULT_PORT
        print("人数发票登记系统已经在运行，正在为你打开浏览器： %s" % url)
        try:
            sys.stdout.flush()
        except Exception:
            pass
        if "--no-browser" not in sys.argv:
            webbrowser.open(url)
        sys.exit(3)          # 3 = 已经在运行，启动脚本据此给出对应提示

    port = pick_port()
    url = "http://127.0.0.1:%d/" % port

    print("=" * 58)
    print("  人数发票登记台账  正在运行")
    print("  请使用浏览器访问： %s" % url)
    print("  数据目录： %s" % STORE.data_dir)
    print("  发票存档： %s" % STORE.archive_dir)
    print("-" * 58)
    print("  关闭本窗口即退出系统（数据已实时保存，不会丢失）")
    print("=" * 58)
    try:
        sys.stdout.flush()
    except Exception:
        pass

    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    if "--no-browser" not in sys.argv:
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
