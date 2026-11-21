# 切换到 Java 版（Spring Boot）

> 目标：**线上 8765 从 Python 版换成 Spring Boot 版**，全程可回滚。
> 数据库、前端、接口都不变，只换后端进程。

---

## 一、要传什么

用 XFTP / WinSCP 上传到 `/opt/02-fapiaodengji/`：

| 本地 | 服务器 | 说明 |
|---|---|---|
| `后端\target\invoice-server.jar` | `后端/invoice-server.jar` | 52 MB，唯一的大文件 |
| `工具\解析服务.py` | `工具/解析服务.py` | 上传识别 |
| `工具\查验服务.py` | `工具/查验服务.py` | 查验 |
| `前端\dist\` 整个目录 | `前端/dist/` | 覆盖（如果已经传过，确认是最新的） |
| `部署\java\` 整个目录 | `部署/java/` | 三个脚本 |

> jar 在 `后端/target/` 下，文件名 `invoice-server.jar`。
> 服务器上放 `后端/` 还是 `后端/target/` 都行，脚本两个位置都会找。

**不用传**：`node_modules`、`target/classes`、`src`。

---

## 二、第 1 步：并行运行（不动线上）

> **端口可能被防火墙挡住**：8766 是新端口，安全组/ufw 默认没放行。
> 打不开就先在服务器上 `curl -s http://127.0.0.1:8766/_health` 验证，
> 或临时放行： `sudo ufw allow 8766/tcp`（云服务器还要在安全组里加规则）。

```bash
cd /opt/02-fapiaodengji
sudo bash 部署/java/step1-parallel.sh
```

脚本会：

1. 检查并安装 JDK 17（`openjdk-17-jre-headless`）
2. 写三个 systemd 服务：`invoice-parse`(8799) / `invoice-audit`(8798) / `invoice-java`(8766)
3. 启动并健康检查

看到这个就成功了：

```
✔ Java 后端已就绪： http://127.0.0.1:8766/
  解析服务  ok
  查验服务  ok
  健康检查  ok invoices=N
  线上 8765 还是 Python 版，没有动。
```

**此时线上 8765 完全没变**，用户无感。

### 验证（重要）

浏览器打开 `http://124.220.163.141:8766`，逐项确认：

| 检查项 | 预期 |
|---|---|
| 登录页 | 深蓝背景，能登录 |
| 登记台账 | 数据、筛选、翻页正常 |
| 待审核 | 能看到待审单据 |
| 打开一张报销单 | 详情、明细、按钮都在 |
| 新建报销单拖一张 PDF | 能识别出号码金额 |
| 数据总览 | 4 个图表都画出来 |
| 用户管理 / 操作日志 | 能看 |
| 导出 | 能下载 xlsx |

有问题就看日志：

```bash
journalctl -u invoice-java -n 60 --no-pager
journalctl -u invoice-parse -n 30 --no-pager
journalctl -u invoice-audit -n 30 --no-pager
```

---

## 三、第 2 步：正式切换

```bash
cd /opt/02-fapiaodengji
sudo bash 部署/java/step2-switch.sh
```

脚本会：

1. **备份数据库**到 `数据/备份/发票台账_切换前_时间戳.db`
2. 检查 8766 和小服务都正常
3. 停 Python（`invoice-ledger`）
4. Java 接管 8765
5. 健康检查 + 冒烟测试（首页、深链接、登录接口）
6. **如果起不来，自动回滚**
7. 设 Java 开机自启，取消 Python 自启

看到这个就完成了：

```
✔ 已备份到 数据/备份/发票台账_切换前_20261120-xxxxxx.db
✔ 健康检查： ok invoices=N
✔ 已设为开机自启： invoice-java / invoice-parse / invoice-audit
  首页： HTTP 200
  深链接 /ledger： HTTP 200
==============================================
  切换完成： 8765 现在是 Spring Boot 版
  回滚（30 秒）： sudo bash 部署/java/rollback-python.sh
==============================================
```

用户那边 **Ctrl+F5** 即可，不需要重新登录（会话表共用）。

---

## 四、回滚（随时可用）

```bash
cd /opt/02-fapiaodengji
sudo bash 部署/java/rollback-python.sh
```

30 秒回到 Python 版，数据不受影响（一直是同一个库）。

---

## 五、切换后可能遇到的事

| 现象 | 原因 / 处理 |
|---|---|
| 页面 502 / 打不开 | `systemctl status invoice-java`，看日志；直接跑回滚脚本 |
| 上传报「连不上解析服务」 | `systemctl restart invoice-parse` |
| 查验报「连不上查验服务」 | `systemctl restart invoice-audit` |
| 查验一直失败 | 平台限流，等一会儿；看 `journalctl -u invoice-audit` |
| 导出下载失败 | 看 `journalctl -u invoice-java`，多半是内存不够，调大 `/etc/default/invoice-java` 里的 `JAVA_OPTS` |

### 常用命令

```bash
systemctl status invoice-java invoice-parse invoice-audit
journalctl -u invoice-java -f          # 实时日志
curl -s http://127.0.0.1:8765/_health
curl -s http://127.0.0.1:8765/api/ping
```

---

## 六、并行期要注意

并行跑（8765 Python + 8766 Java）时**两个进程都连着同一个 SQLite**。
短时间验证没问题，但：

- **不要在 8766 上做大量写入**（提交、审核）——会和线上抢锁
- 验证完尽快切换，或先停掉 8766

真正完全无风险的做法：并行期只在 8766 上"看"，写操作等切换后再做。

---

## 七、切换完成后可以清理的东西

确认稳定运行一周后：

```bash
# 卸掉 Python 版依赖（保留代码，只是不再运行）
systemctl disable invoice-ledger

# 清理旧备份（保留最近 10 个）
ls -t /opt/02-fapiaodengji/数据/备份/*.db | tail -n +11 | xargs -r rm -f
```

Python 版的代码和 `.venv` 建议**留着**——它是唯一能让我们在新版出问题时快速回滚的路径。
