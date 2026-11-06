#!/usr/bin/env bash
# 广东珠江（GDTV 44）定时录制脚本
# 设计要点：
#   1. GitHub cron 会延迟，因此按“中国时区墙钟”等到 START_HHMM 才开录；
#   2. 候选直播源逐个探测，第一个能出片的源生效；
#   3. 只做 -c copy 转封装，不转码，1 小时录制在免费 runner 上很轻松。
set -uo pipefail

TZ_CN="Asia/Shanghai"
export TZ="${TZ:-$TZ_CN}"

START_HHMM="${START_HHMM:-21:00}"
END_HHMM="${END_HHMM:-22:00}"
WAIT_MAX_MIN="${WAIT_MAX_MIN:-40}"
DURATION_OVERRIDE="${DURATION:-0}"       # >0 时忽略时间窗，立刻录这么多秒（测试用）
MIN_BYTES="${MIN_BYTES:-500000}"          # 小于此大小的产物视为失败
OUT_DIR="${OUT_DIR:-out}"
UA="${UA:-Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[record %s] %s\n' "$(TZ=$TZ_CN date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

mkdir -p "$OUT_DIR"

TODAY="$(TZ=$TZ_CN date +%F)"
start_epoch="$(TZ=$TZ_CN date -d "${TODAY} ${START_HHMM}" +%s)"
end_epoch="$(TZ=$TZ_CN date -d "${TODAY} ${END_HHMM}" +%s)"
[ "$end_epoch" -le "$start_epoch" ] && end_epoch=$((start_epoch + 3600))

now="$(date +%s)"
log "今天窗口: ${TODAY} ${START_HHMM}-${END_HHMM} (${start_epoch} → ${end_epoch})，当前 epoch=${now}"

# ---------- 1. 决定录制时长 ----------
if [ "${DURATION_OVERRIDE}" -gt 0 ] 2>/dev/null; then
  rec_seconds="${DURATION_OVERRIDE}"
  log "手动模式：立即录制 ${rec_seconds}s"
else
  # 等到墙钟开始时间（cron 提前跑就睡，最多等 WAIT_MAX_MIN）
  while [ "$now" -lt "$start_epoch" ]; do
    remain=$((start_epoch - now))
    if [ "$remain" -gt $((WAIT_MAX_MIN * 60)) ]; then
      die "离开始还有 $((remain / 60)) 分钟，超过 WAIT_MAX_MIN=${WAIT_MAX_MIN}，本次不录（说明 cron 触发过于提前）"
    fi
    sleep_for=$((remain > 60 ? 60 : remain))
    log "等待开始，剩余 $((remain / 60))m$((remain % 60))s"
    sleep "$sleep_for"
    now="$(date +%s)"
  done

  if [ "$now" -ge "$end_epoch" ]; then
    # cron 严重延迟，窗口已结束：延迟 15 分钟内仍录满 1 小时（宁可偏晚也不要漏录）
    late=$((now - end_epoch))
    if [ "$late" -le 900 ]; then
      rec_seconds=$((end_epoch - start_epoch))
      log "cron 延迟 ${late}s，窗口已过：按完整 ${rec_seconds}s 补录"
    else
      die "窗口已于 $((late / 60)) 分钟前结束，本次跳过"
    fi
  else
    rec_seconds=$((end_epoch - now))
    log "正常开录，录到窗口结束共 ${rec_seconds}s"
  fi
fi
[ "$rec_seconds" -lt 10 ] && die "录制时长过短: ${rec_seconds}s"

# ---------- 2. 候选直播源 ----------
mapfile -t CANDIDATES < <(
  {
    if [ -n "${STREAM_URLS:-}" ]; then
      printf '%s\n' "$STREAM_URLS" | tr ',' '\n'
    fi
    sed -e 's/[[:space:]]*$//' "$SCRIPT_DIR/streams.txt" \
      | grep -v -E '^[[:space:]]*(#|$)' \
      | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
  } | awk 'NF && !seen[$0]++'
)
[ "${#CANDIDATES[@]}" -eq 0 ] && die "没有候选源（检查 scripts/streams.txt 或 STREAM_URLS）"
log "候选源 ${#CANDIDATES[@]} 个"

probe_playlist() {
  local url="$1" body
  body="$(curl -fsSL --max-time 15 -A "$UA" ${REFERER:+-H "Referer: $REFERER"} "$url" 2>/dev/null | head -c 4096)"
  case "$body" in
    \#EXTM3U*) return 0 ;;
    *) return 1 ;;
  esac
}

# ---------- 3. 录制 ----------
stamp="$(TZ=$TZ_CN date +%Y%m%d-%H%M)"
out_file="${OUT_DIR}/gdtv-zhujiang-${stamp}.mp4"
tmp_file="${OUT_DIR}/.part-${stamp}.mp4"
recorded=0

for url in "${CANDIDATES[@]}"; do
  log "探测: ${url}"
  if ! probe_playlist "$url"; then
    log "  跳过：不是有效 m3u8"
    continue
  fi
  log "  使用该源开始录制 → ${out_file}"
  rm -f "$tmp_file"
  ffmpeg -hide_banner -loglevel warning -stats \
    -user_agent "$UA" \
    ${REFERER:+-headers "Referer: ${REFERER}"$'\r\n'} \
    -rw_timeout 20000000 \
    -fflags +genpts+discardcorrupt \
    -i "$url" \
    -t "$rec_seconds" \
    -c copy -bsf:a aac_adtstoasc \
    -movflags +faststart \
    -avoid_negative_ts make_zero \
    -y "$tmp_file"
  rc=$?
  size=$(stat -c %s "$tmp_file" 2>/dev/null || echo 0)
  log "  ffmpeg 退出码=${rc} 产物=${size}B"
  if [ "$size" -ge "$MIN_BYTES" ]; then
    mv -f "$tmp_file" "$out_file"
    recorded=1
    break
  fi
  log "  产物过小，换下一个源"
  rm -f "$tmp_file"
done

[ "$recorded" -eq 1 ] || die "所有候选源均录制失败"
rm -f "$tmp_file"

ffprobe -v error -show_entries format=duration,size:stream=codec_name,width,height,avg_frame_rate \
  -of default=noprint_wrappers=1 "$out_file" 2>/dev/null | sed 's/^/  [probe] /' || true

bytes=$(stat -c %s "$out_file")
log "完成: ${out_file} ($((bytes / 1024 / 1024)) MiB)"

# ---------- 4. 输出给后续 step ----------
if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "OUT_FILE=${out_file}"
    echo "OUT_NAME=$(basename "$out_file")"
    echo "OUT_TAG=rec-${stamp}"
    echo "OUT_BYTES=${bytes}"
  } >> "$GITHUB_ENV"
fi
echo "$out_file"
