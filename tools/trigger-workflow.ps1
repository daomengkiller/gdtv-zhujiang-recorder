# 可选：用外部定时器 100% 准时触发录制
#
# 背景：GitHub 的 schedule 在本环境里会迟 3~8 小时派发，仓库自带的重试设计能吸收大部分，
# 但如果你要"绝不漏一天"，就在一台常开的机器上挂这个脚本，每天 20:30(中国时区) 打一次
# workflow_dispatch 即可。脚本是幂等的：当天已经录过，工作流会直接退出，不会重复录。
#
# 用法（Windows）：
#   1) 把 token 放到用户环境变量（不要写进本文件）：
#        setx GH_TOKEN ghp_xxxxxxxx
#   2) 手动跑一次验证：
#        powershell -NoProfile -ExecutionPolicy Bypass -File .\trigger-workflow.ps1
#   3) 注册每天 20:30 的计划任务：
#        schtasks /Create /F /SC DAILY /ST 20:30 /TN "GDTV珠江录制" ^
#          /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\path\to\trigger-workflow.ps1"
#   4) 查看/删除：
#        schtasks /Query /TN "GDTV珠江录制"
#        schtasks /Delete /F /TN "GDTV珠江录制"

param(
    [string]$Owner     = "daomengkiller",
    [string]$Repo      = "gdtv-zhujiang-recorder",
    [string]$Workflow  = "record-zhujiang.yml",
    [string]$Token     = $env:GH_TOKEN,
    [string]$ProxyUrl  = "",
    [string]$LogFile   = "$env:TEMP\gdtv-trigger.log"
)

$ErrorActionPreference = "Stop"

function Write-Log($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Log "缺少 token：请先 setx GH_TOKEN ghp_xxx 再重开终端"
    exit 1
}

$uri = "https://api.github.com/repos/$Owner/$Repo/actions/workflows/$Workflow/dispatches"
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "gdtv-trigger"
}
$body = @{ ref = "main" } | ConvertTo-Json

$args2 = @{
    Uri             = $uri
    Method          = "POST"
    Headers         = $headers
    Body            = $body
    ContentType     = "application/json"
    UseBasicParsing = $true
    TimeoutSec      = 30
}
if ($ProxyUrl) { $args2["Proxy"] = $ProxyUrl }

try {
    $r = Invoke-WebRequest @args2
    Write-Log "触发成功 HTTP $($r.StatusCode)：$Owner/$Repo $Workflow"
    exit 0
} catch {
    Write-Log "触发失败：$($_.Exception.Message)"
    exit 1
}
