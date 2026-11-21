# 人数发票登记台账 · Spring Boot 后端

替代原来的 Python 后端。**数据库、接口契约、前端都沿用现有的**，
所以可以用「同一个库、同一套接口、同一个前端」逐步切换。

## 环境

| 需要 | 位置 / 版本 |
|---|---|
| JDK | `D:\soft\Java17`（OpenJDK 17.0.2） |
| Maven | 项目自带 `mvnw`，不用单独装（已指向阿里云镜像） |
| 数据库 | 现有的 `数据\发票台账.db`（SQLite，直接用，不导数据） |

## 目录

```
后端/
├── pom.xml                     Spring Boot 3.2.12 + JDBC + sqlite-jdbc
├── mvnw / mvnw.cmd             不用装 Maven 也能构建
├── 打包.bat                     双击 → 生成 target\invoice-server.jar
├── 启动.bat                     双击 → 本地跑起来（端口 8765）
└── src/main/java/com/xinyu/invoice/
    ├── InvoiceApplication.java
    ├── config/AppProps.java           路径、会话、限流等配置
    ├── auth/PasswordTool.java         PBKDF2 密码（与 Python 版完全兼容）
    ├── auth/SessionService.java       会话存 sessions 表，两边共用
    ├── auth/AuthFilter.java           只拦 /api/**
    ├── common/ApiException.java
    ├── common/GlobalExceptionHandler.java
    ├── repo/InvoiceRepo.java          台账查询（SQL 与 store.py 一致）
    ├── repo/StatsRepo.java            统计（同上）
    └── web/
        ├── SpaController.java         /、/login、/ledger… 返回前端入口
        ├── AssetController.java       /assets/** 静态资源
        ├── HealthController.java      /_health、/api/ping
        ├── AuthController.java        登录/注册/退出/改密码/me
        └── InvoiceController.java     /api/list、/api/stats/overview
```

## 三个进程

Java 后端不自己做 PDF 识别和发票查验，这两块交给 Python 小服务：

```bat
工具\启动解析服务.bat      :: 127.0.0.1:8799   上传识别 PDF/OFD
工具\启动查验服务.bat      :: 127.0.0.1:8798   Playwright + 验证码 OCR
启动.bat                   :: 127.0.0.1:8765   Java 后端（主服务）
```

地址在 `application.yml` 里配：`app.parse-base-url` / `app.audit-base-url`。
没启动时只有对应功能不可用（上传报「连不上解析服务」，查验报「连不上查验服务」），
其它功能不受影响。

查验服务还有 `--base` 参数（项目根目录），默认取本文件上两级，一般不用传。

## 构建与运行

```bat
打包.bat        :: 产出 target\invoice-server.jar
启动.bat        :: 访问 http://127.0.0.1:8765
```

命令行等价写法：

```bat
set JAVA_HOME=D:\soft\Java17
mvnw.cmd -DskipTests clean package
java -jar target\invoice-server.jar --app.base-dir=..
```

`--app.base-dir` 指向项目根目录（`数据/`、`前端/dist/` 所在的那层）。
不传就默认用 `..`（后端的上一级）。

## 为什么能用同一个数据库

表结构没动，连密码算法都复刻了：

```
pbkdf2_sha256$200000$盐(hex)$哈希(hex)     PBKDF2WithHmacSHA256，32 字节，UTF-8
```

所以**老账号直接能登**，Python 版和 Java 版还能互相认会话
（`sessions` 表相同、Cookie 名都是 `sid`），迁移期间两边可以并存。

## 已完成 / 待完成

已完成（已用真实数据验证）：

- [x] `/_health`、`/api/ping`
- [x] 登录 / 退出 / 自助注册 / 改密码 / 会话（含失败限流）
- [x] `/api/me`
- [x] `/api/list`：关键词、报销人、月份、状态、查验、报销单、审核状态筛选
- [x] `/api/stats/overview`
- [x] 前端静态托管 + 单页应用路由兜底（`/ledger` 刷新不 404）
- [x] **`/api/parse` 上传识别**（调 Python 解析小服务，PDF/OFD → 字段）
- [x] **`/api/batch_add` 提交报销单**（整批校验、归档原件、通知管理员、写日志）

- [x] **`/api/batch` 详情**（发票列表、合计、汇总状态、can_edit、问题项）
- [x] **`/api/batch/review` 审核**（通过 / 退回，退回必须写原因并可勾选问题票）
- [x] **`/api/batch_resubmit` 重新提交**
- [x] **`/api/batch_delete` 撤回删除**（只有发起人、已通过不能删）
- [x] `/api/batch_status` 整单标记已报销
- [x] `/api/notifications`、`/api/notifications/read`

- [x] **`/api/update` 改发票**（只改带来的字段，重号 409）
- [x] **`/api/status` 切换报销状态**（管理员）
- [x] **`/api/delete` 删单张**（发起人 + 非已通过）
- [x] **`/api/attach` / `/api/detach` 归单 / 拆单**（单空了自动清理）

待完成（按优先级）：

- [ ] `/api/update`、`/api/status`、`/api/delete`、`/api/attach`、`/api/detach`
- [ ] `/api/notifications`
- [ ] `/api/users*`、`/api/logs`
- [ ] `/api/export` Excel
- [ ] 查验：调 Python 小服务（`app.audit-base-url`）

## 已验证

对着真实数据库（7 张发票）跑通：

```
/_health                       ok invoices=7
/api/ping                      engine=spring-boot
登录（错密码 401 / 对密码 200）   ✓
会话 Cookie sid                ✓
PBKDF2 老密码可登录             ✓
/api/list                     7 条，字段与旧版一致
/api/stats/overview           待审/退回/通过/趋势
静态页 /、/login、/ledger      ✓
浏览器全链路（Vue + jar）        ✓ 登录→台账→总览图表
```

## 切换策略

1. 现在：Python 占 8765，Java 可以先用 8766 并行跑
2. 接口补齐后：Nginx 或直接换端口，把流量切到 Java
3. 回滚：改回 Python 即可，数据是同一份，没有迁移动作

## 多收款人（batch_payees）

一张报销单可以付给多个人：`batch_payees(batch_id, name, amount, note, sort)`。

- 合计必须等于单里的发票合计（`PayeeRepo.requireMatches`），差多少报多少
- 整单标记「已报销」后**锁定**，改发票/删发票/改单/删单全部 409；管理员「改回未报销」解锁
- 启动时 `SchemaMigrator` 自动建表并为历史单据补一条收款记录
- 导出 Excel 有第二个工作表「收款明细」
