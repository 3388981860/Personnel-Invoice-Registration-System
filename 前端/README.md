# 人数发票登记台账 · 前端（Vue 3 + Element Plus）

新前端，替代原来的 `网页/`（原生 JS）。**接口完全沿用现有 REST 约定**，
所以现在可以直接接 Python 后端跑，等后端的 Spring Boot 换完，前端一行都不用改。

## 目录

```
前端/
├── index.html
├── package.json
├── vite.config.js          # /api 代理到后端，换后端只改 VITE_API_TARGET
└── src/
    ├── main.js             # 入口：Element Plus + 图标 + 路由
    ├── App.vue
    ├── router.js           # 路由与登录守卫
    ├── api.js              # 所有接口封装（唯一的后端调用出口）
    ├── store.js            # 会话 + 统计的共享状态
    ├── styles/main.css     # 政务深色侧栏主题（含 Element Plus 变量覆盖）
    ├── layouts/AppLayout.vue
    ├── components/
    │   ├── AccountDialog.vue    # 账号设置（信息 + 改密码）
    │   ├── DetailDrawer.vue     # 发票 / 报销单详情 + 审核动作
    │   └── NotifyDrawer.vue     # 站内消息
    └── views/
        ├── LoginView.vue        # 登录 / 注册
        ├── EntryView.vue        # 新建报销单（拖拽上传 + 识别 + 提交）
        ├── LedgerView.vue       # 登记台账 / 待审核（同一组件，按路由切筛选）
        ├── StatsView.vue        # 数据总览（ECharts）
        ├── UsersView.vue        # 用户管理
        └── LogsView.vue         # 操作日志
```

## 开发

```bash
# 1. 先启动后端（Python 版）
python 程序/server.py --no-browser --port 8765

# 2. 前端
cd 前端
npm install
npm run dev          # http://localhost:5180
```

后端不在 8765 时，用环境变量指过去：

```bash
VITE_API_TARGET=http://192.168.1.10:8765 npm run dev
```

## 打包

```bash
npm run build        # 产物在 dist/，纯静态文件
```

产物可以直接丢给任意静态服务器，或者让后端当静态资源托管。

> 打包时如果遇到 `Access is denied` 提示 esbuild 删不掉临时文件，
> 多半是杀毒软件锁了系统临时目录，把 `TMP`/`TEMP` 指到项目里的目录再打包即可：
> `set TMP=%CD%\.tmpbuild && set TEMP=%CD%\.tmpbuild && npm run build`

## 与后端的关系

`src/api.js` 是唯一的出口，所有接口集中在 `api` 对象里：

| 分组 | 接口 |
|---|---|
| 会话 | `/api/me` `/api/login` `/api/register` `/api/logout` `/api/password` |
| 台账 | `/api/list` `/api/parse` `/api/add` `/api/update` `/api/status` `/api/delete` `/api/attach` `/api/detach` |
| 报销单 | `/api/batches` `/api/batch` `/api/batch_add` `/api/batch_update` `/api/batch_status` `/api/batch/review` `/api/batch_resubmit` `/api/batch_delete` |
| 查验 | `/api/audit/start` `/api/audit/status` `/api/audit/stop` `/api/audit/shot` |
| 其它 | `/api/notifications` `/api/stats/overview` `/api/users` `/api/logs` `/api/export` |

Spring Boot 后端按这套契约实现即可，前端不用动。

## 已实现的功能

- 登录 / 自助注册、会话失效自动跳登录
- 工作台：拖拽上传 PDF/OFD → 自动识别 → 本单发票表内联编辑 → 提交报销单
- 登记台账：多条件筛选、排序、分页、批量选择、查验选中、归单/拆单、
  报销状态开关、导出 Excel
- 待审核：与台账同组件，按路由预置「待审核」筛选
- 详情抽屉：发票详情（含查验截图点开看大图）、报销单详情
  （审核通过 / 退回并勾选问题票 / 重新提交 / 整单标记已报销 / 撤回删除）
- 数据总览：8 个 KPI + 趋势 / 审核状态 / 查验分布 / 报销人 TOP8
- 用户管理：新建、改姓名、改角色、停用、重置密码
- 操作日志、站内消息、账号设置

## 还没做

- 报销单编辑（改单号/备注/日期、增删发票）
- 查验进度浮层里的「逐张进度」细化展示
- 移动端适配
