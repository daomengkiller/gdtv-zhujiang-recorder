/**
 * Cloudflare Worker：每天 20:30（中国时区）触发 GitHub Actions 录制。
 *
 * 为什么需要它：这个环境里的 GitHub 自带 schedule 会把任务"吸"到固定扫描窗口，
 * 派发时刻不可控（实测迟到 3~8 小时）。Worker 的 Cron Trigger 是准点的，
 * 一次 fetch 就能把 workflow_dispatch 打过去。
 *
 * 环境变量（wrangler secret / API 写入）：
 *   GH_TOKEN  必填，带 repo + workflow 权限的 PAT
 *   OWNER / REPO / WORKFLOW / REF  可选，默认指向本项目的录制工作流
 *   PROBE_KEY 可选，设置后 HTTP 入口需要 ?key= 匹配
 */

export default {
  // Cron Trigger 入口（wrangler.toml 里配 "30 12 * * *" = 20:30 CST）
  async scheduled(event, env, ctx) {
    ctx.waitUntil(dispatch(env, "cron", {}));
  },

  // HTTP 入口：手动点一次 / 带参数测试
  //   /?key=xxx                → 正常触发（等待到 21:00 再录）
  //   /?key=xxx&duration=20    → 立即录 20 秒（测试链路）
  async fetch(request, env) {
    const url = new URL(request.url);
    if (env.PROBE_KEY && url.searchParams.get("key") !== env.PROBE_KEY) {
      return new Response("forbidden", { status: 403 });
    }

    const inputs = {};
    for (const [k, v] of url.searchParams.entries()) {
      if (k === "key") continue;
      inputs[k] = v;
    }

    const res = await dispatch(env, "manual", inputs);
    const text = res.status === 204 ? "dispatched" : await res.text();
    return new Response(`${res.status} ${text}\n`, {
      status: res.status === 204 ? 200 : res.status,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  },
};

async function dispatch(env, via, inputs) {
  const owner = env.OWNER || "daomengkiller";
  const repo = env.REPO || "gdtv-zhujiang-recorder";
  const workflow = env.WORKFLOW || "record-zhujiang.yml";
  const ref = env.REF || "main";

  if (!env.GH_TOKEN) {
    console.error(`[${via}] GH_TOKEN 未配置`);
    return new Response("missing GH_TOKEN", { status: 500 });
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

  const detail = res.status === 204 ? "dispatched" : await res.text();
  console.log(`[${via}] ${new Date().toISOString()} → ${res.status} ${detail} ${JSON.stringify(inputs || {})}`);
  return new Response(detail, { status: res.status });
}
