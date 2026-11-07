package cn.cjw.gdtvrec;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 广东珠江录制回放（Android TV）—— 传统 TV 播放器交互：
 *
 *   列表页：上下选片，OK 播放；列表底部还有「刷新」「设置」两行；
 *   播放页：OK 呼出控制条（进度 / 播放暂停 / ±10 秒 / 倍速 / 播放列表 / 右上角网速），
 *           15 秒无操作自动收起回到全屏；返回键也收起（再按回列表）。
 *
 * 打开即播最新一期。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gdtv";
    private static final String DEF_OWNER = "daomengkiller";
    private static final String DEF_REPO = "gdtv-zhujiang-recorder";
    private static final int OSD_TIMEOUT_MS = 15000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Recording> items = new ArrayList<>();

    // 配置
    private SharedPreferences sp;
    private String owner, repo, token, proxy;

    // 列表页
    private LinearLayout listScreen;
    private TextView listHeader, listStatus;
    private ListView list;
    private RowAdapter adapter;

    // 播放页
    private FrameLayout playerScreen;
    private SurfaceView surface;
    private MediaPlayer player;
    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean playing = false;
    private boolean playerMode = false;
    private boolean dialogOpen = false;
    private int currentIndex = 0;
    private String pendingUrl = null;

    private LinearLayout osdTop, osdBottom;
    private TextView titleText, speedText, timeText;
    private SeekBar seekBar;
    private Button playBtn, speedBtn;
    private Button[] buttons;
    private ProgressBar spinner;
    private boolean osdVisible = false;
    private float speedFactor = 1.0f;
    private long lastRx = -1;
    private int focusRow = 0;   // 0=按钮行 1=进度条
    private int focusIdx = 0;   // 按钮行里的第几个

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
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(buildListScreen(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildPlayerScreen(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerScreen.setVisibility(View.GONE);
        setContentView(root);
    }

    private View buildListScreen() {
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
        listScreen.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = new TextView(this);
        footer.setText("上下选择　·　OK 播放　·　菜单键 设置　·　返回 退出");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        footer.setTextColor(Color.parseColor("#80FFFFFF"));
        footer.setPadding(dp(28), dp(10), dp(28), dp(18));
        listScreen.addView(footer);
        return listScreen;
    }

    private View buildPlayerScreen() {
        playerScreen = new FrameLayout(this);
        playerScreen.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        FrameLayout.LayoutParams sp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sp2.gravity = Gravity.CENTER;
        playerScreen.addView(surface, sp2);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (player != null) {
                    player.setSurface(holder.getSurface());
                }
                if (pendingUrl != null) {
                    String u = pendingUrl;
                    pendingUrl = null;
                    startPlayback(u);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
            }
        });

        // ---- 顶部：标题 + 右上角网速 ----
        osdTop = new LinearLayout(this);
        osdTop.setOrientation(LinearLayout.HORIZONTAL);
        osdTop.setBackgroundColor(Color.parseColor("#99000000"));
        osdTop.setPadding(dp(28), dp(14), dp(28), dp(14));
        osdTop.setGravity(Gravity.CENTER_VERTICAL);

        titleText = new TextView(this);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleText.setTextColor(Color.WHITE);
        titleText.setMaxLines(1);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        osdTop.addView(titleText);

        speedText = new TextView(this);
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speedText.setTextColor(Color.parseColor("#7CF9C6"));
        speedText.setText("网速 --");
        osdTop.addView(speedText);

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP;
        playerScreen.addView(osdTop, tp);

        // ---- 底部控制条 ----
        osdBottom = new LinearLayout(this);
        osdBottom.setOrientation(LinearLayout.VERTICAL);
        osdBottom.setBackgroundColor(Color.parseColor("#CC000000"));
        osdBottom.setPadding(dp(32), dp(12), dp(32), dp(16));

        seekBar = new SeekBar(this);
        seekBar.setMax(1);
        seekBar.setFocusable(false);       // 焦点由我们自己管理
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateTimeText(progress, player == null ? 0 : safeDuration());
                if (fromUser && !sb.isPressed()) {
                    // 遥控器方向键调进度：SeekBar 不会触发 onStopTrackingTouch，这里直接跳
                    doSeek(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                doSeek(sb.getProgress());
            }
        });
        osdBottom.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        playBtn = makeButton("⏸ 暂停");
        Button rewBtn = makeButton("⏪ 10 秒");
        Button fwdBtn = makeButton("10 秒 ⏩");
        speedBtn = makeButton("1.0x 倍速");
        Button listBtn = makeButton("☰ 播放列表");

        row.addView(playBtn);
        row.addView(rewBtn);
        row.addView(fwdBtn);
        row.addView(speedBtn);
        row.addView(listBtn);
        buttons = new Button[]{playBtn, rewBtn, fwdBtn, speedBtn, listBtn};

        timeText = new TextView(this);
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        timeText.setTextColor(Color.parseColor("#B3FFFFFF"));
        timeText.setPadding(dp(20), 0, 0, 0);
        row.addView(timeText);
        osdBottom.addView(row);

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        rewBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(-10000);
            }
        });
        fwdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(10000);
            }
        });
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSpeed();
            }
        });
        listBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showListScreen(true);
            }
        });

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM;
        playerScreen.addView(osdBottom, bp);

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(56), dp(56));
        cp.gravity = Gravity.CENTER;
        spinner.setVisibility(View.GONE);
        playerScreen.addView(spinner, cp);

        osdTop.setVisibility(View.GONE);
        osdBottom.setVisibility(View.GONE);
        return playerScreen;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        b.setFocusable(false);             // 焦点由 dispatchKeyEvent 自己画，避免系统抢方向键
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** 自己画焦点：选中的按钮橙色、进度条行聚焦时进度条变橙 */
    private void updateFocusVisual() {
        if (buttons != null) {
            for (int i = 0; i < buttons.length; i++) {
                boolean on = (focusRow == 0 && i == focusIdx);
                buttons[i].setBackgroundColor(Color.parseColor(on ? "#FFFF8A00" : "#33FFFFFF"));
                buttons[i].setTextColor(on ? Color.BLACK : Color.WHITE);
            }
        }
        boolean progOn = (focusRow == 1);
        try {
            android.content.res.ColorStateList cs = android.content.res.ColorStateList.valueOf(
                    progOn ? Color.parseColor("#FFFF8A00") : Color.parseColor("#FFFFFFFF"));
            seekBar.setProgressTintList(cs);
            seekBar.setThumbTintList(cs);
        } catch (Exception ignored) {
        }
        timeText.setTextColor(progOn ? Color.parseColor("#FFFF8A00") : Color.parseColor("#B3FFFFFF"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 列表

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
        hideOsd(false);
        stopPlayer();
        playerScreen.setVisibility(View.GONE);
        listScreen.setVisibility(View.VISIBLE);
        listHeader.setText("广东珠江 · 录制节目" + (items.isEmpty() ? "" : "（" + items.size() + " 期）"));
        if (items.isEmpty()) {
            listStatus.setText("还没有录制，或正在获取…\n每晚 21:00 的 Cloudflare 定时任务会自动录制并上传。");
        } else {
            listStatus.setText("共 " + items.size() + " 期　·　最新的在最上面　·　OK 播放");
        }
        if (requestFocus) {
            list.requestFocus();
            list.setSelection(items.isEmpty() ? 0 : Math.min(currentIndex, items.size() - 1));
        }
    }

    private void showPlayerScreen() {
        playerMode = true;
        listScreen.setVisibility(View.GONE);
        playerScreen.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 播放

    private void play(Recording r) {
        showPlayerScreen();
        prepared = false;
        playing = false;
        speedFactor = 1.0f;
        updateSpeedLabel();
        spinner.setVisibility(View.VISIBLE);
        titleText.setText("广东珠江 · " + r.label());
        timeText.setText("正在打开…");
        stopPlayer();

        exec.execute(new Runnable() {
            @Override
            public void run() {
                final String realUrl = resolveUrl(r);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (surfaceReady) {
                            startPlayback(realUrl);
                        } else {
                            pendingUrl = realUrl;
                        }
                    }
                });
            }
        });
    }

    private String resolveUrl(Recording r) {
        if (proxy != null && proxy.trim().length() > 0) {
            String p = proxy.trim();
            if (!p.endsWith("/")) {
                p = p + "/";
            }
            return p + r.url;
        }
        return GitHubApi.resolveRedirect(r.url, token);
    }

    private void startPlayback(String url) {
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, Uri.parse(url));
            player.setSurface(surface.getHolder().getSurface());
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    spinner.setVisibility(View.GONE);
                    seekBar.setMax(Math.max(1, mp.getDuration()));
                    applySpeed(speedFactor);
                    mp.start();
                    playing = true;
                    updatePlayLabel();
                    showOsd(true);
                    ui.post(ticker);
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    playing = false;
                    updatePlayLabel();
                    toast("播放结束");
                    showListScreen(true);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    spinner.setVisibility(View.GONE);
                    playing = false;
                    toast("播放失败(" + what + "/" + extra + ")，换一期试试");
                    showListScreen(true);
                    return true;
                }
            });
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
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
            player.prepareAsync();
        } catch (Exception e) {
            spinner.setVisibility(View.GONE);
            toast("打不开：" + e.getMessage());
            showListScreen(true);
        }
    }

    private void stopPlayer() {
        ui.removeCallbacks(ticker);
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
        playing = false;
    }

    private int safeDuration() {
        try {
            return player == null ? 0 : player.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    private int safePosition() {
        try {
            return player == null ? 0 : player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private void togglePlay() {
        if (player == null || !prepared) {
            return;
        }
        if (playing) {
            player.pause();
            playing = false;
        } else {
            player.start();
            playing = true;
        }
        updatePlayLabel();
        scheduleHide();
    }

    private void updatePlayLabel() {
        if (playBtn != null) {
            playBtn.setText(playing ? "⏸ 暂停" : "▶ 播放");
        }
    }

    private void doSeek(int ms) {
        if (player == null || !prepared) {
            return;
        }
        player.seekTo(ms);
        updateTimeText(ms, safeDuration());
        scheduleHide();
    }

    private void seekBy(int delta) {
        int dur = safeDuration();
        int target = safePosition() + delta;
        if (target < 0) {
            target = 0;
        }
        if (dur > 0 && target > dur - 800) {
            target = dur - 800;
        }
        doSeek(target);
    }

    private void cycleSpeed() {
        float[] list = new float[]{0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        int idx = 0;
        for (int i = 0; i < list.length; i++) {
            if (Math.abs(list[i] - speedFactor) < 0.01f) {
                idx = i;
                break;
            }
        }
        speedFactor = list[(idx + 1) % list.length];
        applySpeed(speedFactor);
        updateSpeedLabel();
        scheduleHide();
    }

    private void applySpeed(float f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || player == null || !prepared) {
            return;
        }
        try {
            PlaybackParams pp = player.getPlaybackParams();
            pp.setSpeed(f);
            pp.setPitch(1f);
            player.setPlaybackParams(pp);
            // 有些机型 setPlaybackParams 会把暂停中的播放器拉起来
            if (!playing) {
                player.pause();
            }
        } catch (Exception ignored) {
        }
    }

    private void updateSpeedLabel() {
        if (speedBtn != null) {
            speedBtn.setText(String.format(java.util.Locale.US, "%.2fx 倍速", speedFactor));
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (playerMode && prepared) {
                int pos = safePosition();
                int dur = safeDuration();
                if (osdVisible && focusRow != 1) {
                    seekBar.setProgress(pos);
                }
                updateTimeText(pos, dur);
                tickerSpeed();
                ui.postDelayed(this, 500);
            }
        }
    };

    private void updateTimeText(int pos, int dur) {
        if (timeText != null) {
            timeText.setText(mmss(pos) + " / " + mmss(dur));
        }
    }

    private void tickerSpeed() {
        if (!osdVisible) {
            return;
        }
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        if (rx <= 0) {
            speedText.setText("网速 --");
            lastRx = -1;
            return;
        }
        if (lastRx > 0 && rx >= lastRx) {
            long delta = (rx - lastRx) * 2;   // ticker 每 500ms 一次 → 换算成 bytes/s
            speedText.setText(formatSpeed(delta));
        }
        lastRx = rx;
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 0) {
            return "网速 --";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb >= 1024) {
            return String.format(java.util.Locale.US, "网速 %.2f MB/s", kb / 1024.0);
        }
        return String.format(java.util.Locale.US, "网速 %.0f KB/s", kb);
    }

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

    // ---------------------------------------------------------------- OSD

    private void showOsd(boolean autoHide) {
        osdVisible = true;
        osdTop.setVisibility(View.VISIBLE);
        osdBottom.setVisibility(View.VISIBLE);
        focusRow = 0;
        focusIdx = 0;
        updateFocusVisual();
        if (autoHide) {
            scheduleHide();
        }
    }

    private void moveFocus(int delta) {
        if (buttons == null) {
            return;
        }
        focusIdx = (focusIdx + delta + buttons.length) % buttons.length;
        updateFocusVisual();
    }

    private void onOk() {
        if (focusRow == 1) {
            focusRow = 0;
            updateFocusVisual();
            return;
        }
        switch (focusIdx) {
            case 0:
                togglePlay();
                break;
            case 1:
                seekBy(-10000);
                break;
            case 2:
                seekBy(10000);
                break;
            case 3:
                cycleSpeed();
                break;
            case 4:
                showListScreen(true);
                break;
            default:
                break;
        }
    }

    private void hideOsd(boolean focusVideo) {
        osdVisible = false;
        osdTop.setVisibility(View.GONE);
        osdBottom.setVisibility(View.GONE);
        ui.removeCallbacks(hideRunnable);
        if (focusVideo) {
            playerScreen.requestFocus();
        }
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideRunnable);
        ui.postDelayed(hideRunnable, OSD_TIMEOUT_MS);
    }

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hideOsd(true);
        }
    };

    // ---------------------------------------------------------------- 数据

    private void loadReleases(final boolean autoPlay) {
        listStatus.setText("正在获取 " + owner + "/" + repo + " 的录制列表…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                List<Recording> got = null;
                String err = null;
                try {
                    got = GitHubApi.fetchReleases(owner, repo, token, 60);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<Recording> fl = got;
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (dialogOpen || !playerMode || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlay();
            return true;
        }

        if (osdVisible) {
            scheduleHide();          // 任何操作都重置 15 秒自动收起
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    if (focusRow == 1) {
                        seekBy(-10000);
                    } else {
                        moveFocus(-1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    if (focusRow == 1) {
                        seekBy(10000);
                    } else {
                        moveFocus(1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    focusRow = 1;
                    updateFocusVisual();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusRow = 0;
                    updateFocusVisual();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    onOk();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    hideOsd(true);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_SETTINGS:
                    openSettings();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                showOsd(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-10000);
                showOsd(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(10000);
                showOsd(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                showListScreen(true);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                openSettings();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (dialogOpen) {
            return super.onKeyDown(keyCode, event);
        }
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
        stopPlayer();
        exec.shutdownNow();
    }
}
