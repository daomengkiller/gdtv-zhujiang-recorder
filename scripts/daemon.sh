#!/usr/bin/env bash
# 自维持链（chain.yml）里的“守护”逻辑。
#
# 设计：每一棒活到“下一个 CST 4 小时整点”（00/04/08/12/16/20 点），到点交棒。
# 这样边界每天固定，20:00 那一棒必定覆盖 20:45 的布防点 → 每天都有人在 21:00 开录，
# 不受交棒漂移影响。不依赖 GitHub 的 schedule。
#
# 布防窗口 [20:45, 22:05)：命中就调 record.sh（它自己会等到 21:00、自带幂等与补录）。
set -uo pipefail

TZ_CN="Asia/Shanghai"
export TZ="${TZ:-$TZ_CN}"
ARM_FROM="${ARM_FROM:-2045}"             # 布防起点 HHMM
ARM_TO="${ARM_TO:-2205}"                 # 布防终点 HHMM
ARM_HHMM="${ARM_HHMM:-20:45}"            # daemon 内部等到的整点（与 ARM_FROM 对应）
MAX_RUNTIME="${MAX_RUNTIME:-21000}"      # 安全上限 5h50m
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[chain %s] %s\n' "$(TZ=$TZ_CN date +%H:%M:%S)" "$*"; }

boot="$(date +%s)"

# ---- 计算本棒的结束时刻：下一个 CST 4 小时整点 ----
h="$(TZ=$TZ_CN date +%-H)"
next_h=""
for b in 0 4 8 12 16 20; do
  if [ "$b" -gt "$h" ]; then next_h="$b"; break; fi
done
if [ -z "$next_h" ]; then
  end_epoch="$(TZ=$TZ_CN date -d "tomorrow 00:00" +%s)"
else
  end_epoch="$(TZ=$TZ_CN date -d "today $(printf '%02d' "$next_h"):00" +%s)"
fi
[ "$end_epoch" -le "$boot" ] && end_epoch=$((boot + 14400))
[ $((end_epoch - boot)) -gt "$MAX_RUNTIME" ] && end_epoch=$((boot + MAX_RUNTIME))

log "本棒启动，交棒时刻 $(TZ=$TZ_CN date -d "@$end_epoch" '+%F %H:%M')（约 $(((end_epoch - boot) / 60)) 分钟）"

armed=0
while :; do
  now="$(date +%s)"
  if [ "$now" -ge "$end_epoch" ]; then
    log "到交棒时刻，本棒结束"
    break
  fi

  hm="$(TZ=$TZ_CN date +%H%M)"
  if [ "$armed" -eq 0 ] && [ "$hm" -ge "$ARM_FROM" ] && [ "$hm" -le "$ARM_TO" ]; then
    log "进入布防窗口（$hm），开始录制流程"
    bash "$SCRIPT_DIR/record.sh"
    log "record.sh 退出码=$?"
    armed=1
    sleep 20
    continue
  fi

  remain=$((end_epoch - now))
  sleep_for=300
  [ "$remain" -lt "$sleep_for" ] && sleep_for="$remain"
  [ "$sleep_for" -lt 20 ] && sleep_for=20
  log "空闲（布防窗口 ${ARM_FROM}-${ARM_TO}，距离交棒 $((remain / 60)) 分钟）"
  sleep "$sleep_for"
done
