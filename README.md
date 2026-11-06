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
| `WAIT_MAX_MIN` | `40` | 最多提前/延迟等待多少分钟仍开录 |
| `TRANSCODE` | `720p` | `720p`≈0.7 GB/时（电视下载友好）/ `480p`≈0.4 GB/时 / `off` 直接复制 1080p≈1.1 GB/时 |
| `KEEP_BYTES` | `8000000000` | Release 总容量上限（约 8 GiB），超出删最旧 |
| `KEEP_COUNT` | `30` | 最多保留多少期 |
| `KEEP_MIN` | `3` | 无论配额如何，至少保留最近 3 期 |
| `KEEP_DAYS` | `0` | >0 时同时删除超过 N 天的录制 |

> 每天成功录制后会更新 `docs/heartbeat.json` 并自动提交，用来**保活**——
> GitHub 会在仓库 60 天无活动后自动停用定时工作流，这一步保证它一直跑。

## 手动触发

```
Actions → record-gdtv-zhujiang → Run workflow
  start: 21:00   end: 22:00   duration: 0(按时间窗) 或 60(秒，测试用)
```
测试可以填 `duration: 90`，立刻录 90 秒验证链路。

## APK 安装

1. 下载 `android-latest` Release 里的 `gdtv-recorder.apk`；
2. U 盘/ADB sideload 到电视盒子：`adb install -r gdtv-recorder.apk`；
3. 打开即播最新录制；**OK** = 历史录制列表；**菜单键** = 设置（可改 owner/repo）；
4. 遥控器：左右 = 快退/快进 10 秒，播放暂停键 = 暂停，返回 = 退出；
5. 国内直连 GitHub 慢时，在设置里填"下载加速前缀"（例如 `https://gh-proxy.com/`），视频会走该前缀下载。

> 仓库需为 **public**（Release 资源可直接下载）。换成别的仓库时，在 APK 设置里改 `owner/repo` 即可，无需重新打包。

## 直播源说明

`scripts/streams.txt` 是候选源，脚本按顺序探测，取第一个能拉到 `#EXTM3U` 且能出片的源。
若哪天全部失效，用 `probe.yml` 手动跑一次，把新源追加进 `streams.txt` 即恢复，无需改代码。
