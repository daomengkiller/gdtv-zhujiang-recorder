package cn.cjw.gdtvrec;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 广东珠江录制回放（Android TV）—— 标准 TV 播放器交互：
 *
 *   列表页：上下选片，OK 播放；列表底部还有「刷新」「设置」两行；
 *   播放页：OK 呼出/收起控制条（进度 + 快进快退），← → 快退快进 10 秒，
 *           播放/暂停键切换，返回键回到列表。
 *
 * 打开即播最新一期；按返回立刻回到列表挑历史。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gdtv";
    private static final String DEF_OWNER = "daomengkiller";
    private static final String DEF_REPO = "gdtv-zhujiang-recorder";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Recording> items = new ArrayList<>();

    // 配置
    private SharedPreferences sp;
    private String owner;
    private String repo;
    private String token;
    private String proxy;

    // 列表页
    private LinearLayout listScreen;
    private TextView listHeader;
    private TextView listStatus;
    private ListView list;
    private RowAdapter adapter;

    // 播放页
    private FrameLayout playerScreen;
    private VideoView video;
    private LinearLayout osd;
    private TextView osdTitle;
    private TextView osdPos;
    private SeekBar osdSeek;
    private ProgressBar spinner;

    private boolean playerMode = false;
    private boolean dialogOpen = false;
    private boolean osdVisible = false;
    private int currentIndex = 0;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        owner = sp.getString("owner", DEF_OWNER);
        repo = sp.getString("repo", DEF_REPO);
        token = sp.getString("token", "");
        proxy = sp.getString("proxy", "");

        buildUi();
        showListScreen(true);
        loadReleases(true);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- UI 构建

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ---------- 列表页 ----------
        listScreen = new LinearLayout(this);
        listScreen.setOrientation(LinearLayout.VERTICAL);
        listScreen.setBackgroundColor(Color.parseColor("#0E1116"));

        listHeader = new TextView(this);
        listHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        listHeader.setTextColor(Color.WHITE);
        listHeader.setPadding(dp(28), dp(24), dp(28), dp(10));
        listHeader.setText("广东珠江 · 录制节目");
        listScreen.addView(listHeader);

        listStatus = new TextView(this);
        listStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        listStatus.setTextColor(Color.parseColor("#B3FFFFFF"));
        listStatus.setPadding(dp(28), 0, dp(28), dp(8));
        listStatus.setText("正在连接 GitHub…");
        listScreen.addView(listStatus);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(getResources().getDrawable(R.drawable.row_bg));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onRowClicked(position);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listScreen.addView(list, lp);

        TextView footer = new TextView(this);
        footer.setText("上下选择　·　OK 播放　·　菜单键 设置　·　返回 退出");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        footer.setTextColor(Color.parseColor("#80FFFFFF"));
        footer.setPadding(dp(28), dp(10), dp(28), dp(18));
        listScreen.addView(footer);

        root.addView(listScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 播放页 ----------
        playerScreen = new FrameLayout(this);
        playerScreen.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vp.gravity = Gravity.CENTER;
        playerScreen.addView(video, vp);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                spinner.setVisibility(View.GONE);
                video.start();
                osdTitle.setText(items.isEmpty() ? "" : items.get(Math.min(currentIndex, items.size() - 1))
                        .label());
                showOsd(true);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                toast("播放结束");
                showListScreen(false);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                spinner.setVisibility(View.GONE);
                toast("播放失败(" + what + "/" + extra + ")，换一期试试");
                showListScreen(false);
                return true;
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    spinner.setVisibility(View.VISIBLE);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    spinner.setVisibility(View.GONE);
                }
                return false;
            }
        });

        // 控制条（OSD）
        osd = new LinearLayout(this);
        osd.setOrientation(LinearLayout.VERTICAL);
        osd.setBackgroundColor(Color.parseColor("#CC000000"));
        osd.setPadding(dp(32), dp(14), dp(32), dp(16));
        osd.setVisibility(View.GONE);

        osdTitle = new TextView(this);
        osdTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        osdTitle.setTextColor(Color.WHITE);
        osd.addView(osdTitle);

        osdSeek = new SeekBar(this);
        osdSeek.setFocusable(false);
        osdSeek.setClickable(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        slp.topMargin = dp(6);
        osd.addView(osdSeek, slp);

        osdPos = new TextView(this);
        osdPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        osdPos.setTextColor(Color.parseColor("#B3FFFFFF"));
        osdPos.setText("00:00 / 00:00　　← → 快退快进 10 秒　·　返回 回列表");
        osd.addView(osdPos);

        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.gravity = Gravity.BOTTOM;
        playerScreen.addView(osd, op);

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams sp2 = new FrameLayout.LayoutParams(dp(56), dp(56));
        sp2.gravity = Gravity.CENTER;
        spinner.setVisibility(View.GONE);
        playerScreen.addView(spinner, sp2);

        playerScreen.setVisibility(View.GONE);
        root.addView(playerScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 列表

    private final Runnable osdTicker = new Runnable() {
        @Override
        public void run() {
            if (!osdVisible || !playerMode) {
                return;
            }
            try {
                int pos = video.getCurrentPosition();
                int dur = video.getDuration();
                osdSeek.setMax(dur > 0 ? dur : 1);
                osdSeek.setProgress(pos);
                osdPos.setText(mmss(pos) + " / " + mmss(dur)
                        + "　　← → 快退快进 10 秒　·　返回 回列表");
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 500);
        }
    };

    private String mmss(int ms) {
        if (ms <= 0) {
            return "00:00";
        }
        int s = ms / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, sec);
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size() + 2;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_row, parent, false);
            }
            TextView t = (TextView) v.findViewById(R.id.row_title);
            TextView m = (TextView) v.findViewById(R.id.row_meta);
            if (position < items.size()) {
                Recording r = items.get(position);
                t.setText((position == 0 ? "【最新】  " : "") + r.label());
                m.setText(r.assetName);
            } else if (position == items.size()) {
                t.setText("⟳   刷新列表");
                m.setText("");
            } else {
                t.setText("⚙   设置：仓库 / Token / 加速前缀");
                m.setText(owner + "/" + repo);
            }
            return v;
        }
    }

    private void onRowClicked(int position) {
        if (position < items.size()) {
            currentIndex = position;
            play(items.get(position));
        } else if (position == items.size()) {
            loadReleases(false);
            toast("正在刷新…");
        } else {
            openSettings();
        }
    }

    private void showListScreen(boolean requestFocus) {
        playerMode = false;
        hideOsd();
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        playerScreen.setVisibility(View.GONE);
        listScreen.setVisibility(View.VISIBLE);
        listHeader.setText("广东珠江 · 录制节目" + (items.isEmpty() ? "" : "（" + items.size() + " 期）"));
        if (items.isEmpty()) {
            listStatus.setText("还没有录制，或正在获取…\n每晚 21:00 的 GitHub Actions 会自动录制并上传。");
        } else {
            listStatus.setText("共 " + items.size() + " 期　·　最新的在最上面");
        }
        if (requestFocus) {
            list.requestFocus();
            if (!items.isEmpty()) {
                list.setSelection(Math.min(currentIndex, items.size() - 1));
            } else {
                list.setSelection(0);
            }
        }
    }

    private void showPlayerScreen() {
        playerMode = true;
        listScreen.setVisibility(View.GONE);
        playerScreen.setVisibility(View.VISIBLE);
        video.requestFocus();
    }

    // ---------------------------------------------------------------- 播放

    private void play(final Recording r) {
        showPlayerScreen();
        spinner.setVisibility(View.VISIBLE);
        osdTitle.setText(r.label());
        osdPos.setText("正在打开 " + r.assetName);
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final String realUrl;
                if (proxy != null && proxy.trim().length() > 0) {
                    String p = proxy.trim();
                    if (!p.endsWith("/")) {
                        p = p + "/";
                    }
                    realUrl = p + r.url;
                } else {
                    realUrl = GitHubApi.resolveRedirect(r.url, token);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        video.setVideoURI(Uri.parse(realUrl));
                        video.requestFocus();
                        video.start();
                    }
                });
            }
        });
    }

    private void showOsd(boolean autoHide) {
        osdVisible = true;
        osd.setVisibility(View.VISIBLE);
        ui.removeCallbacks(osdTicker);
        ui.post(osdTicker);
        ui.removeCallbacks(hideOsdRunnable);
        if (autoHide) {
            ui.postDelayed(hideOsdRunnable, 6000);
        }
    }

    private void hideOsd() {
        osdVisible = false;
        osd.setVisibility(View.GONE);
        ui.removeCallbacks(osdTicker);
        ui.removeCallbacks(hideOsdRunnable);
    }

    private final Runnable hideOsdRunnable = new Runnable() {
        @Override
        public void run() {
            hideOsd();
        }
    };

    private void seekBy(int deltaMs) {
        try {
            int pos = video.getCurrentPosition();
            int dur = video.getDuration();
            int target = pos + deltaMs;
            if (target < 0) {
                target = 0;
            }
            if (dur > 0 && target > dur - 800) {
                target = dur - 800;
            }
            video.seekTo(target);
            showOsd(true);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 数据

    private void loadReleases(final boolean autoPlay) {
        spinner.setVisibility(View.GONE);
        listStatus.setText("正在获取 " + owner + "/" + repo + " 的录制列表…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                List<Recording> list0 = null;
                String err = null;
                try {
                    list0 = GitHubApi.fetchReleases(owner, repo, token, 60);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<Recording> fl = list0;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fe != null || fl == null) {
                            listStatus.setText("获取失败：" + fe + "\n选中「刷新列表」重试，或到「设置」检查仓库地址");
                            toast("获取录制列表失败");
                            showListScreen(true);
                            return;
                        }
                        items.clear();
                        items.addAll(fl);
                        adapter.notifyDataSetChanged();
                        showListScreen(true);
                        if (items.isEmpty()) {
                            toast("仓库里还没有录制");
                            return;
                        }
                        if (autoPlay) {
                            currentIndex = 0;
                            play(items.get(0));
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 设置

    private void openSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);

        TextView l1 = new TextView(this);
        l1.setText("仓库（owner/repo，默认 " + DEF_OWNER + "/" + DEF_REPO + "）");
        box.addView(l1);

        final EditText e1 = new EditText(this);
        e1.setSingleLine(true);
        e1.setInputType(InputType.TYPE_CLASS_TEXT);
        e1.setText(owner + "/" + repo);
        box.addView(e1);

        TextView l2 = new TextView(this);
        l2.setText("GitHub Token（可选：私有仓库或 API 限额时填）");
        l2.setPadding(0, dp(16), 0, 0);
        box.addView(l2);

        final EditText e2 = new EditText(this);
        e2.setSingleLine(true);
        e2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e2.setText(token);
        box.addView(e2);

        TextView l3 = new TextView(this);
        l3.setText("下载加速前缀（可选，例如 https://gh-proxy.com/）");
        l3.setPadding(0, dp(16), 0, 0);
        box.addView(l3);

        final EditText e3 = new EditText(this);
        e3.setSingleLine(true);
        e3.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e3.setText(proxy);
        box.addView(e3);

        dialogOpen = true;
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroller)
                .setPositiveButton("保存并刷新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String v = e1.getText().toString().trim();
                        String[] parts = v.split("/");
                        if (parts.length == 2 && parts[0].trim().length() > 0 && parts[1].trim().length() > 0) {
                            owner = parts[0].trim();
                            repo = parts[1].trim();
                        } else {
                            toast("格式应为 owner/repo，已忽略");
                        }
                        token = e2.getText().toString().trim();
                        proxy = e3.getText().toString().trim();
                        sp.edit().putString("owner", owner).putString("repo", repo)
                                .putString("token", token).putString("proxy", proxy).apply();
                        loadReleases(false);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                dialogOpen = false;
            }
        });
        d.show();
    }

    // ---------------------------------------------------------------- 遥控器

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (dialogOpen) {
            return super.onKeyDown(keyCode, event);
        }
        if (playerMode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    showListScreen(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (osdVisible) {
                        hideOsd();
                    } else {
                        showOsd(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    seekBy(-10000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seekBy(10000);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (video.isPlaying()) {
                        video.pause();
                        osdTitle.setText("已暂停");
                    } else {
                        video.start();
                    }
                    showOsd(true);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_SETTINGS:
                    openSettings();
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
        // 列表页：方向键/OK 交给 ListView 自己处理
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
    }
}
