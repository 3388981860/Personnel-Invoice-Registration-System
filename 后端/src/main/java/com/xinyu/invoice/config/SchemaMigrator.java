package com.xinyu.invoice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐新增的表和字段。
 *
 * 数据库是 Python 版建的，Java 版后面加了东西，不能让用户手工执行 SQL。
 * 这里全部用 IF NOT EXISTS / 先查再建，跑多少遍都安全。
 */
@Component
public class SchemaMigrator implements ApplicationRunner {

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
