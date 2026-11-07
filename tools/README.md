# 外部定时器：给 GitHub Actions 打点

GitHub 自带的 `schedule` 在本实例上会把任务「吸」到固定扫描窗口（实测迟到 3~8 小时），
落不进 16:20–21:00 这段等待区。所以除了仓库内的等待链，还可以用**免费无服务器的定时器**
直接打 `workflow_dispatch`。录制工作流自带幂等，外部打点和 cron 共存也不会重复录。

| 方案 | 免费额度 | 准点程度 | 需要信用卡 | 部署难度 |
| --- | --- | --- | --- | --- |
| **Cloudflare Workers Cron** `tools/cf-cron` | 10 万请求/天，每个账号 5 个 cron | **准点**（cron 精确到分钟） | 不要 | `wrangler deploy` 三条命令 |
| **Google Apps Script** `tools/apps-script` | 完全免费（Google 账号即可） | 准点，可能晚 15~30 分钟 | 不要 | 浏览器里粘贴，点两次运行 |
| AWS EventBridge Scheduler + API destination | 每月 1400 万次调用 | 准点（支持秒级） | 要（AWS 账号） | 控制台点选，无需写代码 |
| Vercel Cron | Hobby 每天 1 次 | 准点 ±1 小时 | 不要 | 需要建项目并部署 |
| Netlify Scheduled Functions | 免费额度内 | 准点 | 不要 | 需要建站点 |
| cron-job.org | 免费 | 准点 | 不要 | 但自定义 Header 需付费版，token 不好放 |

> 录制工作流内已经有「多 job 接力等待到 21:00」的逻辑，所以外部定时器**晚几分钟甚至几小时**都没关系，
> 只要在当天派发成功一次，run 会自己等到 21:00 再录。

## 一、Cloudflare Worker（推荐）

```bash
cd tools/cf-cron
npx wrangler@latest login                 # 浏览器授权（有 Clash 代理也能用）
npx wrangler@latest secret put GH_TOKEN   # 粘 PAT（repo + workflow 权限）
npx wrangler@latest deploy                # 会打印 https://gdtv-cron.<子域>.workers.dev
```

装完可以在 Cloudflare 控制台 → Workers → gdtv-cron → Triggers 看到 `30 12 * * *`（= 20:30 CST）。
手动验证：

```bash
curl "https://gdtv-cron.<子域>.workers.dev/?key=<PROBE_KEY>"
# 返回 204 / dispatched 即成功
```

## 二、Google Apps Script

1. 打开 <https://script.google.com> → 新建项目；
2. 用 `Code.gs`、`appsscript.json` 覆盖默认内容（项目设置里勾选「显示 appsscript.json」）；
3. 项目设置 → 脚本属性 → 新增 `GH_TOKEN`；
4. 运行 `triggerGdtvRecord()` → 授权 → 看日志是否 204；
5. 运行 `installDailyTrigger()` → 装上每天 20:30 的触发器。

## 三、AWS EventBridge Scheduler（无需写代码）

1. EventBridge → Scheduler → Create schedule，Cron `cron(30 12 * * ? *)`，时区 `Asia/Shanghai` 对应 20:30；
2. Target 选 **API destination**（HTTP endpoint `https://api.github.com/repos/<owner>/<repo>/actions/workflows/record-zhujiang.yml/dispatches`）；
3. Connection 用 `API key` 类型：header `Authorization`，值 `Bearer <PAT>`；
4. 一次调用即可触发。
