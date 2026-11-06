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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 广东珠江录制回放（Android TV）。
 *
 * 启动 → 从 GitHub Release 取最新一期直接播放；
 * 按 OK → 历史录制列表，选中即播；
 * 按菜单 → 设置（仓库 owner/repo、可选 Token）。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gdtv";
    private static final String DEF_OWNER = "daomengkiller";
    private static final String DEF_REPO = "gdtv-zhujiang-recorder";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Recording> items = new ArrayList<>();

    private VideoView video;
    private TextView titleView;
    private TextView statusView;
    private TextView hintView;
    private ProgressBar spinner;

    private SharedPreferences sp;
    private String owner;
    private String repo;
    private String token;

    private boolean dialogOpen = false;
    private boolean hintShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        owner = sp.getString("owner", DEF_OWNER);
        repo = sp.getString("repo", DEF_REPO);
        token = sp.getString("token", "");

        buildUi();
        loadReleases(true);
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vp.gravity = Gravity.CENTER;
        root.addView(video, vp);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                spinner.setVisibility(View.GONE);
                video.start();
                showHint(true);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                showHint(true);
                openHistory();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                spinner.setVisibility(View.GONE);
                statusView.setText("播放失败 what=" + what + " extra=" + extra
                        + "\n按 OK 换一期，或按菜单键检查仓库设置");
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

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(32), dp(20), dp(32), dp(8));

        titleView = new TextView(this);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setTextColor(Color.WHITE);
        titleView.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        titleView.setText("珠江录制回放");
        top.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setTextColor(Color.parseColor("#B3FFFFFF"));
        statusView.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        statusView.setPadding(0, dp(6), 0, 0);
        statusView.setText("正在连接 GitHub…");
        top.addView(statusView);

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP;
        root.addView(top, tp);

        hintView = new TextView(this);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hintView.setTextColor(Color.WHITE);
        hintView.setBackgroundColor(Color.parseColor("#7F000000"));
        hintView.setPadding(dp(24), dp(10), dp(24), dp(10));
        hintView.setText("OK：选择历史录制    ·    菜单键：仓库设置    ·    ← →：快退/快进 10 秒");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(36);
        root.addView(hintView, hp);

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams sp2 = new FrameLayout.LayoutParams(dp(56), dp(56));
        sp2.gravity = Gravity.CENTER;
        spinner.setVisibility(View.GONE);
        root.addView(spinner, sp2);

        setContentView(root);
        root.setFocusable(true);
        root.requestFocus();
    }

    private void showHint(boolean autoHide) {
        hintView.setVisibility(View.VISIBLE);
        if (!hintShown && autoHide) {
            hintShown = true;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hintView.setVisibility(View.GONE);
                }
            }, 8000);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 数据加载

    private void loadReleases(final boolean autoPlay) {
        spinner.setVisibility(View.VISIBLE);
        statusView.setText("正在获取 " + owner + "/" + repo + " 的录制列表…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                List<Recording> list = null;
                String err = null;
                try {
                    list = GitHubApi.fetchReleases(owner, repo, token, 60);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<Recording> fl = list;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        if (fe != null || fl == null) {
                            statusView.setText("获取失败：" + fe + "\n按菜单键检查仓库设置，或再按一次 OK 重试");
                            return;
                        }
                        if (fl.isEmpty()) {
                            statusView.setText("仓库里还没有录制文件。\n"
                                    + "每晚 21:00 的 GitHub Actions 会自动录制并上传。\n"
                                    + "按菜单键可修改仓库地址。");
                            return;
                        }
                        items.clear();
                        items.addAll(fl);
                        statusView.setText("共 " + items.size() + " 期录制 · 按 OK 选择历史 · 按菜单键设置");
                        if (autoPlay) {
                            play(items.get(0));
                        }
                    }
                });
            }
        });
    }

    private void play(final Recording r) {
        spinner.setVisibility(View.VISIBLE);
        titleView.setText("广东珠江 · " + r.label());
        statusView.setText("正在打开：" + r.assetName);
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final String realUrl = GitHubApi.resolveRedirect(r.url, token);
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

    private void openHistory() {
        if (items.isEmpty()) {
            toast("还没有可播放的录制，正在刷新…");
            loadReleases(true);
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = (i == 0 ? "【最新】 " : "         ") + items.get(i).label();
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        dialogOpen = true;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("选择历史录制（OK 播放 / 返回键关闭）")
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        play(items.get(which));
                    }
                })
                .setNegativeButton("关闭", null)
                .setNeutralButton("刷新列表", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        loadReleases(false);
                    }
                })
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                dialogOpen = false;
            }
        });
        d.show();
    }

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

        dialogOpen = true;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
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
                        sp.edit().putString("owner", owner).putString("repo", repo)
                                .putString("token", token).apply();
                        loadReleases(true);
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

    // ------------------------------------------------------------ 遥控器

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (dialogOpen) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                openHistory();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                openSettings();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) {
                    video.pause();
                    statusView.setText("已暂停");
                } else {
                    video.start();
                    statusView.setText("");
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
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                loadReleases(true);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void seekBy(int deltaMs) {
        try {
            int pos = video.getCurrentPosition();
            int dur = video.getDuration();
            int target = pos + deltaMs;
            if (target < 0) {
                target = 0;
            }
            if (dur > 0 && target > dur - 1000) {
                target = dur - 1000;
            }
            video.seekTo(target);
            statusView.setText("跳到 " + (target / 1000 / 60) + ":" + String.format(java.util.Locale.US, "%02d",
                    (target / 1000) % 60));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
    }
}
