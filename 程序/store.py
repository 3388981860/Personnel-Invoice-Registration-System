# -*- coding: utf-8 -*-
"""
数据层
------
职责：
    1. SQLite 主库（发票台账.db）—— 发票号码唯一，从数据库层面堵死重复登记
    2. 发票原件归档（发票存档\\年\\月\\发票号码.pdf）
    3. 自动同步一份 Excel（发票台账.xlsx），随时能直接打开查看
    4. 每日自动备份；删除的记录连原件一起移入「已删除」而不是真删
"""
import os
import re
import shutil
import sqlite3
import datetime

COLUMNS = [
    ("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
    ("invoice_no", "TEXT NOT NULL UNIQUE"),
    ("invoice_date", "TEXT"),
    ("amount", "REAL"),
    ("amount_net", "REAL"),
    ("tax", "REAL"),
    ("seller", "TEXT"),
    ("buyer", "TEXT"),
    ("category", "TEXT"),
    ("person", "TEXT"),
    ("payee", "TEXT"),
    ("reimburse_date", "TEXT"),
    ("note", "TEXT"),
    ("file_rel", "TEXT"),
    ("file_name", "TEXT"),
    ("source", "TEXT"),
    ("status", "INTEGER DEFAULT 0"),
    ("paid_at", "TEXT"),
    ("batch_id", "INTEGER"),
    ("created_at", "TEXT"),
    ("updated_at", "TEXT"),
]

# 需要自动补齐的列（老数据库升级时用）
MIGRATIONS = [
    ("status", "INTEGER DEFAULT 0"),
    ("paid_at", "TEXT"),
    ("payee", "TEXT"),
    ("batch_id", "INTEGER"),
]

# 报销单
BATCH_COLUMNS = [
    ("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
    ("batch_no", "TEXT NOT NULL UNIQUE"),
    ("note", "TEXT"),
    ("created_at", "TEXT"),
    ("updated_at", "TEXT"),
]

FIELD_NAMES = [c[0] for c in COLUMNS if c[0] != "id"]

# 发票表字段 + 从报销单带出来的单号
SELECT_INVOICE = (
    "SELECT i.*, b.batch_no AS batch_no FROM invoices i "
    "LEFT JOIN batches b ON b.id = i.batch_id"
)

EXCEL_HEADERS = [
    ("batch_no", "报销单号"),
    ("invoice_no", "发票号码"),
    ("invoice_date", "开票日期"),
    ("amount", "金额(价税合计)"),
    ("amount_net", "不含税金额"),
    ("tax", "税额"),
    ("seller", "销售方"),
    ("buyer", "购买方"),
    ("category", "项目"),
    ("person", "报销人"),
    ("payee", "收款人"),
    ("reimburse_date", "报销日期"),
    ("status", "报销状态"),
    ("paid_at", "报销确认时间"),
    ("note", "备注"),
    ("created_at", "登记时间"),
]


class DuplicateError(Exception):
    def __init__(self, record):
        super().__init__("发票号码已登记")
        self.record = record


def now_str():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class Store(object):
    def __init__(self, base):
        self.base = base
        self.data_dir = os.path.join(base, "数据")
        self.archive_dir = os.path.join(base, "发票存档")
        self.tmp_dir = os.path.join(self.data_dir, "临时")
        self.backup_dir = os.path.join(self.data_dir, "备份")
        self.deleted_dir = os.path.join(self.data_dir, "已删除")
        self.db_path = os.path.join(self.data_dir, "发票台账.db")
        self.xlsx_path = os.path.join(self.data_dir, "发票台账.xlsx")
        for d in (self.data_dir, self.archive_dir, self.tmp_dir,
                  self.backup_dir, self.deleted_dir):
            os.makedirs(d, exist_ok=True)
        self._clean_tmp()
        self._init_db()
        self.backup_daily()
        self.sync_excel()

    def _clean_tmp(self):
        """清掉上次没来得及处理的临时文件（超过 1 天的）。"""
        import time
        now = time.time()
        try:
            for name in os.listdir(self.tmp_dir):
                p = os.path.join(self.tmp_dir, name)
                try:
                    if os.path.isfile(p) and now - os.path.getmtime(p) > 86400:
                        os.remove(p)
                except OSError:
                    pass
        except OSError:
            pass

    # ------------------------------------------------------------------
    # 基础
    # ------------------------------------------------------------------
    def _conn(self):
        conn = sqlite3.connect(self.db_path, timeout=15)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._conn() as conn:
            conn.execute(
                "CREATE TABLE IF NOT EXISTS invoices (%s)"
                % ", ".join("%s %s" % (n, t) for n, t in COLUMNS)
            )
            # 老版本数据库自动补列（同事那边已有数据也能平滑升级）
            have = {r[1] for r in conn.execute("PRAGMA table_info(invoices)").fetchall()}
            for name, ddl in MIGRATIONS:
                if name not in have:
                    conn.execute("ALTER TABLE invoices ADD COLUMN %s %s" % (name, ddl))
            conn.execute("UPDATE invoices SET status = 0 WHERE status IS NULL")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_inv_no ON invoices(invoice_no)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_person ON invoices(person)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_date ON invoices(invoice_date)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_batch ON invoices(batch_id)")
            conn.execute(
                "CREATE TABLE IF NOT EXISTS persons ("
                "name TEXT PRIMARY KEY, sort INTEGER DEFAULT 0)"
            )
            # 报销单表（老库自动建）
            conn.execute(
                "CREATE TABLE IF NOT EXISTS batches (%s)"
                % ", ".join("%s %s" % (n, t) for n, t in BATCH_COLUMNS)
            )
            conn.execute("CREATE INDEX IF NOT EXISTS idx_batch_no ON batches(batch_no)")

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------
    def get_by_no(self, invoice_no):
        if not invoice_no:
            return None
        with self._conn() as conn:
            row = conn.execute(
                SELECT_INVOICE + " WHERE i.invoice_no = ?", (invoice_no,)
            ).fetchone()
        return dict(row) if row else None

    def get(self, rid):
        with self._conn() as conn:
            row = conn.execute(SELECT_INVOICE + " WHERE i.id = ?", (rid,)).fetchone()
        return dict(row) if row else None

    def list(self, q=None, person=None, month=None, status=None, batch_id=None,
             sort="invoice_date", desc=True, limit=5000):
        where, args = [], []
        if q:
            like = "%" + q.strip() + "%"
            where.append(
                "(i.invoice_no LIKE ? OR i.seller LIKE ? OR i.person LIKE ? OR i.payee LIKE ? "
                "OR i.note LIKE ? OR i.category LIKE ? OR i.file_name LIKE ? "
                "OR b.batch_no LIKE ?)"
            )
            args.extend([like] * 8)
        if person:
            where.append("i.person = ?")
            args.append(person)
        if month:
            where.append("substr(COALESCE(i.invoice_date, i.created_at), 1, 7) = ?")
            args.append(month)
        if status in (0, 1, "0", "1"):
            where.append("COALESCE(i.status, 0) = ?")
            args.append(int(status))
        if batch_id not in (None, "", "all"):
            if str(batch_id) == "none":
                where.append("i.batch_id IS NULL")
            else:
                where.append("i.batch_id = ?")
                args.append(int(batch_id))
        sql = SELECT_INVOICE
        if where:
            sql += " WHERE " + " AND ".join(where)
        allowed = {"invoice_date", "amount", "created_at", "invoice_no",
                   "person", "status", "batch_no"}
        sort = sort if sort in allowed else "invoice_date"
        col = "b.batch_no" if sort == "batch_no" else ("i." + sort)
        sql += " ORDER BY COALESCE(%s,'') %s, i.id %s" % (col, "DESC" if desc else "ASC",
                                                          "DESC" if desc else "ASC")
        sql += " LIMIT ?"
        args.append(limit)
        with self._conn() as conn:
            rows = [dict(r) for r in conn.execute(sql, args).fetchall()]
        return rows

    def stats(self):
        today = datetime.date.today()
        month = today.strftime("%Y-%m")
        year = today.strftime("%Y")
        out = {}
        with self._conn() as conn:
            for key, pat in (("month", month), ("year", year)):
                row = conn.execute(
                    "SELECT COUNT(*) c, COALESCE(SUM(amount),0) s FROM invoices "
                    "WHERE substr(COALESCE(invoice_date, created_at),1,%d) = ?"
                    % len(pat), (pat,)
                ).fetchone()
                out[key] = {"count": row["c"], "amount": round(row["s"], 2)}
            row = conn.execute(
                "SELECT COUNT(*) c, COALESCE(SUM(amount),0) s FROM invoices"
            ).fetchone()
            out["total"] = {"count": row["c"], "amount": round(row["s"], 2)}
            for key, val in (("unpaid", 0), ("paid", 1)):
                row = conn.execute(
                    "SELECT COUNT(*) c, COALESCE(SUM(amount),0) s FROM invoices "
                    "WHERE COALESCE(status,0) = ?", (val,)
                ).fetchone()
                out[key] = {"count": row["c"], "amount": round(row["s"], 2)}
            # 报销单维度
            row = conn.execute(
                "SELECT COUNT(*) c FROM ("
                "  SELECT b.id FROM batches b JOIN invoices i ON i.batch_id = b.id "
                "  GROUP BY b.id "
                "  HAVING COALESCE(SUM(COALESCE(i.status,0)),0) < COUNT(i.id))"
            ).fetchone()
            out["unpaid_batches"] = row["c"]
            row = conn.execute(
                "SELECT COUNT(*) c FROM ("
                "  SELECT b.id FROM batches b JOIN invoices i ON i.batch_id = b.id "
                "  GROUP BY b.id)"
            ).fetchone()
            out["batches"] = row["c"]
        return out

    def set_status(self, rid, status):
        """切换报销状态：0=未报销，1=已报销（记下确认时间）。"""
        status = 1 if int(status) else 0
        stamp = now_str()
        with self._conn() as conn:
            conn.execute(
                "UPDATE invoices SET status = ?, paid_at = ?, updated_at = ? WHERE id = ?",
                (status, stamp if status else None, stamp, rid)
            )
        self.sync_excel()
        return self.get(rid)

    def persons(self):
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT name FROM persons "
                "UNION SELECT DISTINCT person FROM invoices WHERE person IS NOT NULL AND person <> '' "
                "ORDER BY 1"
            ).fetchall()
        return [r[0] for r in rows if r[0]]

    def add_person(self, name):
        name = (name or "").strip()
        if not name:
            return
        with self._conn() as conn:
            conn.execute("INSERT OR IGNORE INTO persons(name) VALUES (?)", (name,))

    def months(self):
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT DISTINCT substr(COALESCE(invoice_date, created_at),1,7) m "
                "FROM invoices WHERE m IS NOT NULL AND m <> '' ORDER BY m DESC"
            ).fetchall()
        return [r[0] for r in rows]

    # ------------------------------------------------------------------
    # 报销单
    # ------------------------------------------------------------------
    def next_batch_no(self, day=None):
        """生成报销单号：BX + 日期 + 序号，例如 BX20261021-001"""
        day = day or datetime.date.today().strftime("%Y%m%d")
        prefix = "BX" + day + "-"
        with self._conn() as conn:
            row = conn.execute(
                "SELECT batch_no FROM batches WHERE batch_no LIKE ? "
                "ORDER BY batch_no DESC LIMIT 1", (prefix + "%",)
            ).fetchone()
        n = 1
        if row:
            try:
                n = int(str(row["batch_no"]).rsplit("-", 1)[1]) + 1
            except (ValueError, IndexError):
                n = 1
        return "%s%03d" % (prefix, n)

    def create_batch(self, batch_no=None, note=None):
        """新建一张报销单。单号可指定，重复会自动加后缀。"""
        want = (batch_no or "").strip()
        if not want:
            for _ in range(60):
                try:
                    with self._conn() as conn:
                        cur = conn.execute(
                            "INSERT INTO batches (batch_no, note, created_at, updated_at) "
                            "VALUES (?,?,?,?)",
                            (self.next_batch_no(), note, now_str(), now_str()))
                        return cur.lastrowid
                except sqlite3.IntegrityError:
                    continue
            raise ValueError("报销单号生成失败，请手工指定一个")
        for i in range(60):
            no = want if i == 0 else "%s-%d" % (want, i + 1)
            try:
                with self._conn() as conn:
                    cur = conn.execute(
                        "INSERT INTO batches (batch_no, note, created_at, updated_at) "
                        "VALUES (?,?,?,?)", (no, note, now_str(), now_str()))
                    return cur.lastrowid
            except sqlite3.IntegrityError:
                continue
        raise ValueError("报销单号 %s 已被占用" % want)

    def get_batch(self, bid):
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM batches WHERE id = ?", (bid,)).fetchone()
        return dict(row) if row else None

    def batch_detail(self, bid):
        """报销单详情：单头 + 单内全部发票 + 合计 + 汇总状态。"""
        b = self.get_batch(bid)
        if not b:
            return None
        with self._conn() as conn:
            invs = [dict(r) for r in conn.execute(
                SELECT_INVOICE + " WHERE i.batch_id = ? ORDER BY i.id", (bid,)).fetchall()]
        b["invoices"] = invs
        b["count"] = len(invs)
        b["amount"] = round(sum(float(i.get("amount") or 0) for i in invs), 2)
        paid = [i for i in invs if int(i.get("status") or 0) == 1]
        b["paid_count"] = len(paid)
        # 1=整单已报销  0=整单未报销  -1=部分已报销
        b["status"] = 1 if (invs and len(paid) == len(invs)) else (0 if not paid else -1)
        first = invs[0] if invs else {}
        b["person"] = first.get("person")
        b["payee"] = first.get("payee")
        b["reimburse_date"] = first.get("reimburse_date")
        b["paid_at"] = first.get("paid_at")
        return b

    def list_batches(self, q=None, status=None, person=None, limit=2000):
        sql = ("SELECT b.*, COUNT(i.id) AS count, COALESCE(SUM(i.amount),0) AS amount, "
               "COALESCE(SUM(COALESCE(i.status,0)),0) AS paid_count, "
               "MIN(i.person) AS person, MIN(i.payee) AS payee, "
               "MIN(i.reimburse_date) AS reimburse_date "
               "FROM batches b LEFT JOIN invoices i ON i.batch_id = b.id")
        where, args = [], []
        if q:
            like = "%" + q.strip() + "%"
            where.append("(b.batch_no LIKE ? OR b.note LIKE ? OR i.person LIKE ? OR i.payee LIKE ?)")
            args.extend([like] * 4)
        if person:
            where.append("i.person = ?")
            args.append(person)
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " GROUP BY b.id ORDER BY b.id DESC LIMIT ?"
        args.append(limit)
        with self._conn() as conn:
            rows = [dict(r) for r in conn.execute(sql, args).fetchall()]
        out = []
        for r in rows:
            n, p = int(r.get("count") or 0), int(r.get("paid_count") or 0)
            r["status"] = 1 if (n and p == n) else (0 if not p else -1)
            r["amount"] = round(float(r.get("amount") or 0), 2)
            if status in (0, 1, "0", "1") and r["status"] != int(status):
                continue
            out.append(r)
        return out

    def set_batch_status(self, bid, status):
        """整单标记已报销 / 未报销"""
        status = 1 if int(status) else 0
        stamp = now_str()
        with self._conn() as conn:
            conn.execute(
                "UPDATE invoices SET status = ?, paid_at = ?, updated_at = ? WHERE batch_id = ?",
                (status, stamp if status else None, stamp, bid))
        self.sync_excel()
        return self.batch_detail(bid)

    def update_batch(self, bid, data):
        """改报销单：单号/备注改单，报销人/收款人/报销日期会同步到单内所有发票"""
        b = {}
        if "batch_no" in data and str(data.get("batch_no") or "").strip():
            b["batch_no"] = str(data["batch_no"]).strip()
            with self._conn() as conn:
                row = conn.execute("SELECT id FROM batches WHERE batch_no = ? AND id <> ?",
                                   (b["batch_no"], bid)).fetchone()
            if row:
                raise DuplicateError({"batch_no": b["batch_no"]})
        if "note" in data:
            b["note"] = data["note"]
        if b:
            b["updated_at"] = now_str()
            with self._conn() as conn:
                conn.execute(
                    "UPDATE batches SET %s WHERE id = ?" % ", ".join("%s = ?" % k for k in b),
                    list(b.values()) + [bid])
        inv = {}
        for k in ("person", "payee", "reimburse_date"):
            if k in data:
                inv[k] = data[k]
        if inv:
            inv["updated_at"] = now_str()
            with self._conn() as conn:
                conn.execute(
                    "UPDATE invoices SET %s WHERE batch_id = ?" % ", ".join("%s = ?" % k for k in inv),
                    list(inv.values()) + [bid])
        if inv.get("person"):
            self.add_person(inv["person"])
        self.sync_excel()
        return self.batch_detail(bid)

    def add_batch(self, items, batch_no=None, note=None, person=None, payee=None,
                  reimburse_date=None):
        """
        一次性登记多张发票并归入同一张报销单。
        items: [(字段字典, 临时文件路径), ...]
        先全部校验，任何一张有问题就整批不落库。
        """
        seen, clean = set(), []
        for data, tmp_file in items:
            no = re.sub(r"\D", "", str(data.get("invoice_no") or ""))
            if not no:
                raise ValueError("有发票号码是空的，请补上再提交")
            if no in seen:
                raise DuplicateError({"invoice_no": no, "in_batch": True})
            seen.add(no)
            exist = self.get_by_no(no)
            if exist:
                raise DuplicateError(exist)
            clean.append((no, data, tmp_file))

        bid = self.create_batch(batch_no, note)
        ids = []
        for no, data, tmp_file in clean:
            d = dict(data)
            d["invoice_no"] = no
            d["batch_id"] = bid
            if person:
                d["person"] = person
            if payee:
                d["payee"] = payee
            if reimburse_date:
                d["reimburse_date"] = reimburse_date
            ids.append(self.add(d, tmp_file=tmp_file, quiet=True))
        self.sync_excel()
        return bid, ids

    def attach_to_batch(self, invoice_ids, batch_id=None, batch_no=None, note=None):
        """把已登记的发票补挂到某张报销单；不指定就新建一张。"""
        ids = [int(x) for x in (invoice_ids or [])]
        if not ids:
            raise ValueError("没有选中任何发票")
        old_batches = set()
        with self._conn() as conn:
            q = ",".join("?" for _ in ids)
            for r in conn.execute(
                    "SELECT DISTINCT batch_id FROM invoices WHERE id IN (%s)" % q, ids):
                if r["batch_id"]:
                    old_batches.add(int(r["batch_id"]))
        if not batch_id:
            batch_id = self.create_batch(batch_no, note)
        else:
            batch_id = int(batch_id)
            if not self.get_batch(batch_id):
                raise ValueError("找不到这张报销单")

        # 挂到已有单上：跟随该单的报销人/收款人/报销日期
        shared = {}
        with self._conn() as conn:
            first = conn.execute(
                "SELECT person, payee, reimburse_date FROM invoices "
                "WHERE batch_id = ? ORDER BY id LIMIT 1", (batch_id,)).fetchone()
        if first:
            shared = {k: first[k] for k in ("person", "payee", "reimburse_date") if first[k]}

        sets, args = ["batch_id = ?"], [batch_id]
        for k, v in shared.items():
            sets.append("%s = ?" % k)
            args.append(v)
        sets.append("updated_at = ?")
        args.append(now_str())
        with self._conn() as conn:
            conn.execute("UPDATE invoices SET %s WHERE id IN (%s)"
                         % (", ".join(sets), ",".join("?" for _ in ids)), args + ids)
        self._prune_batches(old_batches - {batch_id})
        self.sync_excel()
        return self.batch_detail(batch_id)

    def detach(self, invoice_ids):
        """把发票从报销单里拿出来（单独成票）"""
        ids = [int(x) for x in (invoice_ids or [])]
        if not ids:
            raise ValueError("没有选中任何发票")
        old = set()
        with self._conn() as conn:
            q = ",".join("?" for _ in ids)
            for r in conn.execute("SELECT DISTINCT batch_id FROM invoices WHERE id IN (%s)" % q, ids):
                if r["batch_id"]:
                    old.add(int(r["batch_id"]))
            conn.execute("UPDATE invoices SET batch_id = NULL, updated_at = ? WHERE id IN (%s)"
                         % q, [now_str()] + ids)
        self._prune_batches(old)
        self.sync_excel()

    def _prune_batches(self, batch_ids):
        """把已经空掉的报销单删掉"""
        for bid in batch_ids or []:
            with self._conn() as conn:
                n = conn.execute("SELECT COUNT(*) c FROM invoices WHERE batch_id = ?",
                                 (bid,)).fetchone()["c"]
                if not n:
                    conn.execute("DELETE FROM batches WHERE id = ?", (bid,))

    # ------------------------------------------------------------------
    # 写入
    # ------------------------------------------------------------------
    def add(self, data, tmp_file=None, quiet=False):
        invoice_no = re.sub(r"\D", "", str(data.get("invoice_no") or ""))
        if not invoice_no:
            raise ValueError("发票号码不能为空")
        exist = self.get_by_no(invoice_no)
        if exist:
            raise DuplicateError(exist)

        file_rel, file_name = None, data.get("file_name")
        if tmp_file and os.path.exists(tmp_file):
            file_rel = self.archive_file(tmp_file, invoice_no, data.get("invoice_date"))
            try:
                os.remove(tmp_file)          # 归档完就清掉临时文件，别在里面越堆越多
            except OSError:
                pass

        rec = {k: data.get(k) for k in FIELD_NAMES}
        rec["invoice_no"] = invoice_no
        rec["file_rel"] = file_rel
        rec["file_name"] = file_name
        rec["status"] = int(data.get("status") or 0)   # 新登记的默认「未报销」
        rec["paid_at"] = data.get("paid_at")
        rec["created_at"] = now_str()
        rec["updated_at"] = rec["created_at"]
        cols = [k for k in FIELD_NAMES if k in rec]
        sql = "INSERT INTO invoices (%s) VALUES (%s)" % (
            ", ".join(cols), ", ".join("?" for _ in cols)
        )
        with self._conn() as conn:
            cur = conn.execute(sql, [rec[c] for c in cols])
            rid = cur.lastrowid
        if rec.get("person"):
            self.add_person(rec["person"])
        if not quiet:
            self.sync_excel()
        return rid

    def update(self, rid, data):
        rec = {k: data.get(k) for k in FIELD_NAMES if k in data}
        if "invoice_no" in rec:
            no = re.sub(r"\D", "", str(rec["invoice_no"] or ""))
            if not no:
                rec.pop("invoice_no")       # 号码不允许被清空
            else:
                other = self.get_by_no(no)
                if other and other["id"] != rid:
                    raise DuplicateError(other)   # 改成别人已经用过的号码，直接拦下
                rec["invoice_no"] = no
        rec["updated_at"] = now_str()
        if not rec:
            return
        sql = "UPDATE invoices SET %s WHERE id = ?" % ", ".join("%s = ?" % k for k in rec)
        with self._conn() as conn:
            conn.execute(sql, list(rec.values()) + [rid])
        if rec.get("person"):
            self.add_person(rec["person"])
        self.sync_excel()

    def delete(self, rid):
        rec = self.get(rid)
        if not rec:
            return
        # 原件移入「已删除」，不做真删除
        if rec.get("file_rel"):
            src = os.path.join(self.base, rec["file_rel"])
            if os.path.exists(src):
                stamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
                dst = os.path.join(self.deleted_dir, "%s_%s" % (stamp, os.path.basename(src)))
                try:
                    shutil.move(src, dst)
                except Exception:
                    pass
        with self._conn() as conn:
            conn.execute("DELETE FROM invoices WHERE id = ?", (rid,))
        if rec.get("batch_id"):
            self._prune_batches([int(rec["batch_id"])])
        self.sync_excel()

    # ------------------------------------------------------------------
    # 原件归档
    # ------------------------------------------------------------------
    def archive_file(self, tmp_path, invoice_no, invoice_date):
        """把原件复制到 发票存档\\年\\月\\发票号码.ext，返回相对路径。"""
        ext = os.path.splitext(tmp_path)[1].lower() or ".pdf"
        year, month = None, None
        if invoice_date and re.match(r"^\d{4}-\d{2}", str(invoice_date)):
            year, month = str(invoice_date)[:4], str(invoice_date)[5:7]
        if not year:
            today = datetime.date.today()
            year, month = today.strftime("%Y"), today.strftime("%m")
        folder = os.path.join(self.archive_dir, year, month)
        os.makedirs(folder, exist_ok=True)
        target = os.path.join(folder, invoice_no + ext)
        n = 2
        while os.path.exists(target):
            # 同号同月已存在：内容相同就复用，不同则加后缀
            try:
                if os.path.getsize(target) == os.path.getsize(tmp_path):
                    return os.path.relpath(target, self.base)
            except OSError:
                pass
            target = os.path.join(folder, "%s_%d%s" % (invoice_no, n, ext))
            n += 1
        shutil.copy2(tmp_path, target)
        return os.path.relpath(target, self.base)

    # ------------------------------------------------------------------
    # Excel 同步 / 导出
    # ------------------------------------------------------------------
    def sync_excel(self, dest=None):
        dest = dest or self.xlsx_path
        try:
            from openpyxl import Workbook
            from openpyxl.styles import Font, Alignment, PatternFill
            from openpyxl.utils import get_column_letter
        except ImportError:
            return
        rows = self.list(sort="created_at", desc=False, limit=100000)
        wb = Workbook()
        ws = wb.active
        ws.title = "发票台账"
        head_font = Font(bold=True, color="FFFFFF", size=11)
        head_fill = PatternFill("solid", fgColor="2F5597")
        for c, (_, title) in enumerate(EXCEL_HEADERS, start=1):
            cell = ws.cell(row=1, column=c, value=title)
            cell.font = head_font
            cell.fill = head_fill
            cell.alignment = Alignment(horizontal="center", vertical="center")
        for r, rec in enumerate(rows, start=2):
            for c, (key, _) in enumerate(EXCEL_HEADERS, start=1):
                v = rec.get(key)
                if key == "status":
                    v = "已报销" if v else "未报销"
                ws.cell(row=r, column=c, value=v)
                if key in ("amount", "amount_net", "tax") and v is not None:
                    ws.cell(row=r, column=c).number_format = "#,##0.00"
        widths = [18, 24, 12, 15, 13, 10, 32, 26, 22, 10, 10, 12, 10, 20, 26, 20]
        for i, w in enumerate(widths, start=1):
            ws.column_dimensions[get_column_letter(i)].width = w
        ws.freeze_panes = "A2"
        if rows:
            ws.auto_filter.ref = "A1:%s%d" % (get_column_letter(len(EXCEL_HEADERS)), len(rows) + 1)
        try:
            wb.save(dest)
        except PermissionError:
            # 文件被 Excel 打开着，退回到备份目录
            alt = os.path.join(self.data_dir, "发票台账_自动副本.xlsx")
            try:
                wb.save(alt)
            except Exception:
                pass

    # ------------------------------------------------------------------
    # 备份
    # ------------------------------------------------------------------
    def backup_daily(self):
        if not os.path.exists(self.db_path):
            return
        stamp = datetime.date.today().strftime("%Y%m%d")
        dst = os.path.join(self.backup_dir, "发票台账_%s.db" % stamp)
        if os.path.exists(dst):
            return
        try:
            shutil.copy2(self.db_path, dst)
        except Exception:
            pass
