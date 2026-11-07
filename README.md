# 广东珠江（GDTV 44）定时录制 · GitHub Actions 自动化

全自动方案：GitHub Actions 每天在中国时区 **21:00–22:00** 录制[广东珠江频道](https://www.gdtv.cn/tvChannelDetail/44)，
把成片上传到本仓库的 **Release**，并按存储配额自动删除最旧的录制。
配套 Android TV APK 直接读取 Release，开机即播最新一期，遥控器按 **OK** 可以选历史录制。

```
GitHub Actions (cron 20:30 CST 启动 → 等到 21:00 开录 → 录 60 分钟)
        │  ffmpeg -c copy 落盘 mp4 (faststart)
        ▼
Release: rec-YYYYMMDD-HHMM  ← 附带 gdtv-zhujiang-YYYYMMDD-HHMM.mp4
        │  scripts/prune.py 超出配额就删最旧
        ▼
Android TV APK  →  GitHub API 列 Release  →  自动播最新 / OK 键选历史
```

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `.github/workflows/record.yml` | 定时录制 + 上传 Release + 配额清理 |
| `.github/workflows/android.yml` | 自动构建 APK 并发布到 `android-latest` Release |
| `.github/workflows/probe.yml` | 手动：探测当前可用的直播源（排障用） |
| `scripts/record.sh` | 等待时间窗 → 选源 → ffmpeg 录制 → 输出 mp4 |
| `scripts/streams.txt` | 候选直播源列表（按顺序探测，第一可用者生效） |
| `scripts/prune.py` | 依据总字节数/条数/天数删除最旧的 Release |
| `app/` | Android TV 客户端（Java，无第三方依赖，可直接 sideload） |

## 关键参数（都在 `record.yml` 的 `env:` 里）

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `START_HHMM` | `21:00` | 开始录制（Asia/Shanghai） |
| `END_HHMM` | `22:00` | 结束录制 |
| `WAIT_MAX_MIN` | `70` | 最多提前等待多少分钟仍开录（cron 早到就睡，超时就交给后面的槽位） |
| `TRANSCODE` | `720p` | `720p`≈0.7 GB/时（电视下载友好）/ `480p`≈0.4 GB/时 / `off` 直接复制 1080p≈1.1 GB/时 |
| `KEEP_BYTES` | `8000000000` | Release 总容量上限（约 8 GiB），超出删最旧 |
| `KEEP_COUNT` | `30` | 最多保留多少期 |
| `KEEP_MIN` | `3` | 无论配额如何，至少保留最近 3 期 |
| `KEEP_DAYS` | `0` | >0 时同时删除超过 N 天的录制 |

### 定时触发：交给 Cloudflare Worker，不用 GitHub 的 schedule

2026-11-06 第一次没录，排查结论不是脚本、也不是时区（cron 一律按 **UTC** 解析）。真实原因是：
**这个环境的调度器不是按 cron 时间派发，而是按固定扫描窗口批量派发**——派发时刻总被"吸"到
UTC 01:30–03:45 / 13:30–15:00 / 21:50–24:00 三簇（= CST 09:30–11:45 / 21:30–23:00 / 05:50–08:00），
cron 写几点都落不进 16:20–21:00 这个"等待区"，还会白烧 Actions 额度。

实测数据（同一账号其他仓库，长期运行）：

| 工作流 | cron(UTC) | 实际触发(UTC) | 迟到 |
| --- | --- | --- | --- |
| `devops/签到` | `45 22 * * *` | 01:27 / 01:48 / 02:41 / 02:45 / 02:48 | +3~4h |
| `devops/签到TLY` | `45 6,18 * * *` | 13:47 / 14:37 / 14:53 / 21:52 / 23:30 / 23:56 | +7~8h / +3~5h |

**所以本仓库不用 `schedule`**（`workflow_dispatch` 是唯一入口），改由
**Cloudflare Worker 的 Cron Trigger 每天 20:30 CST 打一次**：

```
Workers → gdtv-cron → Triggers: 30 12 * * *   (UTC 12:30 = CST 20:30)
           │  POST /actions/workflows/record-zhujiang.yml/dispatches
           ▼
record-gdtv-zhujiang（单个 job，约 1.5 小时）
   record.sh 等到 21:00 → ffmpeg 录 60 分钟 → Release → prune → heartbeat
```

* 单 job，`timeout-minutes: 150`，每天只跑 1 次 ≈ 1.5 小时额度（不再有 20+ 小时的等待链）；
* `record.sh` 会自己等 `WAIT_MAX_MIN=45` 分钟，CF 早几分钟晚几分钟都不影响准点开录；
* 落进 `[21:00, 22:00)` 就录；过了 22:00 明确报"漏录"，不录错档节目；
* 幂等：当天已有 `rec-YYYYMMDD-*` 就退出，重复触发不会重复录。

> 直播不能回看：只在 `[21:00, 22:00)` 内开录，过期不补。

> Worker 的手动入口（带 key）：
> `curl "https://gdtv-cron.2463508647.workers.dev/?key=<PROBE_KEY>"`；
> 想验证链路可加 `&duration=15` 立即录 15 秒。
> 其他可选触发方案（Google Apps Script / AWS EventBridge）见 `tools/README.md`。

> 每天成功录制后会更新 `docs/heartbeat.json` 并自动提交，用来**保活**——
> GitHub 会在仓库 60 天无活动后自动停用定时工作流。

## 手动触发

```
Actions → record-gdtv-zhujiang → Run workflow
  start: 21:00   end: 22:00   duration: 0(按时间窗) 或 60(秒，测试用)
```
测试可以填 `duration: 90`，立刻录 90 秒验证链路。

## APK 安装与操作

1. 下载 `android-latest` Release 里的 `gdtv-recorder.apk`；
2. U 盘/ADB sideload 到电视盒子：`adb install -r gdtv-recorder.apk`；
3. **打开即播最新一期**（全屏播放，控制条自动收起）；
4. **返回键**：控制条收起中 → 回到列表；列表里上下选片、**OK** 播放；
5. 播放中按 **OK** 呼出控制条（跟传统播放器一致的 OSD）：

   ```
   ┌──────────────────────────────────────────────┐
   │ 广东珠江 · 11-07 21:00 · 680 MB      1.2 MB/s │  ← 右上角实时网速
   ├──────────────────────────────────────────────┤
   │  ●━━━━━━━━━━━━━━━━━━━━━━━━━  00:23 / 1:00:00  │  ← 进度条（方向键调）
   │  [⏸ 暂停] [⏪10秒] [10秒⏩] [1.25x 倍速] [☰ 播放列表]  │
   └──────────────────────────────────────────────┘
   ```

   * 上下键在进度条与按钮之间切换，左右键调进度/移动焦点，OK 执行；
   * **暂停/播放**：按钮或遥控器播放暂停键；
   * **倍速**：0.5x → 0.75x → 1.0x → 1.25x → 1.5x → 2.0x 循环（需 Android 6+）；
   * **播放列表**：直接切到录制列表，选别的期；
   * **网速**：右上角实时显示本 App 的接收速率（`TrafficStats` 采样）；
   * **15 秒无操作自动收起**回到全屏，按返回键也会立即收起（再按返回回列表）；
6. 列表最下面两行是「⟳ 刷新列表」和「⚙ 设置」，按 OK 进入；
   - 设置里可改 `owner/repo`（换成别的仓库不用重装 APK）、GitHub Token；
   - 国内直连 GitHub 慢时填"下载加速前缀"（例如 `https://gh-proxy.com/`），视频走该前缀下载。
7. 菜单键在任意界面都能呼出设置。

> 仓库需为 **public**（Release 资源可直接下载）。

## 直播源说明

`scripts/streams.txt` 是候选源，脚本按顺序探测，取第一个能拉到 `#EXTM3U` 且能出片的源。
若哪天全部失效，用 `probe.yml` 手动跑一次，把新源追加进 `streams.txt` 即恢复，无需改代码。
