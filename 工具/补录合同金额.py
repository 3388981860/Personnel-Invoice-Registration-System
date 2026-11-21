# -*- coding: utf-8 -*-
"""
把「合同金额补录表.xlsx」里的金额/签订状态写回数据库。

用法：
    python 工具/补录合同金额.py                     # 预演，只看不改
    python 工具/补录合同金额.py --export-json p.json # 导出改动，供服务器上执行
    python 工具/补录合同金额.py --json p.json --db 数据/发票台账.db --yes
"""
import argparse
import json
import os
import sqlite3
import sys

from openpyxl import load_workbook

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XLSX = os.path.join(ROOT, "合同金额补录表.xlsx")

KIND = {"收入": "income", "外协": "cost", "关联": "reference"}


def read_rows():
    wb = load_workbook(XLSX, data_only=True)
    ws = wb["合同金额"]
    out = []
    for r in range(2, ws.max_row + 1):
        cid = ws.cell(row=r, column=1).value
        if cid is None:
            continue
        amount = ws.cell(row=r, column=9).value
        signed = ws.cell(row=r, column=10).value
        out.append({
            "id": int(cid),
            "amount": clean_amount(amount),
            "signed": (str(signed).strip() if signed is not None else ""),
            "project": str(ws.cell(row=r, column=3).value or ""),
            "kind": str(ws.cell(row=r, column=5).value or ""),
            "party": "%s → %s" % (ws.cell(row=r, column=6).value or "",
                                  ws.cell(row=r, column=7).value or ""),
        })
    return out


def clean_amount(v):
    if v is None or v == "":
        return None
    if isinstance(v, (int, float)):
        return round(float(v), 2)
    s = str(v).replace(",", "").replace("¥", "").replace("￥", "").strip()
    if not s:
        return None
    try:
        return round(float(s), 2)
    except ValueError:
        return None


def apply(rows, db):
    conn = sqlite3.connect(db)
    n_amt = n_signed = 0
    for x in rows:
        if x["amount"] is not None:
            cur = conn.execute("SELECT amount FROM contracts WHERE id = ?", (x["id"],)).fetchone()
            if cur is None:
                print("  ! 合同 %s 不存在，跳过" % x["id"])
                continue
            if cur[0] is None or abs((cur[0] or 0) - x["amount"]) > 0.001:
                conn.execute("UPDATE contracts SET amount = ?, updated_at = datetime('now','localtime') "
                             "WHERE id = ?", (x["amount"], x["id"]))
                n_amt += 1
        if x["signed"]:
            conn.execute("UPDATE contracts SET signed = ?, updated_at = datetime('now','localtime') "
                         "WHERE id = ?", (x["signed"], x["id"]))
            n_signed += 1
    conn.commit()
    conn.close()
    print("已更新金额 %d 条、签订状态 %d 条" % (n_amt, n_signed))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="")
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--export-json", default="")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    if args.json:
        with open(args.json, "r", encoding="utf-8") as fh:
            rows = json.load(fh)
    else:
        if not os.path.isfile(XLSX):
            print("找不到补录表：%s" % XLSX)
            sys.exit(1)
        rows = read_rows()

    filled = [x for x in rows if x["amount"] is not None]
    empty = [x for x in rows if x["amount"] is None]
    print("共 %d 行：填了金额 %d 行，还空着 %d 行" % (len(rows), len(filled), len(empty)))

    if filled:
        print("\n将要写入的金额：")
        for x in filled:
            print("  #%-4s %-8s %-32s %10.2f  %s" % (
                x["id"], x["kind"], x["project"][:30], x["amount"], x["party"][:30]))
    if empty:
        kinds = {}
        for x in empty:
            kinds[x["kind"]] = kinds.get(x["kind"], 0) + 1
        print("\n没填金额的：%s" % "、".join("%s %d 条" % (k, v) for k, v in kinds.items()))

    if args.export_json:
        with open(args.export_json, "w", encoding="utf-8") as fh:
            json.dump(filled, fh, ensure_ascii=False, indent=1)
        print("\n改动已导出：%s" % args.export_json)
        return

    if not args.yes:
        print("\n（预演结束，加 --yes 才会写入）")
        return
    if not args.db:
        print("真写要指定 --db")
        sys.exit(1)
    apply(rows, args.db)


if __name__ == "__main__":
    main()
