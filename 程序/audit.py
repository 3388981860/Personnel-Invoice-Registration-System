# -*- coding: utf-8 -*-
"""
发票查验
--------
把台账里的发票逐张送到「国家税务总局全国增值税发票查验平台」核验，
并把结果和结果截图写回台账。

设计要点：
    1. 纯逻辑，不依赖 tkinter；由 server.py 的后台线程调用
    2. 截图用 Playwright 元素截图，不再用 ImageGrab 抓屏，
       因此与显示器分辨率、DPI 缩放、浏览器窗口位置无关
    3. 查验结果落库（audit_status / audit_at / audit_msg / audit_shot），
       前端可以查、可以看截图
    4. 同一时间只跑一个任务，避免把平台的识别次数配额一次烧光
"""
import base64
import json
import os
import re
import threading
import time
import traceback
import uuid
from datetime import datetime
from io import BytesIO

import reader

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB_DIR = os.path.join(BASE, "运行库")
AUDIT_DIR = os.path.dirname(os.path.abspath(__file__))          # 程序
CAPTCHA_DIR = os.path.join(AUDIT_DIR, "审核")                    # 程序\审核
MODEL_DIR = os.path.join(CAPTCHA_DIR, "模型")                    # 程序\审核\模型
SHOT_DIR = os.path.join(BASE, "数据", "查验截图")
TEMP_DIR = os.path.join(BASE, "数据", "临时")


def _browser_dir_ready(path):
    """
    只有目录里真的放了 chromium-* 才算"自带浏览器"。
    空目录也会被 Playwright 当成浏览器根目录，结果反而找不到浏览器，
    所以这里必须看有没有 chromium-* 子目录，不能只看目录在不在。
    """
    try:
        return any(name.startswith("chromium-") and
                   os.path.isdir(os.path.join(path, name))
                   for name in os.listdir(path))
    except OSError:
        return False


# 自带浏览器优先（换电脑拷走就能用）；没放就交给 Playwright 用系统默认位置
_BROWSERS = os.path.join(LIB_DIR, "playwright_browsers")
if _browser_dir_ready(_BROWSERS):
    os.environ["PLAYWRIGHT_BROWSERS_PATH"] = _BROWSERS

# 查验状态
AUDIT_NONE = 0        # 未查验（含查验失败，可重试）
AUDIT_PASS = 1        # 查验通过
AUDIT_FAIL = 2        # 查验不通过（平台查无此票 / 信息不一致）
AUDIT_SKIP = 3        # 不查验（超过 5 年等）

CONFIG = {
    # 2026-11 实测：平台首页已整体改版为 TDesign 单页应用
    # （两个入口都会跳到 /fpcygzfw/national-invoice-check），
    # 旧版那套 #fpdm / #fphm 选择器已经全部匹配不到，必须用 new。
    "page_version": "new",     # new=新版页面（当前有效）  old=旧版页面（已失效，留档）
    # 默认无头：查验过程不弹浏览器窗口，用户只看右下角进度。
    # 调试时想看见页面，把它改成 False（或改 数据\查验配置.json 里的 headless）。
    "headless": True,
    "window_position": "-10,0",
    "viewport_width": 1400,
    "viewport_height": 900,
    "interval": 3,             # 每张发票之间的间隔（秒）
    "retries": 2,              # 单张发票整体重试次数
    "captcha_tries": 6,        # 连续识别不出验证码时的尝试次数
    "captcha_rounds": 4,       # 验证码被平台判错后，最多重来几轮
    # 超过这个秒数还打不开查验平台，就当网络不通，直接给结论，别一直挂着
    "page_timeout": 25,
    # 单张发票的总预算（秒）。超了就放弃这张，避免"点了一下半天没反应"
    "invoice_timeout": 180,
    "browser_path": "",        # 指定 chrome.exe 全路径；留空=用 Playwright 自己找
    # 无头模式下必须伪装成普通 Chrome 的 UA：查验网关会直接拒掉 HeadlessChrome，
    # 表现是验证码接口 net::ERR_FAILED。留空 = 按实际浏览器主版本自动生成。
    "user_agent": "",
    # ---- 业务审核规则（P3）----
    "company_names": [],       # 抬头白名单，如 ["浙江信宇科技有限公司"]；空=不检查
    "warn_amount": 0,          # 单张金额 ≥ 这个数就提示（0=不检查）
    "warn_cross_month": False, # 跨月报销提示。默认关闭：公司习惯次月报，开着太吵
    "backoff": [60, 180, 300], # 被平台限流后的等待秒数（依次递增）
}

SELECTORS = {
    "old": {
        "url": "https://inv-veri.chinatax.gov.cn/index.html",
        "fpdm": "#fpdm",
        "fphm": "#fphm",
        "kprq": "#kprq",
        "amount": "#kjje",
        "yzm": "#yzm",
        "yzm_img": "#yzm_img",
        "submit": "#checkfp",
        "popup": "#popup_ok",
        "captcha_resp": "yzmQuery",
        "query_resp": "vatQuery",
        "json_mode": "jsonp",
        "date_picker": False,
        "result_frame": "#dialog-body",
        "result_content": "#content",
    },
    "new": {
        # 2026-11-16 实测：两个入口都 302 到 /fpcygzfw/national-invoice-check
        "url": "https://inv-veri.chinatax.gov.cn/index.html",
        "fpdm": ".t-form-item__fpdm input",
        "fphm": ".t-form-item__fphm input",
        "kprq": ".t-form-item__kprq input",
        "amount": ".t-form-item__kpje input",
        "yzm": ".t-form-item__yzm input",
        "yzm_img": ".t-form-item__ img",
        "submit": "button[type='submit']",
        # 提示弹窗（TDesign）与它的"确定"按钮
        "popup": ".popup-content",
        "popup_ok": ".t-dialog__ctx button",
        "captcha_resp": "getVerifyCode",
        "query_resp": "queryFpcyxx",
        "json_mode": "json",
        "date_picker": False,      # 新版可以直接输入 YYYYMMDD
        "result": ".invoice-dialog",   # 查验结果弹窗
    },
}


# ----------------------------------------------------------------------
# 小工具
# ----------------------------------------------------------------------
def _digits(s):
    return re.sub(r"\D", "", str(s or ""))


def _compact_date(s):
    d = _digits(s)
    return d[:8] if len(d) >= 8 else ""


def _load_config():
    path = os.path.join(BASE, "数据", "查验配置.json")
    if not os.path.isfile(path):
        return
    try:
        # utf-8-sig：记事本另存为 UTF-8 会带 BOM，用 utf-8 读会直接解析失败
        with open(path, "r", encoding="utf-8-sig") as f:
            data = json.load(f)
        for k in CONFIG:
            if k in data:
                CONFIG[k] = data[k]
    except Exception:
        traceback.print_exc()


_load_config()


def preset_skip(record):
    """
    登记时就能确定的"平台不给查"的情况，直接标成无需查验，
    免得它排在待查列表里、每次都要白跑一遍才知道。
    返回 (status, msg) 或 None（None 表示需要查验）。
    """
    no = _digits(record.get("invoice_no"))
    if len(no) not in (20, 8):
        return AUDIT_SKIP, "发票号码是 %d 位，平台只接受 20 位（数电票）或 8 位（老票）" % len(no)

    kprq = _compact_date(record.get("invoice_date"))
    if not kprq:
        return None
    try:
        d = datetime.strptime(kprq, "%Y%m%d")
        now = datetime.now()
        try:
            five = now.replace(year=now.year - 5)
        except ValueError:
            five = now.replace(year=now.year - 5, day=28)
        if d <= five:
            return AUDIT_SKIP, "开票时间超过 5 年，平台不提供查验"
    except ValueError:
        pass
    return None


def model_dir(version=None):
    """
    返回某个页面版本的验证码模型目录。
    该版本没放模型时退回 old，避免"选了新版但只放了一套模型"直接哑火。
    """
    version = version or CONFIG["page_version"]
    for name in (version, "old"):
        d = os.path.join(MODEL_DIR, name)
        if os.path.isfile(os.path.join(d, "inference.onnx")):
            return d
    return os.path.join(MODEL_DIR, version)


def model_ready(version=None):
    return os.path.isfile(os.path.join(model_dir(version), "inference.onnx"))


# 当前任务使用的 User-Agent（每次启动浏览器时确定，_one 建页面时套用）
_UA = {"value": ""}


def build_user_agent(browser_version):
    """
    生成一个"普通 Chrome"的 UA。

    为什么必须这么做：无头浏览器默认 UA 里带 HeadlessChrome，
    查验平台的网关会直接把跨域的验证码接口掐掉（net::ERR_FAILED），
    表现就是"一直是未查验"。带上普通 UA 就正常了。
    """
    if CONFIG.get("user_agent"):
        return CONFIG["user_agent"]
    major = str(browser_version or "120").split(".")[0]
    return ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36" % major)


def new_page(browser):
    """统一的建页方式：套用伪装 UA 和视口，避免各处分叉。"""
    kw = {"viewport": {"width": CONFIG["viewport_width"],
                       "height": CONFIG["viewport_height"]}}
    if _UA["value"]:
        kw["user_agent"] = _UA["value"]
    return browser.new_page(**kw)


def shot_rel_path(invoice_no, when=None):
    """截图相对路径：数据/查验截图/年/月/发票号码.png（统一用正斜杠，跨平台都能用）"""
    when = when or datetime.now()
    return "/".join(["数据", "查验截图", when.strftime("%Y"), when.strftime("%m"),
                     "%s.png" % _digits(invoice_no)])


def abs_rel(rel):
    """把库里存的相对路径（可能带 Windows 反斜杠）转成本机绝对路径。"""
    if not rel:
        return None
    parts = [p for p in str(rel).replace("\\", "/").split("/")
             if p not in ("", ".", "..")]
    return os.path.join(BASE, *parts) if parts else BASE


# ----------------------------------------------------------------------
# 取查验用的参数
# ----------------------------------------------------------------------
def _ocr_image(img_path):
    """图片型发票：OCR 兜底取 发票代码 / 校验码 / 金额。"""
    from 审核.extractor import InvoiceExtractor
    ex = InvoiceExtractor()
    f = ex.extract_fields(img_path) or {}
    return {
        "invoice_code": f.get("fpdm"),
        "check_code": f.get("jym"),
        "amount": f.get("kpje"),
        "amount_other": f.get("qtje") or [],
    }


def _ocr_pdf(pdf_path):
    """没有文字层的 PDF：把首页渲染成图片再 OCR。"""
    fitz = reader.import_fitz()
    os.makedirs(TEMP_DIR, exist_ok=True)
    tmp = os.path.join(TEMP_DIR, "audit_%s.png" % uuid.uuid4().hex)
    doc = fitz.open(pdf_path)
    try:
        pix = doc[0].get_pixmap(dpi=220)
        pix.save(tmp)
    finally:
        doc.close()
    try:
        return _ocr_image(tmp)
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass


def _source_fields(record):
    """从归档原件里补台账没有的字段（发票代码、校验码、金额）。"""
    out = {}
    rel = record.get("file_rel")
    if not rel:
        return out
    path = abs_rel(rel)
    if not path or not os.path.isfile(path):
        return out
    ext = os.path.splitext(path)[1].lower()
    try:
        if ext in (".pdf", ".ofd"):
            f = reader.extract(path)
            out["invoice_code"] = f.get("invoice_code")
            out["check_code"] = f.get("check_code")
            out["amount"] = f.get("amount")
            if ext == ".pdf" and not f.get("text_layer"):
                out.update(_ocr_pdf(path))
        else:
            out.update(_ocr_image(path))
    except Exception:
        traceback.print_exc()
    return out


def _money_str(v):
    """金额统一成两位小数：344.0 → 344.00，避免平台按不同格式校验。"""
    try:
        return "%.2f" % float(v)
    except (TypeError, ValueError):
        return str(v)


def build_query(record, src=None):
    """
    由台账记录生成平台查询参数。
    返回 (query, msg, skip)：
        query 为 dict 表示可以查验；为 None 时 msg 说明原因；
        skip=True 表示这类票平台本来就不给查（比如超过 5 年）。

    金额口径（2026-11 实测）：
        20 位数电票 → 平台要"价税合计"      → 用台账 amount
        12 位代码老票 → 平台要校验码后 6 位  → 用原件里的 check_code
        10 位代码老票 → 平台要"开具金额(不含税)" → 用原件 OCR 到的金额
    """
    no = _digits(record.get("invoice_no"))
    if len(no) not in (20, 8):
        return None, "发票号码是 %d 位，平台只接受 20 位（数电票）或 8 位（老票）" % len(no), True

    kprq = _compact_date(record.get("invoice_date"))
    if not kprq:
        return None, "缺少开票日期，无法查验", False

    # 超过 5 年平台不提供查验
    try:
        d = datetime.strptime(kprq, "%Y%m%d")
        now = datetime.now()
        try:
            five = now.replace(year=now.year - 5)
        except ValueError:
            five = now.replace(year=now.year - 5, day=28)
        if d <= five:
            return None, "开票时间超过 5 年，平台不提供查验", True
    except ValueError:
        return None, "开票日期格式不对，无法查验", False

    src = src if src is not None else _source_fields(record)
    fpdm = _digits(src.get("invoice_code"))
    check = _digits(src.get("check_code"))
    amount = record.get("amount")
    if amount in (None, ""):
        amount = src.get("amount")

    q = {"fphm": no, "kprq": kprq, "fpdm": "", "amount": "", "role": ""}

    if len(no) == 20:
        # 数电票：只填号码 + 日期 + 价税合计
        if amount in (None, ""):
            return None, "缺少价税合计金额，无法查验", False
        q["amount"] = _money_str(amount)
        q["role"] = "total"
        return q, "", False

    # 8 位号码的老票：必须带发票代码
    if len(fpdm) == 12:
        if len(check) < 6:
            return None, "这张老票要校验码后 6 位，但原件里没读到校验码", False
        q["fpdm"] = fpdm
        q["amount"] = check[-6:]
        q["role"] = "check"
    elif len(fpdm) == 10:
        other = src.get("amount_other") or []
        q["fpdm"] = fpdm
        q["amount"] = _money_str(other[0]) if other else _money_str(amount or "")
        q["role"] = "other"
        if not q["amount"]:
            return None, "缺少开具金额，无法查验", False
    else:
        return None, "8 位号码的老票必须同时有 10/12 位发票代码，原件里没读到", True
    return q, "", False


# ----------------------------------------------------------------------
# 验证码
# ----------------------------------------------------------------------
def _parse_body(text):
    """平台的验证码接口旧版返回 jsonp，新版返回 json。"""
    text = (text or "").strip()
    try:
        return json.loads(text)
    except ValueError:
        pass
    try:
        return json.loads(text[text.index("(") + 1:text.rindex(")")])
    except Exception:
        return {}


def _base64_to_image(b64, path):
    data = base64.b64decode(b64)
    from PIL import Image
    Image.open(BytesIO(data)).save(path)


def _solve_captcha(page, sel, recognizer, color_parser):
    """
    抓一张验证码并识别，返回识别文本（识别不可靠时返回空串）。

    2026-11 实测：新版平台点验证码图片会 POST getVerifyCode（跨域，省级节点），
    返回 JSON 里的 key3 就是颜色码（00黑 01红 02黄 03蓝），key1 是图片 base64。
    """
    # 有校验弹窗（比如"请输入发票号码!"）时先关掉，否则点不到图片
    _dismiss_popup(page, sel)

    try:
        with page.expect_response(lambda r: sel["captcha_resp"] in r.url,
                                  timeout=20000) as info:
            page.locator(sel["yzm_img"]).first.click(timeout=6000)
    except Exception:
        return ""
    data = _parse_body(info.value.text())

    if sel["json_mode"] == "jsonp":
        img_b64, color = data.get("key1"), data.get("key4")
    else:
        img_b64, color = data.get("key1"), data.get("key3")
    if not img_b64:
        return ""

    os.makedirs(TEMP_DIR, exist_ok=True)
    stamp = uuid.uuid4().hex
    raw = os.path.join(TEMP_DIR, "%s.png" % stamp)
    filtered = os.path.join(TEMP_DIR, "%s.filter.png" % stamp)
    try:
        _base64_to_image(img_b64, raw)
        from PIL import Image
        img = Image.open(raw)
        bg = color_parser.get_most_common_color(img)
        if bg:
            img = color_parser.remove_background_color(img, bg)
        color_parser.filter_by_color(img, str(color or "00")).save(filtered)
        result = json.loads(recognizer.infer_json(filtered))
        if result.get("scores", 0) < 0.99:
            return ""
        return result.get("txts", "")
    except Exception:
        traceback.print_exc()
        return ""
    finally:
        for p in (raw, filtered):
            try:
                os.remove(p)
            except OSError:
                pass


def _select_date(page, date_str):
    """旧版新版页面曾经用过日期选择面板；现在可直接输入，此函数留作兜底。"""
    date_input = page.locator(".t-form-item__kprq input")
    date_input.click()
    panel = page.locator(".t-date-picker__panel-container")
    panel.wait_for(state="visible", timeout=10000)

    year, month, day = date_str[:4], date_str[4:6], date_str[6:8]

    year_selector = panel.locator(".t-date-picker__header-controller-year .t-input__inner")
    if year_selector.input_value() != year:
        year_selector.click()
        panel.locator(".t-date-picker__header-controller-year-popup").wait_for(
            state="visible", timeout=5000)
        panel.locator('.t-select__list .t-select-option:has-text("%s")' % year).first.click()

    target_month = "%d 月" % int(month)
    month_selector = panel.locator(".t-date-picker__header-controller-month .t-input__inner")
    if month_selector.input_value() != target_month:
        month_selector.click()
        panel.locator(".t-date-picker__header-controller-month-popup").wait_for(
            state="visible", timeout=5000)
        panel.locator('.t-select__list .t-select-option:has-text("%s")' % target_month).first.click()

    cell = (panel.locator(".t-date-picker__cell:not(.t-date-picker__cell--disabled)"
                          ":not(.t-date-picker__cell--additional)")
            .get_by_text(str(int(day)), exact=True))
    cell.wait_for(state="visible", timeout=5000)
    cell.first.click()


# ----------------------------------------------------------------------
# 平台弹窗（校验提示 / 查验结果）
# ----------------------------------------------------------------------
def _popup_text(page, sel):
    """读平台"提示"弹窗的文案；没有就返回空串。"""
    try:
        loc = page.locator(sel.get("popup") or ".popup-content")
        if loc.count() and loc.first.is_visible():
            return (loc.first.inner_text(timeout=3000) or "").strip()
    except Exception:
        pass
    return ""


def _dismiss_popup(page, sel):
    """关掉校验提示弹窗。查验结果弹窗不要动（要留着截图）。"""
    try:
        if page.locator(sel.get("result") or ".invoice-dialog").count():
            return False
        if not _popup_text(page, sel):
            return False
        page.locator(sel.get("popup_ok") or ".t-dialog__ctx button").first.click(timeout=3000)
        time.sleep(0.4)
        return True
    except Exception:
        return False


def _result_ready(page, sel):
    try:
        loc = page.locator(sel.get("result") or ".invoice-dialog")
        return loc.count() > 0 and loc.first.is_visible()
    except Exception:
        return False


def _result_text(page, sel):
    """查验结果弹窗里的完整文字。"""
    try:
        return page.locator(sel.get("result") or ".invoice-dialog").first.inner_text(timeout=8000)
    except Exception:
        return ""


# ----------------------------------------------------------------------
# 截图
# ----------------------------------------------------------------------
def _img_ok(path):
    if not os.path.isfile(path) or os.path.getsize(path) < 3000:
        return False
    try:
        from PIL import Image
        with Image.open(path) as im:
            return im.width > 200 and im.height > 100
    except Exception:
        return False


def _capture(page, sel, out_path):
    """优先截查验结果弹窗，失败再退回整页截图。"""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    # 右下角"提示"浮层会挡住发票备注栏，截图前先藏起来（只影响这一张截图）
    try:
        page.evaluate("""() => {
            document.querySelectorAll('.com-bottom-tips').forEach(el => {
                el.style.display = 'none';
            });
        }""")
    except Exception:
        pass
    candidates = []
    for css in (sel.get("result") or ".invoice-dialog",
                ".invoice-dialog",
                ".t-dialog__body",
                "[class*='dialog'] [class*='content']"):
        try:
            candidates.append(page.locator(css).first)
        except Exception:
            pass

    for loc in candidates:
        try:
            if loc.count() == 0 or not loc.is_visible():
                continue
            loc.screenshot(path=out_path, timeout=20000)
            if _img_ok(out_path):
                return True
        except Exception:
            continue
    try:
        page.screenshot(path=out_path, full_page=True)
        return _img_ok(out_path)
    except Exception:
        return False


# CyjgDm = 查验结果代码。已实测：7=信息一致，97=验证码错误。
CYJG_PASS = {"7"}
CYJG_CAPTCHA_BAD = {"97"}
CYJG_FAIL = {"3", "4", "5", "6"}     # 不一致 / 查无此票 等（按文案兜底）
FAIL_WORDS = ("不一致", "查无此票", "不存在", "作废", "失败", "不符")


# ----------------------------------------------------------------------
# 单张发票查验
# ----------------------------------------------------------------------
def _fill_form(page, query, sel):
    """填表。返回 (ok, msg)。平台会对空字段弹"请输入发票号码!"之类提示。"""
    try:
        if query.get("fpdm"):
            page.locator(sel["fpdm"]).fill(query["fpdm"], timeout=15000)
        else:
            # 数电票没有发票代码，先把这一格清空，避免上一张的残留
            try:
                if page.locator(sel["fpdm"]).count():
                    page.locator(sel["fpdm"]).fill("", timeout=5000)
            except Exception:
                pass
        page.locator(sel["fphm"]).fill(query["fphm"], timeout=15000)
        page.locator(sel["kprq"]).fill(query["kprq"], timeout=15000)
        page.locator(sel["amount"]).fill(str(query["amount"]), timeout=15000)
        # 让页面完成校验（TDesign 会在 blur 后校验日期/金额格式）
        try:
            page.locator(sel["yzm"]).click(timeout=3000)
        except Exception:
            pass
        time.sleep(0.4)
    except Exception as exc:
        return False, "填表失败：%s" % str(exc)[:120]

    msg = _popup_text(page, sel)
    if msg:
        _dismiss_popup(page, sel)
        return False, "平台提示：%s" % msg
    return True, ""


def _submit(page, sel):
    """点查验并等查询响应。返回 (data, error)。"""
    btn = page.locator(sel["submit"]).first
    # 必填项没齐时按钮是 disabled，直接点会一直卡到超时
    for _ in range(30):
        try:
            if btn.is_enabled():
                break
        except Exception:
            pass
        time.sleep(0.3)
    else:
        msg = _popup_text(page, sel)
        _dismiss_popup(page, sel)
        return None, msg or "查验按钮一直不可点（必填项可能没填齐）"

    try:
        with page.expect_response(lambda r: sel["query_resp"] in r.url,
                                  timeout=60000) as info:
            btn.click(timeout=15000)
    except Exception as exc:
        msg = _popup_text(page, sel)
        _dismiss_popup(page, sel)
        return None, msg or ("没等到查询响应：%s" % str(exc)[:100])
    return _parse_body(info.value.text()), ""


def _judge(data):
    """
    按平台返回判定。
    返回 (status, msg)。
    """
    resp = data.get("Response") if isinstance(data.get("Response"), dict) else {}
    d = resp.get("Data") if isinstance(resp.get("Data"), dict) else {}
    code = str(d.get("CyjgDm") or "")
    msg = str(d.get("CyjgMsg") or "").strip()

    if code in CYJG_CAPTCHA_BAD or "验证码" in msg:
        return None, msg or "验证码错误"
    if code in CYJG_PASS or ("一致" in msg and "不一致" not in msg):
        return AUDIT_PASS, msg or "查验通过"
    if code in CYJG_FAIL or any(w in msg for w in FAIL_WORDS):
        return AUDIT_FAIL, msg or "查验不通过"
    if not code and not msg:
        return AUDIT_NONE, "平台没有返回查验结果"
    return AUDIT_NONE, "未识别的查验结果：%s" % (msg or code)


class _Abort(Exception):
    """用户点了停止，或者这张发票超时了 —— 用来从半路跳出来"""


def _verify_once(page, query, sel, recognizer, color_parser, log=None, abort=None):
    """返回 (status, msg, result_text, data)

    log    : 每走一步写一行进度，界面上能看见"卡在哪一步"
    abort  : 返回 None 表示继续，返回 'stop' / 'timeout' 表示立刻放弃
    """
    say = log or (lambda _t: None)
    page_ms = int(CONFIG.get("page_timeout", 25)) * 1000

    def check_abort():
        """停止/超时就抛出去，别把"页面被关掉"当成网络故障"""
        if abort:
            why = abort()
            if why:
                raise _Abort(why)

    def step(text):
        check_abort()
        say(text)

    step("打开查验页面…")
    try:
        page.goto(sel["url"], wait_until="domcontentloaded", timeout=page_ms)
    except Exception as exc:
        check_abort()
        name = type(exc).__name__
        head = str(exc).strip().splitlines()[0] if str(exc).strip() else name
        if "Timeout" in name or "Timeout" in head[:120]:
            return (AUDIT_NONE,
                    "查验平台打不开（等了 %d 秒没响应）——多半是这台电脑连不上外网，"
                    "或者平台正在维护" % (page_ms // 1000), "", None)
        net = ("ERR_CONNECTION", "ERR_NAME_NOT_RESOLVED", "ERR_INTERNET_DISCONNECTED",
               "ERR_TIMED_OUT", "ERR_PROXY", "ERR_EMPTY_RESPONSE", "ERR_ADDRESS")
        if any(k in head for k in net):
            return (AUDIT_NONE,
                    "查验平台打不开（网络不通：%s）——请确认这台电脑能上外网，"
                    "或让 IT 放开 inv-veri.chinatax.gov.cn"
                    % head.split(" at ")[0], "", None)
        return AUDIT_NONE, "查验平台打不开：%s" % head[:100], "", None

    try:
        page.set_viewport_size({"width": CONFIG["viewport_width"],
                                "height": CONFIG["viewport_height"]})
    except Exception:
        pass

    # 等表单渲染完（单页应用，DOM 是异步出来的）
    step("页面已打开，等待表单加载…")
    try:
        page.locator(sel["fphm"]).first.wait_for(state="visible", timeout=page_ms)
    except Exception:
        check_abort()
        return (AUDIT_NONE,
                "查验平台没有打开（表单 %d 秒没出来，可能是网络慢或平台改版）"
                % (page_ms // 1000), "", None)

    step("填写发票号码 / 开票日期 / 金额…")
    ok, msg = _fill_form(page, query, sel)
    if not ok:
        return AUDIT_NONE, msg, "", None

    for _round in range(CONFIG["captcha_rounds"]):
        text = ""
        for _try in range(CONFIG["captcha_tries"]):
            step("识别验证码（第 %d 轮 · 第 %d 次）…" % (_round + 1, _try + 1))
            text = _solve_captcha(page, sel, recognizer, color_parser)
            if text:
                break
            time.sleep(0.8)
        if not text:
            return AUDIT_NONE, "验证码连续识别失败（图片太糊或模型没加载）", "", None

        try:
            page.locator(sel["yzm"]).fill(text, timeout=10000)
        except Exception:
            return AUDIT_NONE, "验证码填不进去（页面结构可能变了）", "", None

        step("提交查验，等待平台返回…")
        data, err = _submit(page, sel)
        if data is None:
            return AUDIT_NONE, err, "", None

        status, msg = _judge(data)
        if status is None:          # 验证码错了，再来一轮
            # 只关弹窗，不主动刷新：下一轮 _solve_captcha 自己会点图片，
            # 这里多点一次会让"抓到的验证码"和"页面显示的验证码"错位。
            say("验证码被平台判错，换一张重试…")
            _dismiss_popup(page, sel)
            time.sleep(1.5)
            continue

        if status != AUDIT_PASS:
            return status, msg, "", data

        # 通过：等结果弹窗出来，拿文字（截图在 _one 里做）
        step("查验通过，读取结果…")
        for _ in range(20):
            if _result_ready(page, sel):
                break
            time.sleep(0.3)
        body = _result_text(page, sel)
        return AUDIT_PASS, msg, body, data

    return AUDIT_NONE, "验证码多次不正确", "", None


def _first_hit(text):
    for line in (text or "").splitlines():
        line = line.strip()
        if line and any(w in line for w in FAIL_WORDS):
            return line[:60]
    return "平台提示信息不一致"


def _is_rate_limited(msg):
    m = str(msg or "")
    return any(k in m for k in ("次数", "频繁", "超限", "稍后", "休息"))


def _backoff_seconds(i):
    seq = CONFIG.get("backoff") or [60, 180, 300]
    try:
        return int(seq[min(i, len(seq) - 1)])
    except (TypeError, ValueError, IndexError):
        return 60


# ----------------------------------------------------------------------
# 一致性比对 / 内控提示
# ----------------------------------------------------------------------
def _norm_text(s):
    """比对名称用：去掉空白和常见标点，避免"有限公司。" vs "有限公司" 这种假差异。"""
    return re.sub(r"[\s\u3000（）()·.,，、:：;；\-]+", "", str(s or ""))


def platform_fields(data):
    """把平台返回里真正有用的字段抽出来。"""
    d = (data.get("Response") or {}).get("Data") or {}
    vo = d.get("DzfpFpxxVO") or {}
    items = []
    for x in (vo.get("MxzbList") or []):
        name = x.get("Hwhyslwfwmc") or x.get("Xmmc") or ""
        if name:
            items.append(name)
    return {
        "no": _digits(vo.get("Fphm")),
        "date": _digits(vo.get("Kprq"))[:8],
        "amount": vo.get("Jshj"),
        "amount_net": vo.get("Hjje"),
        "tax": vo.get("Hjse"),
        "buyer": (vo.get("Gmfmc") or "").strip(),
        "buyer_tax": (vo.get("Gmfnsrsbh") or "").strip(),
        "seller": (vo.get("Xsfmc") or "").strip(),
        "seller_tax": (vo.get("Xsfnsrsbh") or "").strip(),
        "items": items,
        "status": vo.get("FpztDm"),
    }


def compare(record, plat):
    """
    台账 vs 平台 逐项比对。
    返回 (hard, soft)：
        hard —— 号码/日期/价税合计对不上，属于"这不是同一张票"，判不通过
        soft —— 销售方名称有出入，多半是台账 OCR 的锅，只提示
    """
    hard, soft = [], []

    no = _digits(record.get("invoice_no"))
    if plat.get("no") and plat["no"] != no:
        hard.append("发票号码不一致（台账 %s / 平台 %s）" % (no, plat["no"]))

    d1 = _compact_date(record.get("invoice_date"))
    d2 = plat.get("date") or ""
    if d1 and d2 and d1 != d2:
        hard.append("开票日期不一致（台账 %s / 平台 %s）" % (d1, d2))

    try:
        a1 = float(record.get("amount"))
        a2 = float(plat.get("amount"))
        if abs(a1 - a2) > 0.011:
            hard.append("价税合计不一致（台账 %.2f / 平台 %.2f）" % (a1, a2))
    except (TypeError, ValueError):
        pass

    s1 = _norm_text(record.get("seller"))
    s2 = _norm_text(plat.get("seller"))
    if s1 and s2 and s1 != s2:
        soft.append("销售方名称有出入（台账「%s」/ 平台「%s」）" % (
            (record.get("seller") or "")[:30], plat["seller"][:30]))

    return hard, soft


def internal_warnings(record, plat):
    """
    业务内控提示：不改变查验结论，只是在界面上提醒人看一眼。
    """
    warns = []

    # 1) 抬头是不是本公司
    names = [_norm_text(x) for x in (CONFIG.get("company_names") or []) if str(x).strip()]
    buyer = plat.get("buyer") or (record.get("buyer") or "")
    if names and buyer:
        nb = _norm_text(buyer)
        if not any(n and (n in nb or nb in n) for n in names):
            warns.append("购买方不是本公司（%s）" % buyer)

    # 2) 收款人与报销人不是同一人
    #    这条故意不做成"警告"（不写进 audit_warn、不点亮感叹号），
    #    因为它太常见（代报、家属代收），做成硬提示会让人对感叹号麻木。
    #    界面按台账现有的 报销人/收款人 现场派生一句灰字提示即可，
    #    好处是改对了人提示立刻消失，不用重新查验。

    # 3) 跨月报销（默认关闭：公司习惯次月报）
    if CONFIG.get("warn_cross_month", False):
        kp = (record.get("invoice_date") or "")[:7]
        rb = (record.get("reimburse_date") or "")[:7]
        if kp and rb and kp != rb:
            warns.append("跨月报销（开票 %s，报销 %s）" % (kp, rb))

    # 4) 大额提示
    try:
        limit = float(CONFIG.get("warn_amount") or 0)
        amt = float(plat.get("amount"))
        if limit > 0 and amt >= limit:
            warns.append("金额较大（%.2f 元），建议二次确认" % amt)
    except (TypeError, ValueError):
        pass

    return warns


# ----------------------------------------------------------------------
# 后台任务
# ----------------------------------------------------------------------
class Runner(object):
    def __init__(self, store):
        self.store = store
        self.lock = threading.Lock()
        self.thread = None
        self.stop_flag = threading.Event()
        self.state = self._empty()
        self._page = None      # 当前正在用的页面，停止时直接关掉

    @staticmethod
    def _empty():
        return {
            "running": False, "total": 0, "done": 0,
            "ok": 0, "fail": 0, "skip": 0,
            "current": "", "current_no": "",
            "log": [], "error": None,
            "started_at": None, "finished_at": None,
        }

    # ---------------- 对外 ----------------
    def snapshot(self):
        with self.lock:
            s = dict(self.state)
            s["log"] = list(self.state["log"])
        return s

    def start(self, ids):
        with self.lock:
            if self.state["running"]:
                return False, "已有查验任务在运行，请等它结束"
            records = [self.store.get(int(i)) for i in ids]
            records = [r for r in records if r]
            if not records:
                return False, "没有可查验的发票"
            self.stop_flag.clear()
            self.state = self._empty()
            self.state["running"] = True
            self.state["total"] = len(records)
            self.state["started_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            self.thread = threading.Thread(target=self._run, args=(records,), daemon=True)
            self.thread.start()
        return True, ""

    def stop(self):
        self.stop_flag.set()
        with self.lock:
            self._log_locked("收到停止指令，正在中断当前这张…")
        # 光设标志位不够：页面卡在 goto / 等待元素上时，要把它关掉才能立刻中断
        page = self._page
        if page is not None:
            try:
                page.close()
            except Exception:
                pass

    # ---------------- 内部 ----------------
    def _log(self, text):
        with self.lock:
            self._log_locked(text)

    def _log_locked(self, text):
        self.state["log"].append({
            "t": time.strftime("%H:%M:%S"),
            "text": text,
        })
        if len(self.state["log"]) > 300:
            self.state["log"] = self.state["log"][-300:]

    def _bump(self, field, n=1):
        with self.lock:
            self.state[field] = self.state.get(field, 0) + n

    def _run(self, records):
        recognizer = color_parser = None
        try:
            from playwright.sync_api import sync_playwright
            from 审核.captcha import CaptchaRecognizer
            from 审核.color_parse import ColorParser

            version = CONFIG["page_version"]
            sel = SELECTORS.get(version, SELECTORS["old"])
            mdir = model_dir(version)
            if not model_ready(version):
                self._log("验证码模型缺失：%s —— 自动识别验证码将不可用，"
                          "只能靠人工填验证码" % mdir)
            recognizer = CaptchaRecognizer(os.path.join(mdir, "inference.onnx"),
                                           os.path.join(mdir, "ppocrv5_dict.txt"))
            color_parser = ColorParser()

            launch_kw = {
                "headless": CONFIG["headless"],
                "args": ["--window-position=%s" % CONFIG["window_position"]],
            }
            if CONFIG.get("browser_path"):
                launch_kw["executable_path"] = CONFIG["browser_path"]

            with sync_playwright() as p:
                self._log("启动浏览器（%s 版页面）" % ("新" if version == "new" else "旧"))
                try:
                    browser = p.chromium.launch(**launch_kw)
                except Exception as exc:
                    self._log("浏览器启动失败：%s" % str(exc)[:300])
                    if "playwright install" in str(exc) or "Executable doesn't exist" in str(exc) \
                            or "chromium" in str(exc).lower():
                        self._log("解决办法：在项目目录执行下面的命令重装浏览器"
                                  "（Linux 用 .venv/bin/python，Windows 用 python）：")
                        self._log("    python -m playwright install chromium")
                        self._log("如果浏览器已经装过，多半是「装的位置和服务用户看不到」"
                                  "：设置环境变量 PLAYWRIGHT_BROWSERS_PATH 指向同一个目录后重启")
                    return

                # 无头时把 UA 换成普通 Chrome，否则网关会掐掉验证码接口
                _UA["value"] = build_user_agent(browser.version)
                if CONFIG["headless"]:
                    self._log("浏览器在后台运行（不弹窗口）")
                try:
                    for rec in records:
                        if self.stop_flag.is_set():
                            break
                        self._one(browser, rec, sel, recognizer, color_parser)
                        time.sleep(CONFIG["interval"])
                finally:
                    try:
                        browser.close()
                    except Exception:
                        pass
        except ImportError as exc:
            self._log("缺少查验组件：%s —— 请在命令行执行 "
                      "python -m pip install playwright rapidocr onnxruntime" % exc)
        except Exception:
            self._log("查验任务异常：\n" + traceback.format_exc()[-800:])
        finally:
            with self.lock:
                self.state["running"] = False
                self.state["current"] = ""
                self.state["current_no"] = ""
                self.state["finished_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            self._log("任务结束：成功 %d，不通过 %d，跳过 %d" % (
                self.state["ok"], self.state["fail"], self.state["skip"]))

    def _model_ready(self):
        return model_ready(CONFIG["page_version"])

    def _one(self, browser, rec, sel, recognizer, color_parser):
        no = rec.get("invoice_no") or ""
        with self.lock:
            self.state["current"] = no
            self.state["current_no"] = no
        self._log("开始查验 %s" % no)

        try:
            src = _source_fields(rec)
            query, msg, skip = build_query(rec, src)
        except Exception:
            query, msg, skip = None, "读取原件出错", False

        if not query:
            status = AUDIT_SKIP if skip else AUDIT_NONE
            if skip:
                self._bump("skip")
                self._log("跳过 %s：%s" % (no, msg))
            else:
                self._log("未查验 %s：%s" % (no, msg))
            self.store.set_audit(rec["id"], status, msg, None)
            self._bump("done")
            return

        status, msg, shot, warn = AUDIT_NONE, "查验失败", None, ""
        backoff_i = 0
        # 这张发票的总预算：超时就放弃，免得界面上"点了半天没反应"
        budget = float(CONFIG.get("invoice_timeout", 180))
        deadline = time.time() + budget

        def abort():
            if self.stop_flag.is_set():
                return "stop"
            if time.time() > deadline:
                return "timeout"
            return None

        for attempt in range(CONFIG["retries"]):
            page = None
            if abort() == "stop":
                break
            if abort() == "timeout":
                if attempt == 0:
                    msg = "查验超时（%d 秒没结果），已放弃这张" % int(budget)
                    self._log(msg)
                break
            try:
                page = new_page(browser)
                self._page = page          # 让 stop() 能把卡住的页面直接关掉
                status, msg, _body, data = _verify_once(
                    page, query, sel, recognizer, color_parser,
                    log=self._log, abort=abort)

                if status == AUDIT_PASS and data:
                    # 台账 vs 平台 逐项比对：号码/日期/价税合计对不上，就不算通过
                    plat = platform_fields(data)
                    hard, soft = compare(rec, plat)
                    warns = soft + internal_warnings(rec, plat)
                    warn = "；".join(warns)
                    if hard:
                        status = AUDIT_FAIL
                        msg = "信息不一致：" + "；".join(hard)
                        self._log("%s 与台账不一致：%s" % (no, "；".join(hard)))
                    elif warns:
                        self._log("%s 查验通过，但有 %d 条提示" % (no, len(warns)))

                # 有结果弹窗就截图（通过 / 不一致都留证）
                if status in (AUDIT_PASS, AUDIT_FAIL):
                    rel = shot_rel_path(no)
                    if _capture(page, sel, abs_rel(rel)):
                        shot = rel
                    else:
                        self._log("截图失败：%s" % no)

                # 被平台限流：等一会儿再重试，别硬撞
                if status == AUDIT_NONE and _is_rate_limited(msg):
                    wait = _backoff_seconds(backoff_i)
                    self._log("平台限流（%s），等 %d 秒再试" % (msg, wait))
                    time.sleep(wait)
                    backoff_i += 1
                    continue
                break
            except _Abort as exc:
                # 用户点了停止 / 这张超时：不再重试，直接收尾
                if str(exc) == "stop":
                    msg = "已停止"
                    self._log("已停止，%s 未查验" % no)
                else:
                    msg = "查验超时（%d 秒没结果），已放弃这张" % int(budget)
                    self._log(msg)
                break
            except Exception as exc:
                why = abort()
                if why == "stop":
                    msg = "已停止"
                    self._log("已停止，%s 未查验" % no)
                    break
                if why == "timeout":
                    msg = "查验超时（%d 秒没结果），已放弃这张" % int(budget)
                    self._log(msg)
                    break
                msg = "查验出错：%s" % str(exc)[:120]
                self._log("%s 第 %d 次出错：%s" % (no, attempt + 1, str(exc)[:300]))
            finally:
                try:
                    if page:
                        page.close()
                except Exception:
                    pass

        if status == AUDIT_PASS:
            self._bump("ok")
            self._log("查验通过 %s" % no)
        elif status == AUDIT_FAIL:
            self._bump("fail")
            self._log("查验不通过 %s：%s" % (no, msg))
        else:
            self._log("未能查验 %s：%s" % (no, msg))

        self.store.set_audit(rec["id"], status, msg, shot, warn)
        self._bump("done")
