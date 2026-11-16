# -*- coding: utf-8 -*-
"""图片型发票（扫描件）的字段识别，仅在 PDF 没有文字层时作为兜底。"""
import re

from rapidocr import RapidOCR


class InvoiceExtractor:
    def __init__(self):
        self.engine = RapidOCR()

    def extract_fields(self, img_path):
        """识别发票图片并提取关键字段，返回 {字段名: 值}。"""
        result = self.engine(img_path)
        text_blocks = []
        for txt, score in zip(result.txts or [], result.scores or []):
            if score < 0.5:
                continue
            text_blocks.append(txt.strip())

        fields = {
            "fpdm": None, "fphm": None, "kprq": None, "jym": None,
            "kpje": None, "page_total": None, "page_current": None, "qtje": [],
        }

        prev_text = ""
        for text in text_blocks:
            if re.search(r"发票\s*代码", text):
                m = re.search(r"发票\s*代码[：:]\s*(\d{12}|\d{10})", text)
                if m:
                    fields["fpdm"] = m.group(1)
            elif len(text) in (10, 12) and text.isdigit():
                fields["fpdm"] = text

            if re.search(r"发票\s*号码", text):
                m = re.search(r"发票\s*号码[：:]\s*(\d{8,20})", text)
                if m:
                    fields["fphm"] = m.group(1)
            elif len(text) == 8 and text.isdigit():
                fields["fphm"] = text

            m = re.search(r"(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}日?)", text)
            if m and (re.search(r"开票\s*日期", text) or re.search(r"开票\s*日期", prev_text)):
                fields["kprq"] = m.group(1)

            if re.search(r"校验\s*码", text):
                m = re.search(r"校验\s*码[：:]\s*(.*)", text)
                if m:
                    candidate = re.sub(r"\s+", "", m.group(1))
                    if re.fullmatch(r"\d{20}", candidate):
                        fields["jym"] = candidate
                else:
                    codes = re.findall(r"\d{20}", re.sub(r"\s+", "", text))
                    if codes:
                        fields["jym"] = codes[0]

            m = re.search(r"[¥￥]?\s*([\d,]+\.\d{1,2})", text)
            if m and (re.search(r"小写", text) or re.search(r"小写", prev_text)):
                fields["kpje"] = m.group(1).replace(",", "")
            elif m and re.search(r"[¥￥]", text):
                fields["qtje"].append(m.group(1).replace(",", ""))

            m = re.search(r"共\s*(\d+)\s*页\s*第\s*(\d+)\s*页", text)
            if m:
                fields["page_total"] = int(m.group(1))
                fields["page_current"] = int(m.group(2))
            else:
                m_total = re.search(r"共\s*(\d+)\s*页", text)
                m_current = re.search(r"第\s*(\d+)\s*页", text)
                if m_total:
                    fields["page_total"] = int(m_total.group(1))
                if m_current:
                    fields["page_current"] = int(m_current.group(1))
                if not m_current:
                    pm = re.search(r"第\s*(\d+)\s*页", prev_text)
                    if pm:
                        fields["page_current"] = int(pm.group(1))
                if not m_total:
                    pm = re.search(r"共\s*(\d+)\s*页", prev_text)
                    if pm:
                        fields["page_total"] = int(pm.group(1))

            prev_text = text

        return fields
