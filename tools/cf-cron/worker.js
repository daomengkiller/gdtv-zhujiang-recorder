/**
 * Cloudflare Worker：每天 20:30（中国时区）触发 GitHub Actions 录制。
 *
 * 为什么需要它：这个环境里的 GitHub 自带 schedule 会把任务"吸"到固定扫描窗口，
 * 派发时刻不可控（实测迟到 3~8 小时）。Worker 的 Cron Trigger 是准点的，
 * 一次 fetch 就能把 workflow_dispatch 打过去。
 *
 * 部署：
 *   npm i -g wrangler && wrangler login
 *   wrangler secret put GH_TOKEN        # 粘一个带 repo+workflow 权限的 PAT
 *   wrangler deploy
 *
 * 手动验证（部署后会给你一个 https://gdtv-cron.<你的子域>.workers.dev 地址）：
 *   curl "https://gdtv-cron.<子域>.workers.dev/?key=<PROBE_KEY>"
 */

export default {
  // Cron Trigger 入口（wrangler.toml 里配 "30 12 * * *" = 20:30 CST）
  async scheduled(event, env, ctx) {
    ctx.waitUntil(dispatch(env, "cron"));
  },

  // HTTP 入口，方便手动点一次；带 PROBE_KEY 时校验，避免被随便打
  async fetch(request, env) {
    const url = new URL(request.url);
    if (env.PROBE_KEY && url.searchParams.get("key") !== env.PROBE_KEY) {
      return new Response("forbidden", { status: 403 });
    }
    const res = await dispatch(env, "manual");
    const text = await res.text();
    return new Response(`${res.status} ${text}\n`, { status: res.status === 204 ? 200 : res.status });
  },
};

async function dispatch(env, via) {
  const owner = env.OWNER || "daomengkiller";
  const repo = env.REPO || "gdtv-zhujiang-recorder";
  const workflow = env.WORKFLOW || "record-zhujiang.yml";
  const ref = env.REF || "main";

  if (!env.GH_TOKEN) {
    console.error(`[${via}] GH_TOKEN 未配置`);
    return new Response("missing GH_TOKEN", { status: 500 });
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
    body: JSON.stringify({ ref }),
  });

  const body = res.status === 204 ? "dispatched" : await res.text();
  console.log(`[${via}] ${new Date().toISOString()} → ${res.status} ${body}`);
  return new Response(body, { status: res.status });
}
