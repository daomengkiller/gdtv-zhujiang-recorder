/**
 * Cloudflare Worker：每天 20:30（中国时区）触发 GitHub Actions 录制。
 *
 * 为什么需要它：这个环境里的 GitHub 自带 schedule 会把任务"吸"到固定扫描窗口，
 * 派发时刻不可控（实测迟到 3~8 小时）。Worker 的 Cron Trigger 是准点的，
 * 一次 fetch 就能把 workflow_dispatch 打过去。
 *
 * 环境变量：
 *   GH_TOKEN  必填，带 repo + workflow 权限的 PAT
 *   OWNER / REPO / WORKFLOW / REF  可选，默认指向本项目的录制工作流
 *   PROBE_KEY 可选，设置后 HTTP 入口需要 ?key= 匹配
 */

export default {
  // Cron Trigger 入口（cron "30 12 * * *" = 20:30 CST）
  async scheduled(event, env, ctx) {
    ctx.waitUntil(dispatchAndLog(env, "cron", {}));
  },

  // HTTP 入口：手动点一次 / 带参数测试
  //   /?key=xxx                 → 正常触发（脚本会等到 21:00 再录）
  //   /?key=xxx&duration=20     → 立即录 20 秒（测链路）
  async fetch(request, env) {
    const url = new URL(request.url);
    if (env.PROBE_KEY && url.searchParams.get("key") !== env.PROBE_KEY) {
      return new Response("forbidden\n", { status: 403 });
    }

    const inputs = {};
    for (const [k, v] of url.searchParams.entries()) {
      if (k === "key") continue;
      inputs[k] = v;
    }

    const r = await dispatchAndLog(env, "manual", inputs);
    return new Response(`${r.status} ${r.detail}\n`, {
      status: 200,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  },
};

/** @returns {Promise<{status:number, detail:string}>} */
async function dispatchAndLog(env, via, inputs) {
  try {
    const r = await dispatch(env, inputs);
    console.log(`[${via}] ${new Date().toISOString()} → ${r.status} ${r.detail} ${JSON.stringify(inputs || {})}`);
    return r;
  } catch (e) {
    const detail = `worker error: ${e && e.message ? e.message : String(e)}`;
    console.error(`[${via}] ${detail}`);
    return { status: 500, detail };
  }
}

async function dispatch(env, inputs) {
  const owner = env.OWNER || "daomengkiller";
  const repo = env.REPO || "gdtv-zhujiang-recorder";
  const workflow = env.WORKFLOW || "record-zhujiang.yml";
  const ref = env.REF || "main";

  if (!env.GH_TOKEN) {
    return { status: 500, detail: "missing GH_TOKEN" };
  }

  const payload = { ref };
  if (inputs && Object.keys(inputs).length > 0) {
    payload.inputs = inputs;
  }

  const url = `https://api.github.com/repos/${owner}/${repo}/actions/workflows/${workflow}/dispatches`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GH_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "gdtv-cron-worker",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });

  // 注意：204 不能带 body，所以这里只返回状态和文本，由调用方组装 Response
  const detail = res.status === 204 ? "dispatched" : await res.text();
  return { status: res.status, detail };
}
