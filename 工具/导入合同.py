# -*- coding: utf-8 -*-
"""
把「合同签订开票情况.xlsx」导入合同台账。

映射规则：
  每一行（项目名称非空）= 一个项目
    单位 / 运维起止 / 甲方联系人  → 项目
    甲方合同状态                  → 收入合同的签订状态
  收入合同：数据集团 → 人数（我司）
  外协合同：人数（我司） → 外协单位（外协单位空或「/」就不建）
  开票/回款状态写进合同备注，金额留空等你们补

用法：
  python import_contracts.py                 # 预演，只看不改
  python import_contracts.py --db <路径> --yes   # 真写
"""
import argparse
import os
import sqlite3
import sys
from datetime import datetime

from openpyxl import load_workbook

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

XLSX = r"D:\xwechat_files\wxid_3n26fsqaxc6j22_db19\msg\file\2026-11\合同签订开票情况.xlsx"

COLS = {
    'seq': 1, 'unit': 2, 'name': 3, 'start': 4, 'end': 5, 'contact': 6,
    'party_signed': 7, 'group_invoiced': 8,
    'back_group': 9, 'back_us': 10,
    'vendor': 11, 'vendor_contact': 12, 'vendor_phone': 13,
    'vendor_signed': 14, 'vendor_invoiced': 15, 'vendor_paid': 16,
}

EMPTY = {'', '/', '—', '-', '无'}


def clean(v):
    if v is None:
        return ''
    s = str(v).replace('\n', ' ').replace('\r', ' ').strip()
    return '' if s in EMPTY else s


def norm_date(v):
    s = clean(v)
    if not s:
        return ''
    s = s.replace('.', '-').replace('/', '-').replace('年', '-').replace('月', '-')
    s = s.replace('日', '').strip('-')
    parts = [p for p in s.split('-') if p]
    if len(parts) >= 3:
        try:
            y, m, d = int(parts[0]), int(parts[1]), int(parts[2])
            return '%04d-%02d-%02d' % (y, m, d)
        except ValueError:
            return ''
    return ''


def read_rows():
    wb = load_workbook(XLSX, data_only=True)
    ws = wb['Sheet1']
    out = []
    unit = ''
    for r in range(2, ws.max_row + 1):
        row = {k: ws.cell(row=r, column=c).value for k, c in COLS.items()}
        u = clean(row['unit'])
        if u:
            unit = u
        name = clean(row['name'])
        if not name:
            continue
        out.append({
            'row': r,
            'unit': unit,
            'name': name,
            'start_date': norm_date(row['start']),
            'end_date': norm_date(row['end']),
            'contact': clean(row['contact']),
            'party_signed': clean(row['party_signed']),
            'group_invoiced': clean(row['group_invoiced']),
            'back_group': clean(row['back_group']),
            'back_us': clean(row['back_us']),
            'vendor': clean(row['vendor']),
            'vendor_contact': clean(row['vendor_contact']),
            'vendor_phone': clean(row['vendor_phone']),
            'vendor_signed': clean(row['vendor_signed']),
            'vendor_invoiced': clean(row['vendor_invoiced']),
            'vendor_paid': clean(row['vendor_paid']),
        })
    return out


def pct_or_money(v):
    """表里写 0.5 是指 50%，写具体金额就原样"""
    s = clean(v)
    if not s:
        return ''
    try:
        f = float(s)
    except ValueError:
        return s
    if 0 < f <= 1:
        return '%d%%' % round(f * 100)
    return s


def build_plan(rows):
    plan = []
    for x in rows:
        # 项目备注只放甲方联系人，别再和合同备注重复
        project_note = ('甲方联系人：' + x['contact']) if x['contact'] else ''

        flow = []
        if x['group_invoiced']:
            flow.append('数据集团开票：' + x['group_invoiced'])
        if x['back_group']:
            flow.append('回款到集团：' + pct_or_money(x['back_group']))
        if x['back_us']:
            flow.append('回款到人数：' + pct_or_money(x['back_us']))

        contracts = [{
            'kind': 'reference',
            'party_a': x['unit'] or '甲方',
            'party_b': '数据集团',
            'signed': x['party_signed'],
            'note': '',
        }, {
            'kind': 'income',
            'party_a': '数据集团',
            'party_b': '人数（我司）',
            'signed': '',
            'note': '；'.join(flow),
        }]

        if x['vendor']:
            vnote = []
            if x['vendor_contact']:
                vnote.append('联系人：' + x['vendor_contact'])
            if x['vendor_phone']:
                vnote.append(x['vendor_phone'])
            if x['vendor_invoiced']:
                vnote.append('外协开票：' + x['vendor_invoiced'])
            if x['vendor_paid']:
                vnote.append('付款：' + x['vendor_paid'])
            contracts.append({
                'kind': 'cost',
                'party_a': '人数（我司）',
                'party_b': x['vendor'],
                'signed': x['vendor_signed'],
                'note': '；'.join(vnote),
            })

        plan.append({
            'unit': x['unit'],
            'name': x['name'],
            'start_date': x['start_date'],
            'end_date': x['end_date'],
            'note': project_note,
            'contracts': contracts,
            'src_row': x['row'],
        })
    return plan


def dry_run(plan):
    print('共 %d 个项目，%d 份合同' % (
        len(plan), sum(len(p['contracts']) for p in plan)))
    print()
    for p in plan:
        print('[%d] %s｜%s  %s ~ %s' % (p['src_row'], p['unit'] or '（无单位）',
                                        p['name'], p['start_date'] or '?', p['end_date'] or '?'))
        if p['note']:
            print('      项目备注：%s' % p['note'][:80])
        for c in p['contracts']:
            tag = {'income': '收入', 'cost': '外协', 'reference': '关联'}[c['kind']]
            print('      %s：%s → %s  [%s]%s' % (
                tag, c['party_a'], c['party_b'], c['signed'] or '未填',
                ('  ' + c['note'][:50]) if c['note'] else ''))
        print()


def do_import(plan, db):
    conn = sqlite3.connect(db)
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    n_p = n_c = 0
    for p in plan:
        conn.execute(
            'INSERT INTO projects (code, name, unit, start_date, end_date, owner_id, '
            'note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)',
            (None, p['name'], p['unit'] or None, p['start_date'] or None,
             p['end_date'] or None, 1, p['note'] or None, now, now))
        pid = conn.execute('SELECT last_insert_rowid()').fetchone()[0]
        n_p += 1
        for c in p['contracts']:
            conn.execute(
                'INSERT INTO contracts (project_id, kind, party_a, party_b, contract_no, '
                'amount, signed, signed_at, file_rel, file_name, note, sort, '
                'created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (pid, c['kind'], c['party_a'], c['party_b'], None, None,
                 c['signed'] or None, None, None, None, c['note'] or None, 0, now, now))
            n_c += 1
    conn.commit()
    conn.close()
    print('已导入 %d 个项目、%d 份合同' % (n_p, n_c))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--db', default='')
    ap.add_argument('--yes', action='store_true')
    ap.add_argument('--export-json', default='', help='把计划导出成 JSON，供服务器上用')
    ap.add_argument('--json', default='', help='从 JSON 读计划（服务器上没有 Excel 时用）')
    args = ap.parse_args()

    if args.json:
        import json as _json
        with open(args.json, 'r', encoding='utf-8') as fh:
            plan = _json.load(fh)
        print('从 %s 读到 %d 个项目' % (args.json, len(plan)))
    else:
        if not os.path.isfile(XLSX):
            print('找不到 Excel：%s' % XLSX)
            sys.exit(1)
        rows = read_rows()
        plan = build_plan(rows)

    if args.export_json:
        import json as _json
        with open(args.export_json, 'w', encoding='utf-8') as fh:
            _json.dump(plan, fh, ensure_ascii=False, indent=1)
        print('计划已导出：%s' % args.export_json)
        return

    dry_run(plan)

    if not args.yes:
        print('（预演结束，加 --yes 才会写入）')
        return
    if not args.db:
        print('真写要指定 --db')
        sys.exit(1)
    do_import(plan, args.db)


if __name__ == '__main__':
    main()
