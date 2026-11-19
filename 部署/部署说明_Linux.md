# 人数发票登记系统 —— Linux 服务器部署说明

> 适用：Ubuntu / Debian / CentOS / Rocky / AlmaLinux 等常见发行版。
> 两种情况任选：**A 一键脚本**（推荐，直接用 systemd 管服务）或 **B Docker**（环境最干净）。

---

## 零、先明确一件事：服务器上要跑什么

| 模块 | 说明 |
|---|---|
| `程序/server.py` | 本地 Web 服务（Python 标准库，无需 nginx 也能跑） |
| `网页/` | 前端页面（原生 JS，无构建步骤） |
| `数据/` | SQLite 台账 + Excel + 查验截图（**你的业务数据**） |
| `发票存档/` | 发票原件 PDF（**你的业务数据**） |
| `程序/审核/模型/` | 验证码识别模型（29 MB） |
| 运行库 | **Linux 不用**，Windows 才用；Linux 走 pip |
| `启动.bat` / `自检.bat` | Windows 专用，Linux 用 `部署/*.sh` |

服务默认监听 `0.0.0.0:8765`，同事用浏览器访问 `http://服务器IP:8765/`。

---

## 一、方案 A：一键脚本（systemd）

### 1. 把文件传上去

```bash
# 在你自己的电脑上打包（务必带 -C，避免 Windows 路径问题）
tar -czf invoice.tar.gz \
    程序 网页 数据 发票存档 部署 自检.py 操作说明.txt

# 传到服务器
scp invoice.tar.gz root@服务器IP:/opt/
```

> ⚠ **不要用 zip**：Windows 打的 zip 里中文文件名编码容易坏，解压后全是乱码文件名。
> 用 `tar.gz` 或 `rsync -av` 最稳。

```bash
ssh root@服务器IP
mkdir -p /opt/invoice-ledger
tar -xzf /opt/invoice.tar.gz -C /opt/invoice-ledger
cd /opt/invoice-ledger
```

### 2. 一条命令部署

```bash
sudo INVOICE_AUTH='财务:换成你的密码' bash 部署/deploy.sh
```

它会依次做：

1. 装系统依赖（python3、venv、中文字体）
2. 建系统用户 `invoice`，把 `数据/`、`发票存档/` 交给它
3. 建 `.venv` 虚拟环境，装 Python 包（默认清华源）
4. 装 chromium（约 200 MB）
5. 写 `/etc/systemd/system/invoice-ledger.service`，开机自启
6. 启动并做健康检查（`/_health`）

跑完会打印访问地址。**首次启动会自动把数据库里的 Windows 反斜杠路径转成正斜杠**，几秒钟。

### 3. 常用运维命令

```bash
systemctl status invoice-ledger       # 状态
systemctl restart invoice-ledger      # 重启
journalctl -u invoice-ledger -f       # 实时日志
journalctl -u invoice-ledger -n 100   # 最近日志
```

### 4. 改配置

- 端口 / 监听地址：改 systemd 里的 `--port`（或重新跑一遍 deploy.sh 时传 `PORT=9000`）
- 查验参数：`数据/查验配置.json`（改完 `systemctl restart invoice-ledger`）
- 访问口令：改 `/etc/invoice-ledger.env`（权限 600），
  然后 `systemctl restart invoice-ledger`

---

## 二、方案 B：Docker

```bash
cd /opt/invoice-ledger

# 可选：设置访问口令
echo 'INVOICE_AUTH=财务:换成你的密码' > .env

docker compose -f 部署/docker-compose.yml up -d --build
```

- 镜像基于 `mcr.microsoft.com/playwright/python`，chromium、字体、系统依赖都自带
- `数据/`、`发票存档/` 挂载在宿主机上，重建容器不丢数据
- 健康检查已内置（`/_health`）

```bash
docker compose -f 部署/docker-compose.yml logs -f
docker compose -f 部署/docker-compose.yml restart
```

---

## 三、部署后必做

### 1. 防火墙放行

```bash
# firewalld
firewall-cmd --permanent --add-port=8765/tcp && firewall-cmd --reload
# ufw
ufw allow 8765/tcp
```

云服务器还要在**安全组**里放行。

### 2. 一定要设访问口令

台账里有发票号码、购销方、税号、金额、出行人。不设口令的话，**同网段任何人都能打开**。

```bash
sudo INVOICE_AUTH='财务:强密码' bash 部署/deploy.sh   # 重跑即可
```

### 3. 再加一层 HTTPS（建议）

如果要从公网访问，前面挂 nginx + 证书：

```nginx
server {
    listen 443 ssl;
    server_name invoice.example.com;

    ssl_certificate     /etc/letsencrypt/live/invoice.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/invoice.example.com/privkey.pem;

    client_max_body_size 50m;      # 一次拖多张发票

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;   # 查验是后台任务，别让代理过早断开
    }
}
```

配上证书后，可以把服务本身改成只听 127.0.0.1（`deploy.sh` 时传 `HOST=127.0.0.1`），
外网只走 nginx。

---

## 四、数据迁移注意事项

### 从 Windows 迁移

1. **整目录传**：`数据/` 和 `发票存档/` 都要带过去，别只传数据库
2. **用 tar.gz / rsync**，不要 zip（中文文件名编码问题）
3. 第一次启动会自动：
   - 给数据库补新字段（如果对方版本旧）
   - 把 `发票存档\2026\10\x.pdf` 这种反斜杠路径转成 `/`
4. 传完检查一下：

```bash
sudo -u invoice .venv/bin/python 自检.py
cat 自检报告.txt
```

报告里【7】能读到记录数、【8-1】查验组件"存在/成功/已安装"，就说明数据接上了。

### 权限

`数据/` 和 `发票存档/` 必须让运行用户（默认 `invoice`）可写，否则上传发票会失败：

```bash
chown -R invoice:invoice /opt/invoice-ledger/数据 /opt/invoice-ledger/发票存档
```

### 备份

系统每天自动把数据库备份到 `数据/备份/`。建议再加一条定时任务备份整个目录：

```bash
# crontab -e
0 2 * * * tar -czf /backup/invoice-$(date +\%F).tar.gz -C /opt/invoice-ledger 数据 发票存档
```

---

## 五、和 Windows 版的差异

| 项目 | Windows | Linux |
|---|---|---|
| 启动 | 双击 `启动.bat` | `systemctl start invoice-ledger` |
| 依赖 | `运行库/` 便携目录 | `pip install -r 部署/requirements-linux.txt` |
| 浏览器 | 装到 `%LOCALAPPDATA%\ms-playwright` | 装到 `~/.cache/ms-playwright` |
| 打开文件夹按钮 | 可用 | **自动隐藏**（服务器没有图形界面） |
| 访问地址 | 127.0.0.1 | 0.0.0.0，同事用服务器 IP 访问 |
| 口令 | 无（本机用） | 支持 `INVOICE_AUTH` |

代码里已经做了跨平台处理（`os.startfile` 只在 Windows 分支、路径统一用 `/` 存、`rel_path()` 兼容反斜杠），**同一套代码 Windows 和 Linux 都能跑**。

---

## 六、排错

| 现象 | 排查 |
|---|---|
| 打不开页面 | `systemctl status` 看是否在跑；`ss -lntp \| grep 8765` 看监听；防火墙/安全组 |
| 上传发票后识别不出号码 | `自检.py` 看【2】【4】；Linux 上需要 `pip install pymupdf` |
| 查验一直"未查验" | `journalctl -u invoice-ledger -n 50`；常见是没网或 chromium 没装好 |
| 查验提示浏览器启动失败 | `.venv/bin/python -m playwright install --with-deps chromium` |
| 页面显示旧样式 | 浏览器 Ctrl+F5（服务端已带版本号，正常不会缓存） |
| 中文文件名乱码 | 传输用了 zip；改用 tar.gz 重新传 |
| 服务起不来，日志说权限 | `chown -R invoice:invoice 数据 发票存档` |

---

## 七、部署时踩过的坑（真实记录）

### 7.1 `zxingcpp` 在 Python 3.13 上装不上

第一次在 Ubuntu（Python 3.13）上跑 `deploy.sh`，卡在：

```
ERROR: Could not find a version that satisfies the requirement zxingcpp>=2.0
ERROR: No matching distribution found for zxingcpp>=2.0
```

原因：zxingcpp 2.0.x 只支持到 Python 3.12，镜像里没有 3.13 的 wheel。

**处理**：`zxingcpp` 只是"图片型发票读二维码"的兜底，不该成为硬依赖。
改成用 rapidocr 已经带的 **opencv** 兜底（`cv2.QRCodeDetector`），
`requirements-linux.txt` 里去掉它，`deploy.sh` 里改成"可选，装不上只警告"。

实测两条路解出的内容一模一样：

```
zxingcpp → 01,32,,26327000001715695220,25.86,20260928,,3de2
opencv   → 01,32,,26327000001715695220,25.86,20260928,,3de2
模拟 zxingcpp 缺失 → 自动退到 opencv，结果一致
```

### 7.2 无桌面服务器上 opencv 导入失败

`rapidocr` 依赖 `opencv-python`，它需要系统的 `libGL`；纯命令行服务器默认没有这个库，
表现是 `import cv2` 报 `libGL.so.1: cannot open shared object file`，然后验证码识别整个不可用。

**处理**：系统依赖里补上 `libgl1 libglib2.0-0 libgomp1`，
并在装完 pip 包后立刻做一次导入自检（缺库当场报错，不会拖到用的时候才发现）。

### 7.3 中文发票截图显示成方框

查验截图是浏览器渲染的，服务器没有中文字体就会全是 tofu 方块。
**处理**：系统依赖里加 `fonts-noto-cjk`。

### 7.4 口令写在 systemd 单元里不安全

`/etc/systemd/system/*.service` 默认 644，任何用户都能读到 `INVOICE_AUTH=用户:密码`。
**处理**：改成写 `/etc/invoice-ledger.env`（权限 600）+ `EnvironmentFile=-` 引用。

### 7.5 重跑是安全的

`deploy.sh` 可以反复执行：venv 已存在会复用、系统用户已存在会跳过、
systemd 服务会被覆盖重写、数据目录只 chown 不动内容。
所以第一次失败后，修完直接再跑一遍即可。
### 7.6 浏览器装到了 root 家目录，服务说找不到

**现象**：部署完成、页面能开，但点查验立刻失败：

```
浏览器启动失败：BrowserType.launch: Executable doesn't exist at
/opt/02-fapiaodengji/.cache/ms-playwright/chromium_headless_shell-1255/...
```

**原因**：`deploy.sh` 是 root 跑的，`playwright install` 把浏览器装进了
`/root/.cache/ms-playwright`；而服务是以 `invoice` 用户运行的，
它的 HOME 是 `/opt/02-fapiaodengji`，Playwright 于是去找
`/opt/02-fapiaodengji/.cache/ms-playwright` —— 空目录。

**处理**（脚本已改，重跑即可）：

```bash
sudo INVOICE_AUTH='xych:xych-123' bash 部署/deploy.sh
```

改动有三处：
1. 浏览器安装路径显式定为 `$APP_DIR/.cache/ms-playwright`
   （正好等于服务用户 HOME 下的默认路径，两条路指向同一处）
2. systemd 单元里写入 `Environment=PLAYWRIGHT_BROWSERS_PATH=...`，
   不再依赖 HOME 推断
3. 装完立刻**以服务用户身份真的开一次 chromium**，打不开就当场失败

**只想快速修好、不想重跑整个部署**，两条命令即可：

```bash
cd /opt/02-fapiaodengji

# 系统依赖（root）
sudo .venv/bin/python -m playwright install-deps chromium

# 以服务用户装浏览器（落到它自己 HOME 下的 .cache）
sudo -u invoice env HOME=/opt/02-fapiaodengji \
     /opt/02-fapiaodengji/.venv/bin/python -m playwright install chromium

sudo systemctl restart invoice-ledger
```

验证：

```bash
sudo -u invoice env HOME=/opt/02-fapiaodengji \
  /opt/02-fapiaodengji/.venv/bin/python -c "
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch(headless=True); print('OK', b.version); b.close()"
```

出现 `OK 1xx.x.xxxx.xx` 就说明浏览器能用了，再去页面点查验。
### 7.7 装浏览器时报 `EACCES: permission denied, mkdir '/opt/.../.cache'`

**现象**：

```bash
sudo -u invoice env HOME=/opt/02-fapiaodengji \
     /opt/02-fapiaodengji/.venv/bin/python -m playwright install chromium
# Failed to install browsers
# Error: EACCES: permission denied, mkdir '/opt/02-fapiaodengji/.cache'
```

**原因**：项目目录是 root 解压出来的，`invoice` 用户对它没有写权限，
连 `.cache` 这一级都建不了。

**处理**：别让服务用户去装，改成 **root 装 + 装完把目录交给服务用户**：

```bash
cd /opt/02-fapiaodengji

sudo env PLAYWRIGHT_BROWSERS_PATH=/opt/02-fapiaodengji/.cache/ms-playwright \
     /opt/02-fapiaodengji/.venv/bin/python -m playwright install --with-deps chromium

sudo chown -R invoice:invoice /opt/02-fapiaodengji/.cache

sudo systemctl restart invoice-ledger
```

**为什么最后是这个路径**：`invoice` 用户的 HOME 就是 `/opt/02-fapiaodengji`，
Playwright 默认去 `$HOME/.cache/ms-playwright` 找浏览器，
所以装到这里，服务的默认查找路径刚好对得上，不用改任何环境变量。

`deploy.sh` 已按这个思路改：root 装 → `chown -R invoice:invoice $APP_DIR/.cache`
→ systemd 里再显式写一次 `PLAYWRIGHT_BROWSERS_PATH`，双保险。
### 7.8 升级到多用户版本后，第一次登录

升级后第一次启动，程序会自动建管理员账号，并把随机初始密码写到：

```
/opt/02-fapiaodengji/数据/管理员初始密码.txt
```

查看：

```bash
sudo cat /opt/02-fapiaodengji/数据/管理员初始密码.txt
```

用 `admin` + 这个密码登录，**系统会要求立刻改密码**，改完这个文件就可以删掉。

如果忘了密码，可以这样重置（把 admin 的密码重置成固定值）：

```bash
cd /opt/02-fapiaodengji
sudo -u invoice .venv/bin/python - <<'PY'
import sys, os
sys.path.insert(0, os.path.join(os.getcwd(), "程序"))
import auth, store
st = store.Store(os.getcwd())
u = st.get_user_by_name("admin")
st.set_password(u["id"], auth.hash_password("改成你的新密码"), must_change_pw=1)
print("已重置")
PY
sudo systemctl restart invoice-ledger
```

### 7.9 忘记管理员密码 / 被锁在外面

**最快办法**（不用传文件，服务器上直接跑）：

```bash
cd /opt/02-fapiaodengji
sudo -u invoice .venv/bin/python - <<'PY'
import sys, os, secrets
sys.path.insert(0, os.path.join(os.getcwd(), "程序"))
import auth, store
st = store.Store(os.getcwd())
u = st.get_user_by_name("admin")
pw = "".join(secrets.choice("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(10))
st.set_password(u["id"], auth.hash_password(pw), must_change_pw=1)
st.delete_user_sessions(u["id"])
print("admin 新密码:", pw)
PY
```

**或者**把项目里的 `工具/重置密码.py` 传上去，它会列出所有账号让你选：

```bash
cd /opt/02-fapiaodengji
sudo -u invoice .venv/bin/python 工具/重置密码.py admin
```

Windows 单机版直接双击 `工具\重置密码.bat`。

### 7.10 不要用「用户管理」里的「重置密码」重置自己

那个操作会给随机新密码 + **立刻清掉自己的登录状态**，新密码只闪一下。
自己改密码请用顶栏的「改密码」（需要原密码，不会掉线）。

（新版本里自己那一行已经不显示「重置密码」了，只剩「改密码」。）
