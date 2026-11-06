package cn.cjw.gdtvrec;

/** 一期录制（GitHub Release 里的一个 mp4 资源）。 */
public class Recording {
    public String tag;
    public String title;
    public String assetName;
    public String url;
    public long sizeBytes;
    public long publishedMs;
    public String publishedIso;

    /** 列表里显示的一行文字。 */
    public String label() {
        StringBuilder sb = new StringBuilder();
        String when = Recording.formatTime(publishedMs, publishedIso);
        if (when != null) {
            sb.append(when);
        } else {
            sb.append(title != null ? title : tag);
        }
        if (sizeBytes > 0) {
            sb.append("  ·  ").append(humanSize(sizeBytes));
        }
        return sb.toString();
    }

    public static String humanSize(long bytes) {
        double mb = bytes / 1024.0 / 1024.0;
        if (mb >= 1024) {
            return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.0f MB", mb);
    }

    /** ISO8601(UTC) → 本地时区 "MM-dd HH:mm"。 */
    public static String formatTime(long ms, String iso) {
        try {
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
            out.setTimeZone(java.util.TimeZone.getDefault());
            if (ms > 0) {
                return out.format(new java.util.Date(ms));
            }
            if (iso != null) {
                java.text.SimpleDateFormat in =
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return out.format(in.parse(iso));
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
