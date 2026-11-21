# -*- coding: utf-8 -*-
"""
查验小服务（Python 侧）
=======================
Playwright 开浏览器 + 验证码 OCR 这套逻辑都在 程序/audit.py 里，
保持一个 HTTP 边界，由 Spring Boot 调它。

启动：
    python 工具/查验服务.py --port 8798
    python 工具/查验服务.py --port 8798 --base /opt/02-fapiaodengji

--base 是项目根目录（数据/、发票存档/ 所在那层）。
默认取本文件的上两级，一般不用传；只有测试或多副本时才需要。

接口：
    GET  /_health                → "ok"
    GET  /audit/status           → {"ok":true,"state":{...},"config":{...}}
    POST /audit/start  {"ids"}   → {"ok":true,"count":n,"state":{...}}
    POST /audit/stop             → {"ok":true,"state":{...}}
"""
import argparse
import json
import os
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(DEFAULT_BASE, "程序"))
try:
    sys.path.insert(0, os.path.join(DEFAULT_BASE, "运行库"))
except Exception:
    pass

import audit as audit_mod   # noqa: E402
import store as store_mod   # noqa: E402

STORE = None
RUNNER = None


class Handler(BaseHTTPRequestHandler):
    server_version = "invoice-audit/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[查验] %s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _text(self, code, text):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/_health":
            return self._text(200, "ok")
        if path == "/audit/status":
            model_ok = False
            try:
                model_ok = bool(audit_mod.model_ready())
            except Exception:
                pass
            return self._json(200, {
                "ok": True,
                "state": RUNNER.snapshot(),
                "config": {
                    "page_version": audit_mod.CONFIG["page_version"],
                    "headless": audit_mod.CONFIG["headless"],
                    "model_ready": model_ok,
                    "base": audit_mod.BASE,
                },
            })
        return self._json(404, {"ok": False, "error": "未知地址"})

    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            length = int(self.headers.get("Content-Length") or 0)
            data = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
        except Exception as exc:
            return self._json(400, {"ok": False, "error": "请求格式不对：%s" % exc})

        if path == "/audit/stop":
            RUNNER.stop()
            return self._json(200, {"ok": True, "state": RUNNER.snapshot()})

        if path != "/audit/start":
            return self._json(404, {"ok": False, "error": "未知地址"})

        ids = []
        for x in (data.get("ids") or []):
            try:
                ids.append(int(x))
            except (TypeError, ValueError):
                continue
        if not ids:
            return self._json(400, {"ok": False, "error": "没有选中要查验的发票"})

        try:
            ok, msg = RUNNER.start(ids)
        except Exception as exc:
            traceback.print_exc()
            return self._json(500, {"ok": False, "error": "启动失败：%s" % str(exc)[:200]})
        if not ok:
            return self._json(409, {"ok": False, "error": msg})
        return self._json(200, {"ok": True, "count": len(ids), "state": RUNNER.snapshot()})


def main():
    ap = argparse.ArgumentParser(description="发票查验小服务")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8798)
    ap.add_argument("--base", default=DEFAULT_BASE,
                    help="项目根目录（数据/ 所在那层），默认本文件的上两级")
    args = ap.parse_args()

    global STORE, RUNNER
    root = os.path.abspath(args.base)
    # audit.py 里截图、存档路径都是相对 BASE 算的，必须一起指过去
    audit_mod.BASE = root
    STORE = store_mod.Store(root)
    RUNNER = audit_mod.Runner(STORE)

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    print("查验服务已启动： http://%s:%d/  (项目目录 %s)"
          % (args.host, args.port, root), flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止", flush=True)


if __name__ == "__main__":
    main()
