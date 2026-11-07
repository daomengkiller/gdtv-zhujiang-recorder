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

### 定时触发：实测出来的真相（重要）

2026-11-06 第一次没录，排查结论不是脚本、也不是时区（cron 一律按 **UTC** 解析，
`30 12` 就是 20:30 CST；时区错只会跑错点，不会 0 条 run）。真实原因是：
**这个环境的 schedule 派发严重迟到，且注册后要过很久才开始生效。**

实测数据（同一账号其他仓库，长期运行）：

| 工作流 | cron(UTC) | 实际触发(UTC) | 迟到 |
| --- | --- | --- | --- |
| `devops/签到` | `45 22 * * *` | 01:27 / 01:48 / 02:41 / 02:45 / 02:48 | +3~4h |
| `devops/签到TLY` | `45 6,18 * * *` | 13:47 / 14:37 / 14:53 / 21:52 / 23:30 / 23:56 | +7~8h / +3~5h |
| 本仓库新加的 `*/5` 探针 | 每 5 分钟 | 各仓库只触发 **1 次**（21:24Z 前后） | — |

所以现在的设计是**让任务早到、再准点开录**：

* cron 设在 21:00 之前若干小时：`0 6`（14:00 CST）、`0 9`（17:00 CST）；
* 实测派发会晚 3~8 小时 → 落在 **17:00~次日 02:00 CST**；
* `record.sh` 会自己**等到 21:00 再开录**（最多提前等 4h40m，正好在 job 6h 上限内录满 1 小时）；
* 落在 21:00~22:00 之间就直接录剩余部分；落在 22:00 之后就明确报"漏录"、不录错内容；
* 脚本自带幂等（当天已有 `rec-YYYYMMDD-*` 就退出），两个 cron 撞车也不会重复录。

> 直播不能回看：只在 `[21:00, 22:00)` 内开录，过期不补。

> 想要 100% 准时（完全不看 GitHub 调度器脸色），用外部定时器打一次 `workflow_dispatch`：
> 见 `tools/trigger-workflow.ps1`（Windows 任务计划，20:30 CST 触发）。两者可并存，幂等保证不重复录。

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
3. **打开即播最新一期**（和电视播放器一样全屏播放）；
4. **返回键**回到列表，上下键选片，**OK** 播放选中的那期；
5. 播放中：**OK** 呼出/收起控制条（进度+时间），**← →** 快退/快进 10 秒，播放/暂停键切换，**返回**回列表；
6. 列表最下面两行是「⟳ 刷新列表」和「⚙ 设置」，按 OK 进入；
   - 设置里可改 `owner/repo`（换成别的仓库不用重装 APK）、GitHub Token；
   - 国内直连 GitHub 慢时填"下载加速前缀"（例如 `https://gh-proxy.com/`），视频走该前缀下载。
7. 菜单键在任意界面都能呼出设置。

> 仓库需为 **public**（Release 资源可直接下载）。

## 直播源说明

`scripts/streams.txt` 是候选源，脚本按顺序探测，取第一个能拉到 `#EXTM3U` 且能出片的源。
若哪天全部失效，用 `probe.yml` 手动跑一次，把新源追加进 `streams.txt` 即恢复，无需改代码。
