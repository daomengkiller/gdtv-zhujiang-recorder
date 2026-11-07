#!/usr/bin/env bash
# 等待用的 job 脚本：把「这一次 run」一直拖到中国时区 21:00。
#
# 为什么不用 cron 卡点：这个环境里的 GitHub 调度器是按固定扫描窗口批量派发的
# （实测 dispatched 时刻总落在 UTC 01:30-03:45 / 13:30-15:00 / 21:50-24:00 三簇），
# cron 写几点都会被吸到窗口上，落不进 16:20-21:00。
# 所以改成：不管什么时候被派发，run 内部用多个 job 接力睡到 21:00。
#
# 单个 job 上限 6 小时，所以每次最多睡 HOLD_MIN（默认 340 分钟）。
set -uo pipefail

TZ_CN="Asia/Shanghai"
export TZ="${TZ:-$TZ_CN}"
START_HHMM="${START_HHMM:-21:00}"
END_HHMM="${END_HHMM:-22:00}"
HOLD_MIN="${HOLD_MIN:-340}"
DURATION_OVERRIDE="${DURATION:-0}"

log() { printf '[hold %s] %s\n' "$(TZ=$TZ_CN date +%H:%M:%S)" "$*"; }

# 手动测试模式：不等待
if [ "${DURATION_OVERRIDE}" -gt 0 ] 2>/dev/null; then
  log "手动模式（DURATION=${DURATION_OVERRIDE}），跳过等待"
  exit 0
fi

now="$(date +%s)"
start_epoch="$(TZ=$TZ_CN date -d "$(TZ=$TZ_CN date +%F) ${START_HHMM}" +%s)"
end_epoch="$(TZ=$TZ_CN date -d "$(TZ=$TZ_CN date +%F) ${END_HHMM}" +%s)"

if [ "$now" -ge "$end_epoch" ]; then
  log "今天 ${END_HHMM} 已过，本棒不再等（交给明天的派发）"
  exit 0
fi

if [ "$now" -ge "$start_epoch" ]; then
  log "已在 ${START_HHMM}-${END_HHMM} 窗口内，直接放行"
  exit 0
fi

remain=$((start_epoch - now))
budget=$((HOLD_MIN * 60))

if [ "$remain" -le "$budget" ]; then
  log "睡到 ${START_HHMM}（$((remain / 60)) 分 $((remain % 60)) 秒后）"
  sleep "$remain"
else
  log "先睡 ${HOLD_MIN} 分钟（距 ${START_HHMM} 还有 $((remain / 60)) 分钟）"
  sleep "$budget"
fi

log "本棒结束"
