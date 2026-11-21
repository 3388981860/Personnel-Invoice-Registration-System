# -*- coding: utf-8 -*-
"""
解析小服务（Python 侧）
=======================
PDF / OFD 的识别逻辑（PyMuPDF、二维码、中文金额解析）都在 程序/reader.py 里，
重写一遍到 Java 风险太大，所以留成一个小服务，由 Spring Boot 通过 HTTP 调用。

启动：
    python 工具/解析服务.py --port 8799

接口：
    GET  /_health          → "ok"
    POST /parse            {"path": "<绝对路径>", "name": "<原始文件名>"}
                           → {"ok": true, "fields": {...}}
                           → {"ok": false, "error": "..."}
"""
import argparse
import json
import os
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE, "程序"))
try:
    sys.path.insert(0, os.path.join(BASE, "运行库"))
except Exception:
    pass

import reader  # noqa: E402

MAX_BODY = 64 * 1024 * 1024      # 单次请求最大 64MB


class Handler(BaseHTTPRequestHandler):
    server_version = "invoice-parse/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # 只打一行，别把 stdout 刷满
        sys.stderr.write("[解析] %s - %s\n" % (self.address_string(), fmt % args))

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
        if self.path.startswith("/_health"):
            return self._text(200, "ok")
        return self._json(404, {"ok": False, "error": "未知地址"})

    def do_POST(self):
        if not self.path.startswith("/parse"):
            return self._json(404, {"ok": False, "error": "未知地址"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                return self._json(400, {"ok": False, "error": "请求内容为空或过大"})
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception as exc:
            return self._json(400, {"ok": False, "error": "请求格式不对：%s" % exc})

        path = str(data.get("path") or "")
        if not path or not os.path.isfile(path):
            return self._json(400, {"ok": False, "error": "文件不存在：%s" % path})

        try:
            fields = reader.extract(path)
            if not isinstance(fields, dict):
                fields = {}
            return self._json(200, {"ok": True, "fields": fields})
        except Exception as exc:
            traceback.print_exc()
            return self._json(500, {"ok": False, "error": "识别失败：%s" % str(exc)[:200]})


def main():
    ap = argparse.ArgumentParser(description="发票解析小服务")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8799)
    args = ap.parse_args()

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    print("解析服务已启动： http://%s:%d/  (Ctrl+C 停止)" % (args.host, args.port),
          flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止", flush=True)


if __name__ == "__main__":
    main()
