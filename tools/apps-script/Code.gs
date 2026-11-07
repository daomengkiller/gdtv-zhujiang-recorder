/**
 * Google Apps Script 版：每天 20:30（Asia/Shanghai）触发 GitHub Actions 录制。
 *
 * 零成本、无需信用卡、不用服务器：Google 账号 → script.google.com → 新建项目 → 粘贴本文件。
 *
 * 步骤：
 *   1) 项目设置里勾选「显示 appsscript.json 清单文件」，把 appsscript.json 的 timeZone 改成 Asia/Shanghai；
 *   2) 项目设置 → 脚本属性 → 新增属性 GH_TOKEN = 你的 PAT（需要 repo + workflow 权限）；
 *   3) 先手动跑一次 triggerGdtvRecord()，授权 UrlFetchApp，看执行日志是否为 204；
 *   4) 跑一次 installDailyTrigger()，装上每天 20:30 的触发器（Apps Script 触发可能晚 15~30 分钟，
 *      录制工作流自带等待逻辑，晚一点也会等到 21:00 再录）。
 */

const OWNER = 'daomengkiller';
const REPO = 'gdtv-zhujiang-recorder';
const WORKFLOW = 'record-zhujiang.yml';
const REF = 'main';

function triggerGdtvRecord() {
  const token = PropertiesService.getScriptProperties().getProperty('GH_TOKEN');
  if (!token) throw new Error('脚本属性缺少 GH_TOKEN');

  const url = `https://api.github.com/repos/${OWNER}/${REPO}/actions/workflows/${WORKFLOW}/dispatches`;
  const res = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'gdtv-apps-script',
    },
    payload: JSON.stringify({ ref: REF }),
    muteHttpExceptions: true,
  });

  const code = res.getResponseCode();
  Logger.log(`${new Date().toISOString()} → HTTP ${code} ${res.getContentText()}`);
  if (code >= 300) throw new Error(`触发失败 HTTP ${code}: ${res.getContentText()}`);
}

/** 装一个每天 20:30 的触发器（会先清掉旧的同名触发器） */
function installDailyTrigger() {
  ScriptApp.getProjectTriggers().forEach((t) => {
    if (t.getHandlerFunction() === 'triggerGdtvRecord') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('triggerGdtvRecord')
    .timeBased()
    .atHour(20)
    .nearMinute(30)
    .create();
  Logger.log('已安装每天 20:30 的触发器');
}

/** 列出当前触发器，确认装好了 */
function listTriggers() {
  ScriptApp.getProjectTriggers().forEach((t) =>
    Logger.log(`${t.getHandlerFunction()} ${t.getEventType()} ${t.getTriggerSource()}`)
  );
}
