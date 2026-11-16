# -*- coding: utf-8 -*-
"""
人数发票登记台账 —— 自检
放在项目文件夹里（和 启动.bat 同一层），双击 自检.bat 运行。
会把结果写到同目录的「自检报告.txt」，把这个文件发回给管理员即可。
"""
import datetime
import glob
import os
import sqlite3
import sys
import traceback

BASE = os.path.dirname(os.path.abspath(__file__))
RPT = os.path.join(BASE, "自检报告.txt")
L = []


def w(s=""):
    L.append(str(s))


def path(*parts):
    return os.path.join(BASE, *parts)


def dir_stat(p):
    n, sz = 0, 0
    for r, _, fs in os.walk(p):
        for f in fs:
            try:
                sz += os.path.getsize(os.path.join(r, f))
                n += 1
            except OSError:
                pass
    return n, sz


w("人数发票登记台账  自检报告")
w("生成时间：" + datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
w("项目目录：" + BASE)
w("Python 版本：" + sys.version.replace("\n", " "))
w("Python 路径：" + sys.executable)
w("=" * 66)

w("")
w("【1】目录检查")
for d in ("程序", "网页", "运行库", "数据", "发票存档"):
    p = path(d)
    if os.path.isdir(p):
        n, sz = dir_stat(p)
        w("  %-8s 存在   %5d 个文件   %8.1f MB" % (d, n, sz / 1048576.0))
    else:
        w("  %-8s 【缺失！】" % d)

w("")
w("【2】运行库关键文件（自动识别 PDF 就靠它们）")
KEY = [
    "运行库/pymupdf/__init__.py",
    "运行库/pymupdf/_mupdf.pyd",
    "运行库/pymupdf/_extra.pyd",
    "运行库/pymupdf/mupdfcpp64.dll",
    "运行库/PIL/__init__.py",
    "运行库/openpyxl/__init__.py",
    "运行库/zxingcpp/__init__.py",
]
missing = []
for k in KEY:
    p = path(*k.split("/"))
    if os.path.isfile(p):
        w("  %-34s 存在  %8.2f MB" % (k, os.path.getsize(p) / 1048576.0))
    else:
        w("  %-34s 【缺失！】" % k)
        missing.append(k)

w("")
w("【3】运行库 顶层内容")
rl = path("运行库")
if os.path.isdir(rl):
    for n in sorted(os.listdir(rl)):
        w("  " + n)
else:
    w("  （运行库 文件夹不存在）")

w("")
w("【4】模块导入测试")
sys.path.insert(0, rl)
for mod in ("pymupdf", "fitz", "PIL", "PIL.Image", "openpyxl", "zxingcpp"):
    try:
        __import__(mod)
        w("  %-12s 成功" % mod)
    except Exception as e:
        w("  %-12s 【失败】 %s: %s" % (mod, type(e).__name__, e))

w("")
w("【5】pymupdf 详细错误（诊断 DLL 问题用）")
try:
    import pymupdf
    w("  导入正常，版本信息：")
    w("  " + str(getattr(pymupdf, "__doc__", ""))[:200].replace("\n", " "))
except Exception:
    w(traceback.format_exc())

w("")
w("【6】PDF 解析实测")
try:
    import pymupdf
    arch = path("发票存档")
    pdfs = glob.glob(os.path.join(arch, "**", "*.pdf"), recursive=True)[:3]
    if not pdfs:
        w("  发票存档里没有 PDF 可测")
    for p in pdfs:
        try:
            d = pymupdf.open(p)
            t = d[0].get_text("text")
            d.close()
            w("  %-40s 读出 %d 个字符" % (os.path.basename(p)[:40], len(t)))
        except Exception as e:
            w("  %-40s 【失败】%r" % (os.path.basename(p)[:40], e))
except Exception as e:
    w("  无法测试（pymupdf 不可用）：%r" % (e,))

w("")
w("【7】数据检查")
db = path("数据", "发票台账.db")
if os.path.isfile(db):
    w("  数据库：%d 字节" % os.path.getsize(db))
    try:
        c = sqlite3.connect(db)
        w("  发票记录数：" + str(c.execute("SELECT COUNT(*) FROM invoices").fetchone()[0]))
        tabs = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        w("  数据表：" + ", ".join(tabs))
        try:
            w("  报销单数：" + str(c.execute("SELECT COUNT(*) FROM batches").fetchone()[0]))
        except Exception:
            w("  （没有 batches 表，说明数据库还是旧结构）")
        c.close()
    except Exception as e:
        w("  读取失败：" + str(e))
else:
    w("  【缺失】数据\\发票台账.db 不存在")
arch = path("发票存档")
if os.path.isdir(arch):
    n, sz = dir_stat(arch)
    w("  发票存档：%d 个原件，%.1f MB" % (n, sz / 1048576.0))
else:
    w("  【缺失】发票存档 文件夹不存在")

w("")
w("【8】程序 / 网页文件")
for k in ("程序/server.py", "程序/store.py", "程序/reader.py",
          "网页/index.html", "网页/app.js", "网页/style.css", "启动.bat"):
    p = path(*k.split("/"))
    w("  %-22s %s" % (k, ("%d 字节" % os.path.getsize(p)) if os.path.isfile(p) else "【缺失！】"))

w("")
w("【9】系统 Python 里有没有 pymupdf（备用方案用）")
try:
    import importlib.util
    for mod in ("pymupdf", "fitz"):
        spec = None
        for p in sys.path:
            if p == rl:
                continue
            cand = os.path.join(p, mod)
            if os.path.isdir(cand):
                spec = cand
                break
        w("  %-10s %s" % (mod, spec or "没找到"))
except Exception as e:
    w("  检查失败：%r" % (e,))

w("")
w("【10】结论")
if missing:
    w("  运行库不完整，缺少：")
    for m in missing:
        w("    " + m)
    w("  → 解决办法：把完整的「运行库」文件夹放回项目目录（覆盖原有的）")
else:
    w("  运行库关键文件都在。如果还是读不出号码，请把本报告发回给管理员。")

w("")
w("=" * 66)
w("报告结束。请把「自检报告.txt」发给管理员。")

try:
    with open(RPT, "w", encoding="utf-8-sig", newline="") as f:
        f.write("\r\n".join(L))
    print(RPT)
except Exception as e:
    print("写报告失败：%r" % (e,))
