package com.xinyu.invoice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 启动时补齐新增的表和字段。
 *
 * 数据库是 Python 版建的，Java 版后面加了东西，不能让用户手工执行 SQL。
 * 这里全部用 IF NOT EXISTS / 先查再建，跑多少遍都安全。
 */
@Component
public class SchemaMigrator implements ApplicationRunner {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.error("数据库结构检查失败", e);
        }
    }

    private void migrate() {
        // 一张报销单可以付给多个人
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS batch_payees (
                  id         INTEGER PRIMARY KEY AUTOINCREMENT,
                  batch_id   INTEGER NOT NULL,
                  name       TEXT    NOT NULL,
                  amount     REAL    NOT NULL DEFAULT 0,
                  note       TEXT,
                  sort       INTEGER DEFAULT 0,
                  created_at TEXT
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_payees_batch "
                + "ON batch_payees(batch_id)");

        // ---------------------------------------------------------------- 合同管理
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                  id         INTEGER PRIMARY KEY AUTOINCREMENT,
                  code       TEXT,
                  name       TEXT NOT NULL,
                  unit       TEXT,
                  start_date TEXT,
                  end_date   TEXT,
                  owner_id   INTEGER,
                  note       TEXT,
                  created_at TEXT,
                  updated_at TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS contracts (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  project_id  INTEGER NOT NULL,
                  kind        TEXT NOT NULL DEFAULT 'income',
                  party_a     TEXT,
                  party_b     TEXT,
                  contract_no TEXT,
                  amount      REAL,
                  signed      TEXT,
                  signed_at   TEXT,
                  file_rel    TEXT,
                  file_name   TEXT,
                  note        TEXT,
                  sort        INTEGER DEFAULT 0,
                  created_at  TEXT,
                  updated_at  TEXT
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_contracts_project "
                + "ON contracts(project_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS contract_billings (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  contract_id INTEGER NOT NULL,
                  kind        TEXT NOT NULL,
                  stage       TEXT,
                  amount      REAL,
                  ratio       REAL,
                  no          TEXT,
                  happened_at TEXT,
                  file_rel    TEXT,
                  note        TEXT,
                  created_at  TEXT
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_billings_contract "
                + "ON contract_billings(contract_id)");


        // 用户部门（从钉钉通讯录同步过来）
        try {
            jdbc.queryForList("SELECT dept_name FROM users LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE users ADD COLUMN dept_name TEXT");
            jdbc.execute("ALTER TABLE users ADD COLUMN dept_ids TEXT");
            jdbc.execute("ALTER TABLE users ADD COLUMN dingtalk_title TEXT");
            log.info("users 表已补部门相关列");
        }

        // 报销单上的部门 / 项目（来自数据字典，可留空）
        try {
            jdbc.queryForList("SELECT dept_name FROM batches LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE batches ADD COLUMN dept_name TEXT");
            jdbc.execute("ALTER TABLE batches ADD COLUMN project_name TEXT");
            log.info("batches 表已补 dept_name / project_name 列");
        }

        // 数据字典：报销部门、报销项目
        jdbc.execute("CREATE TABLE IF NOT EXISTS dict_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "type TEXT NOT NULL,"
                + "name TEXT NOT NULL,"
                + "sort_no INTEGER DEFAULT 0,"
                + "enabled INTEGER DEFAULT 1,"
                + "created_at TEXT)");

        // 免考勤人员：这些人不参与统计、也不收未打卡提醒
        jdbc.execute("CREATE TABLE IF NOT EXISTS attendance_exempt ("
                + "dt_userid TEXT PRIMARY KEY,"
                + "name TEXT,"
                + "note TEXT,"
                + "created_at TEXT)");

        // 工作日日历：法定节假日、调休上班、公司自己标的日子
        jdbc.execute("CREATE TABLE IF NOT EXISTS workday_calendar ("
                + "work_date TEXT PRIMARY KEY,"
                + "kind TEXT NOT NULL,"
                + "name TEXT,"
                + "source TEXT,"
                + "updated_at TEXT)");

        // 考勤（每天每人一行，从钉钉拉取后缓存）
        jdbc.execute("CREATE TABLE IF NOT EXISTS attendance_daily ("
                + "work_date TEXT NOT NULL,"
                + "dt_userid TEXT NOT NULL,"
                + "name TEXT,"
                + "on_duty TEXT,"
                + "off_duty TEXT,"
                + "on_result TEXT,"
                + "off_result TEXT,"
                + "address TEXT,"
                + "has_leave INTEGER DEFAULT 0,"
                + "updated_at TEXT,"
                + "PRIMARY KEY (work_date, dt_userid))");

        // 考勤缓存补列（建表之后才能补，且失败不影响后续迁移）
        try {
            jdbc.queryForList("SELECT has_fix FROM attendance_daily LIMIT 1");
        } catch (Exception e) {
            try {
                jdbc.execute("ALTER TABLE attendance_daily ADD COLUMN has_fix INTEGER DEFAULT 0");
                jdbc.execute("ALTER TABLE attendance_daily ADD COLUMN approval_note TEXT");
                log.info("attendance_daily 已补 has_fix / approval_note 列");
            } catch (Exception e2) {
                log.warn("补考勤列失败：" + e2.getMessage());
            }
        }

        // 钉钉账号绑定
        try {
            jdbc.queryForList("SELECT dingtalk_userid FROM users LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE users ADD COLUMN dingtalk_userid TEXT");
            log.info("users 表已补 dingtalk_userid 列");
        }
        try {
            jdbc.queryForList("SELECT dingtalk_union_id FROM users LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE users ADD COLUMN dingtalk_union_id TEXT");
            log.info("users 表已补 dingtalk_union_id 列");
        }

        // 项目集（program）：一批项目归到一个集下
        jdbc.execute("CREATE TABLE IF NOT EXISTS project_sets ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "code TEXT,"
                + "name TEXT NOT NULL,"
                + "year INTEGER,"
                + "unit TEXT,"
                + "note TEXT,"
                + "created_at TEXT)");
        try {
            jdbc.queryForList("SELECT set_id FROM projects LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE projects ADD COLUMN set_id INTEGER");
            log.info("projects 表已补 set_id 列");
        }

        // 已有项目但还没有项目集：建一个「2026年集约化」并把它们挂进去
        try {
            Integer setCount = jdbc.queryForObject("SELECT COUNT(*) FROM project_sets", Integer.class);
            Integer projCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM projects WHERE set_id IS NULL", Integer.class);
            if ((setCount == null || setCount == 0) && projCount != null && projCount > 0) {
                String year = String.valueOf(java.time.LocalDate.now().getYear());
                jdbc.update("INSERT INTO project_sets (code, name, year, note, created_at)"
                                + " VALUES (?,?,?,?,?)",
                        "JC-" + year + "-001", year + "年瓯海区集约化运维项目",
                        Integer.parseInt(year), "历史项目自动归集",
                        LocalDateTime.now().format(TS));
                Long setId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
                int n = jdbc.update("UPDATE projects SET set_id = ? WHERE set_id IS NULL", setId);
                log.info("已建项目集「" + year + "年瓯海区集约化运维项目」，归入 " + n + " 个项目");
            }
        } catch (Exception e) {
            log.warn("迁移项目集失败：" + e.getMessage());
        }

        // 项目编号规则 HT-YYYY-序号：把还没编号的老项目补上
        try {
            java.util.List<java.util.Map<String, Object>> noCode =
                    jdbc.queryForList("SELECT id FROM projects WHERE code IS NULL OR code = '' ORDER BY id");
            if (!noCode.isEmpty()) {
                String year = String.valueOf(java.time.LocalDate.now().getYear());
                String prefix = "HT-" + year + "-";
                Integer max = 0;
                for (java.util.Map<String, Object> r : jdbc.queryForList(
                        "SELECT code FROM projects WHERE code LIKE ?", prefix + "%")) {
                    String tail = String.valueOf(r.get("code")).substring(prefix.length());
                    try {
                        max = Math.max(max, Integer.parseInt(tail.replaceAll("[^0-9]", "")));
                    } catch (NumberFormatException ignored) {
                        // 不是标准格式就跳过
                    }
                }
                for (java.util.Map<String, Object> r : noCode) {
                    max = max + 1;
                    jdbc.update("UPDATE projects SET code = ? WHERE id = ?",
                            prefix + String.format("%03d", max), r.get("id"));
                }
                log.info("已给 " + noCode.size() + " 个项目补编号（" + prefix + "001 起）");
            }
        } catch (Exception e) {
            log.warn("补项目编号失败：" + e.getMessage());
        }

        // 公告
        jdbc.execute("CREATE TABLE IF NOT EXISTS announcements ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "created_by INTEGER,"
                + "created_at TEXT)");
        try {
            jdbc.queryForList("SELECT announcement_id FROM notifications LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE notifications ADD COLUMN announcement_id INTEGER");
            log.info("notifications 表已补 announcement_id 列");
        }

        // 工具箱（把外部小工具挂进来）
        jdbc.execute("CREATE TABLE IF NOT EXISTS tools ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "url TEXT,"
                + "description TEXT,"
                + "icon TEXT,"
                + "category TEXT,"
                + "sort_no INTEGER DEFAULT 0,"
                + "enabled INTEGER DEFAULT 1)");
        Integer toolCount = jdbc.queryForObject("SELECT COUNT(*) FROM tools", Integer.class);
        if (toolCount == null || toolCount == 0) {
            jdbc.update("INSERT INTO tools (name, url, description, icon, category, sort_no, enabled)"
                    + " VALUES ('公文助手', '', '公文智能排版工具', '📝', '公文', 10, 1)");
            jdbc.update("INSERT INTO tools (name, url, description, icon, category, sort_no, enabled)"
                    + " VALUES ('PDF 工具箱', '', 'PDF 合并、拆分、转换', '📄', '工具', 20, 1)");
            log.info("工具箱已预置：公文助手 / PDF 工具箱（地址待填）");
        }

        // 用户功能权限（JSON 数组，null = 用默认）
        try {
            jdbc.queryForList("SELECT perms FROM users LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE users ADD COLUMN perms TEXT");
            log.info("users 表已补 perms 列");
        }

        // 报销单关联合同（外协付款单用）
        try {
            jdbc.queryForList("SELECT contract_id FROM batches LIMIT 1");
        } catch (Exception e) {
            jdbc.execute("ALTER TABLE batches ADD COLUMN contract_id INTEGER");
            log.info("batches 表已补 contract_id 列");
        }

        // 老数据：每张单补一条收款记录（金额 = 单合计，收款人 = 发票上的收款人）
        int n = jdbc.update("""
                INSERT INTO batch_payees (batch_id, name, amount, note, sort, created_at)
                SELECT b.id,
                       COALESCE((SELECT MIN(i.payee) FROM invoices i WHERE i.batch_id = b.id), '—'),
                       COALESCE((SELECT SUM(i.amount) FROM invoices i WHERE i.batch_id = b.id), 0),
                       NULL, 0, datetime('now', 'localtime')
                FROM batches b
                WHERE EXISTS (SELECT 1 FROM invoices i WHERE i.batch_id = b.id)
                  AND NOT EXISTS (SELECT 1 FROM batch_payees p WHERE p.batch_id = b.id)
                """);
        if (n > 0) {
            log.info("已为 {} 张历史报销单补建收款记录", n);
        }
    }
}
