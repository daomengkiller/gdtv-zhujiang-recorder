package cn.cjw.gdtvrec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 极简 GitHub REST 客户端：只做两件事——列 Release、解析资源直链。 */
public final class GitHubApi {

    private static final String UA = "GdtvRecorder/1.0 (Android TV)";
    private static final int TIMEOUT = 20000;

    private GitHubApi() {
    }

    /**
     * 列出仓库里带 .mp4 资源的 Release（新的在前）。
     *
     * @param token 可为 null/空；有的话可提高限额、访问私有仓库
     */
    public static List<Recording> fetchReleases(String owner, String repo, String token, int perPage)
            throws IOException {
        String url = "https://api.github.com/repos/" + owner + "/" + repo
                + "/releases?per_page=" + Math.max(1, Math.min(perPage, 100));
        String body = httpGet(url, token);
        List<Recording> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(body);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.optJSONObject(i);
                if (rel == null || rel.optBoolean("draft", false)) {
                    continue;
                }
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) {
                    continue;
                }
                JSONObject best = null;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    String lower = name.toLowerCase(Locale.US);
                    if (!(lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts"))) {
                        continue;
                    }
                    if (best == null || a.optLong("size", 0) > best.optLong("size", 0)) {
                        best = a;
                    }
                }
                if (best == null) {
                    continue;
                }
                Recording r = new Recording();
                r.tag = rel.optString("tag_name", "");
                r.title = rel.optString("name", r.tag);
                r.assetName = best.optString("name", "");
                r.url = best.optString("browser_download_url", "");
                r.sizeBytes = best.optLong("size", 0);
                r.publishedIso = rel.optString("published_at", rel.optString("created_at", ""));
                try {
                    r.publishedMs = iso.parse(r.publishedIso).getTime();
                } catch (Exception ignored) {
                    r.publishedMs = 0;
                }
                if (r.url != null && r.url.length() > 0) {
                    out.add(r);
                }
            }
        } catch (Exception e) {
            throw new IOException("解析 Release JSON 失败: " + e.getMessage(), e);
        }
        return out;
    }

    /** 把 github.com/download 的 302 解析成最终对象地址，老电视盒子对 302 支持不稳。 */
    public static String resolveRedirect(String url, String token) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String current = url;
        try {
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(TIMEOUT);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "*/*");
                if (token != null && token.length() > 0) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                if ("GET".equals(c.getRequestMethod())) {
                    c.setRequestMethod("HEAD");
                }
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (code >= 300 && code < 400 && loc != null) {
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                break;
            }
        } catch (Exception ignored) {
            return url;
        }
        return current;
    }

    private static String httpGet(String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(in);
        c.disconnect();
        if (code >= 400) {
            String hint = "";
            if (code == 404) {
                hint = "（仓库不存在或不是 public）";
            } else if (code == 403) {
                hint = "（API 限额用完，可在设置里填 Token）";
            }
            throw new IOException("HTTP " + code + " " + hint + " " + body);
        }
        return body;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 4 * 1024 * 1024) {
                break;
            }
        }
        in.close();
        return bos.toString("UTF-8");
    }
}
