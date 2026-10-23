# -*- coding: utf-8 -*-
"""
发票识别模块
------------
从 PDF（或 OFD 解包后的文本）中自动提取：
    发票号码、开票日期、金额(价税合计)、不含税金额、税额、销售方、购买方、项目类别

识别原理：
    1. 读 PDF 文字层，按坐标把所有文字片段重建成“视觉行”
       （多家开票平台的文字流顺序是乱的，直接按顺序读会串行）
    2. 在每一行上用正则定位字段
    3. 金额用“小写”与“大写”互相校验，不一致则给出警告

兜底顺序：文字层 -> 文件名中的20位号码 -> 发票二维码 -> 提示手工输入
"""
import os
import re

CN_DIGITS = "零壹贰叁肆伍陆柒捌玖"
CN_UNITS = {"拾": 10, "佰": 100, "仟": 1000}
CN_BIG = {"万": 10000, "亿": 100000000}

RE_INVOICE_NO_LINE = re.compile(r"发票号码[:：]?\s*(\d{20})")
RE_INVOICE_NO_ANY = re.compile(r"(?<!\d)(\d{20})(?!\d)")
RE_DATE_CN_LINE = re.compile(r"开票日期[:：]?\s*(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日")
RE_DATE_CN_ANY = re.compile(r"(\d{4})\s*年\s*(\d{1,2})\s*月\s{0,4}(\d{1,2})\s*日")
RE_MONEY = re.compile(r"[¥￥]\s*([\d,]+\.\d{2})|(?<![\d.])([\d,]{1,12}\.\d{2})(?![\d])")
RE_DAXIE = re.compile(r"[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]{1,20}[圆元][零壹贰叁肆伍陆柒捌玖角分整]{0,6}")


def import_fitz():
    """兼容新旧两种导入名。"""
    try:
        import pymupdf as fitz
    except ImportError:  # pragma: no cover
        import fitz
    return fitz


# --------------------------------------------------------------------------
# 大写金额 -> 数字
# --------------------------------------------------------------------------
def cn_amount_to_float(text):
    """把 '贰拾伍圆捌角陆分' / '叁佰肆拾肆圆整' 解析成 25.86 / 344.0"""
    if not text:
        return None
    text = text.strip()
    m = re.search(r"([零壹贰叁肆伍陆柒捌玖拾佰仟万亿]+)[圆元]([零壹贰叁肆伍陆柒捌玖角分整]*)", text)
    if not m:
        return None
    whole, frac = m.group(1), m.group(2)

    total, section, num = 0, 0, 0
    for ch in whole:
        if ch in CN_DIGITS:
            num = CN_DIGITS.index(ch)
        elif ch in CN_UNITS:
            section += (num if num else 1) * CN_UNITS[ch]
            num = 0
        elif ch in CN_BIG:
            section = (section + num) * CN_BIG[ch]
            total += section
            section, num = 0, 0
    value = float(total + section + num)

    jiao = re.search(r"([零壹贰叁肆伍陆柒捌玖])角", frac)
    fen = re.search(r"([零壹贰叁肆伍陆柒捌玖])分", frac)
    if jiao:
        value += CN_DIGITS.index(jiao.group(1)) / 10.0
    if fen:
        value += CN_DIGITS.index(fen.group(1)) / 100.0
    return round(value, 2)


# --------------------------------------------------------------------------
# 按坐标把文字片段重建成视觉行
# --------------------------------------------------------------------------
def page_lines(page, tol=3.0):
    """返回按视觉顺序排列的文本行列表。"""
    try:
        words = page.get_text("words")
    except Exception:
        words = []
    items = []
    for w in words:
        x0, y0, x1, y1, txt = w[0], w[1], w[2], w[3], w[4]
        items.append((round((y0 + y1) / 2.0, 1), x0, txt))
    items.sort(key=lambda t: (t[0], t[1]))

    lines, cur, cur_y = [], [], None
    for y, x, txt in items:
        if cur_y is None or abs(y - cur_y) <= tol:
            cur.append((x, txt))
            cur_y = y if cur_y is None else (cur_y + y) / 2.0
        else:
            lines.append(cur)
            cur, cur_y = [(x, txt)], y
    if cur:
        lines.append(cur)

    out = []
    for ln in lines:
        ln.sort(key=lambda t: t[0])
        out.append("".join(t[1] for t in ln))
    return [s for s in out if s.strip()]


def _squeeze(s):
    return re.sub(r"[\s\u3000]+", "", s)


def _money_values(text):
    """取出文本中所有形如 12.34 的金额（去重、排序）。"""
    vals = []
    for m in re.finditer(r"(?<![\d.])(\d[\d,]*\.\d{2})(?![\d])", text):
        try:
            vals.append(round(float(m.group(1).replace(",", "")), 2))
        except ValueError:
            pass
    return vals


# --------------------------------------------------------------------------
# 主入口
# --------------------------------------------------------------------------
def extract_from_pdf(path):
    """解析单个 PDF，返回字段字典。失败不抛异常，字段留空并给出 warnings。"""
    res = {
        "invoice_no": None,
        "invoice_date": None,
        "amount": None,
        "amount_net": None,
        "tax": None,
        "seller": None,
        "buyer": None,
        "category": None,
        "page_count": 0,
        "text_layer": False,
        "source": None,
        "warnings": [],
    }

    try:
        fitz = import_fitz()
    except ImportError:
        res["warnings"].append("缺少 PDF 解析组件，无法自动识别，请手工输入发票号码")
        return res

    try:
        doc = fitz.open(path)
    except Exception as exc:
        res["warnings"].append("无法打开 PDF：%s" % exc)
        return res

    try:
        if doc.is_encrypted:
            try:
                doc.authenticate("")
            except Exception:
                pass
        res["page_count"] = doc.page_count

        all_lines = []
        line_sources = []          # (页码, 行文本)
        for pno in range(min(doc.page_count, 5)):
            try:
                for ln in page_lines(doc[pno]):
                    all_lines.append(ln)
                    line_sources.append((pno, ln))
            except Exception:
                continue

        if not all_lines:
            res["warnings"].append("PDF 里没有文字层，可能是扫描件/图片发票")
        else:
            res["text_layer"] = True
            _parse_lines(all_lines, line_sources, res)

        # ---------------- 兜底 1：文件名里的 20 位号码 ----------------
        if not res["invoice_no"]:
            m = RE_INVOICE_NO_ANY.search(os.path.basename(path))
            if m:
                res["invoice_no"] = m.group(1)
                res["source"] = "文件名"
                res["warnings"].append("发票号码取自文件名，请核对")

        # ---------------- 兜底 2：发票二维码 ----------------
        # 全电发票二维码内容形如 01,32,,<20位号码>,<金额>,<yyyyMMdd>,,校验码
        # 图片型 / 扫描件发票没有文字层，全靠这里
        if not (res["invoice_no"] and res["amount"] and res["invoice_date"]):
            qr = _read_qr(doc)
            if qr:
                if not res["invoice_no"] and qr.get("invoice_no"):
                    res["invoice_no"] = qr["invoice_no"]
                    res["source"] = "二维码"
                    res["warnings"].append("发票号码取自二维码，请核对")
                if not res["amount"] and qr.get("amount") is not None:
                    res["amount"] = qr["amount"]
                    res["warnings"].append("金额取自二维码，请核对")
                if not res["invoice_date"] and qr.get("invoice_date"):
                    res["invoice_date"] = qr["invoice_date"]
    finally:
        try:
            doc.close()
        except Exception:
            pass

    # 号码格式检查
    if res["invoice_no"]:
        res["invoice_no"] = re.sub(r"\D", "", res["invoice_no"])
        if len(res["invoice_no"]) != 20:
            res["warnings"].append(
                "发票号码为 %d 位（数电发票应为 20 位），请人工确认" % len(res["invoice_no"])
            )
    else:
        res["warnings"].append("未能自动识别发票号码，请手工输入")

    return res


def _parse_lines(all_lines, line_sources, res):
    """在重建后的文本行上定位各字段。"""
    full = "\n".join(all_lines)
    flat = _squeeze(full)

    # ---------- 发票号码 ----------
    for ln in all_lines:
        m = RE_INVOICE_NO_LINE.search(_squeeze(ln))
        if m:
            res["invoice_no"] = m.group(1)
            res["source"] = "PDF文字"
            break

    # ---------- 开票日期 ----------
    for ln in all_lines:
        m = RE_DATE_CN_LINE.search(_squeeze(ln))
        if m:
            res["invoice_date"] = "%04d-%02d-%02d" % (int(m.group(1)), int(m.group(2)), int(m.group(3)))
            break
    if not res["invoice_date"]:
        m = RE_DATE_CN_ANY.search(flat)
        if m:
            res["invoice_date"] = "%04d-%02d-%02d" % (int(m.group(1)), int(m.group(2)), int(m.group(3)))

    # ---------- 价税合计（小写） ----------
    amount_xiaoxie = None
    for ln in all_lines:
        s = _squeeze(ln)
        if "小写" in s:
            tail = s.split("小写", 1)[1]
            vals = _money_values(tail)
            if vals:
                amount_xiaoxie = vals[0]
                break
    # ---------- 价税合计（大写） ----------
    amount_daxie = None
    for ln in all_lines:
        if "价税合计" in ln and "大写" in ln:
            amount_daxie = cn_amount_to_float(ln)
            if amount_daxie:
                break
    if amount_daxie is None:
        for cand in RE_DAXIE.findall(flat):
            v = cn_amount_to_float(cand)
            if v:
                amount_daxie = v
                break

    if amount_xiaoxie is not None and amount_daxie is not None:
        if abs(amount_xiaoxie - amount_daxie) < 0.005:
            res["amount"] = amount_xiaoxie
        else:
            res["amount"] = amount_xiaoxie
            res["warnings"].append(
                "小写金额 %s 与大写金额 %s 不一致，请人工核对" % (amount_xiaoxie, amount_daxie)
            )
    elif amount_xiaoxie is not None:
        res["amount"] = amount_xiaoxie
    elif amount_daxie is not None:
        res["amount"] = amount_daxie
    else:
        # 最后兜底：取票面最大金额（通常就是价税合计）
        vals = _money_values(flat)
        if vals:
            res["amount"] = max(vals)
            res["warnings"].append("价税合计为推算值，请人工核对")

    # ---------- 合计行：不含税金额 与 税额 ----------
    for ln in all_lines:
        s = _squeeze(ln)
        if s.startswith("合计") and "小写" not in s:
            vals = _money_values(s)
            if len(vals) >= 2:
                res["amount_net"] = vals[0]
                res["tax"] = vals[1]
            break
    if res["amount"] is not None and res["amount_net"] is None and res["tax"] is not None:
        res["amount_net"] = round(res["amount"] - res["tax"], 2)
    if res["amount"] is not None and res["tax"] is None and res["amount_net"] is not None:
        res["tax"] = round(res["amount"] - res["amount_net"], 2)

    # ---------- 购买方 / 销售方 ----------
    names = _party_names(all_lines)
    if names:
        res["buyer"] = names[0]
        if len(names) > 1:
            res["seller"] = names[1]

    # ---------- 项目类别 ----------
    for ln in all_lines:
        m = re.search(r"\*([^*\n]{1,20})\*([^*\n]{1,40}?)(?=\d+\.\d|$)", ln)
        if m:
            first = m.group(1).strip()
            second = m.group(2).strip()
            # 第二段含数字（如 95号车用汽油）时只保留税收分类简称，避免把单位/数量带进来
            if second and re.fullmatch(r"[\u4e00-\u9fa5A-Za-z()（）\u2160-\u2183·]{2,24}", second):
                res["category"] = "%s / %s" % (first, second)
            else:
                res["category"] = first
            break


def _party_names(lines):
    """
    购买方在票据左栏、销售方在右栏。重建行里两栏会被拼在一起，
    形如 '名称：A公司名称：B公司'，按出现顺序取即可。
    """
    names = []
    for ln in lines:
        if "名称：" not in ln and "名称:" not in ln:
            continue
        if "项目名称" in ln:
            continue
        parts = re.split(r"名称\s*[:：]", ln)
        for p in parts[1:]:
            p = p.strip()
            p = re.sub(r"^(购买方|销售方|购|销|买|售|方|信|息)+", "", p)
            p = re.sub(r"(统一社会信用代码.*|纳税人识别号.*)$", "", p)
            p = p.strip("　 :：")
            # 去掉右侧栏跑进来的残字
            p = re.sub(r"(购|销|买|售|方|信|息)+$", "", p)
            if 4 <= len(p) <= 60 and not p.startswith("统一社会信用代码"):
                names.append(p)
        if names:
            break
    # 去重保序
    seen, out = set(), []
    for n in names:
        if n not in seen:
            seen.add(n)
            out.append(n)
    return out


def _read_qr(doc):
    """渲染首页并用二维码解码兜底（应对图片型发票）。
    全电发票二维码内容形如： 01,32,,26327000001715695220,25.86,20260928,,3de2"""
    try:
        import zxingcpp
        import io
        from PIL import Image
    except Exception:
        return None
    out = {}
    try:
        page = doc[0]
        pix = page.get_pixmap(dpi=220)
        img = Image.open(io.BytesIO(pix.tobytes("png")))
        for r in zxingcpp.read_barcodes(img):
            text = r.text or ""
            parts = text.split(",")
            if len(parts) >= 6:
                no = re.sub(r"\D", "", parts[3])
                if len(no) == 20:
                    out["invoice_no"] = no
                try:
                    out["amount"] = round(float(parts[4]), 2)
                except (ValueError, IndexError):
                    pass
                d = re.sub(r"\D", "", parts[5])
                if len(d) == 8:
                    out["invoice_date"] = "%s-%s-%s" % (d[:4], d[4:6], d[6:8])
            if not out.get("invoice_no"):
                m = RE_INVOICE_NO_ANY.search(text)
                if m:
                    out["invoice_no"] = m.group(1)
            if out.get("invoice_no"):
                break
    except Exception:
        return None
    return out or None


def extract_from_ofd(path):
    """
    OFD 兜底：OFD 本质是 zip + XML，直接把里面的文本抓出来再走同一套正则。
    """
    import zipfile
    res = {
        "invoice_no": None, "invoice_date": None, "amount": None,
        "amount_net": None, "tax": None, "seller": None, "buyer": None,
        "category": None, "page_count": 0, "text_layer": False,
        "source": None, "warnings": [],
    }
    try:
        texts = []
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.lower().endswith(".xml"):
                    try:
                        raw = z.read(n).decode("utf-8", "ignore")
                    except Exception:
                        continue
                    texts.append(re.sub(r"<[^>]+>", " ", raw))
        full = _squeeze(" ".join(texts))
        res["text_layer"] = bool(full)
        m = RE_INVOICE_NO_ANY.search(full)
        if m:
            res["invoice_no"] = m.group(1)
            res["source"] = "OFD"
        m = RE_DATE_CN_ANY.search(full)
        if m:
            res["invoice_date"] = "%04d-%02d-%02d" % (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        vals = _money_values(full)
        if vals:
            res["amount"] = max(vals)
            res["warnings"].append("OFD 金额为推算值，请人工核对")
    except Exception as exc:
        res["warnings"].append("OFD 解析失败：%s" % exc)

    if not res["invoice_no"]:
        m = RE_INVOICE_NO_ANY.search(os.path.basename(path))
        if m:
            res["invoice_no"] = m.group(1)
            res["source"] = "文件名"
    if not res["invoice_no"]:
        res["warnings"].append("未能自动识别发票号码，请手工输入")
    return res


def extract(path):
    """统一入口，按扩展名分派。"""
    ext = os.path.splitext(path)[1].lower()
    if ext == ".ofd":
        return extract_from_ofd(path)
    return extract_from_pdf(path)
