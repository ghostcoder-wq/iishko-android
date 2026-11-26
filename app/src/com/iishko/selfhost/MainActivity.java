package com.iishko.selfhost;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "IISHKO";
    private static final int SERVER_PORT = 8091;
    private static final String SERVER_URL = "http://127.0.0.1:" + SERVER_PORT + "/";
    private static final Object LOG_LOCK = new Object();

    private WebView webView;
    private ScrollView statusBox;
    private TextView status;
    private TextView shareBtn;
    private File filesDir;
    private File rootfsDir;
    private File nativeDir;
    private File shmDir;
    private Process server;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF070B18);
            getWindow().setNavigationBarColor(0xFF070B18);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        filesDir = getFilesDir();
        rootfsDir = new File(filesDir, "rootfs");
        nativeDir = new File(filesDir, "native");
        buildUi();
        requestStorage();
        StartUtils.startServerService(this);
        ui.postDelayed(new Runnable() {
            @Override public void run() { requestKeepAlivePermissions(); }
        }, 2000);
        new Thread(new Runnable() {
            @Override public void run() { boot(); }
        }, "iishko-boot").start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        statusBox = new ScrollView(this);
        statusBox.setFillViewport(true);
        statusBox.setVerticalScrollBarEnabled(false);
        statusBox.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable splashBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF070B18, 0xFF101A33, 0xFF070B18});
        splashBg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        statusBox.setBackground(splashBg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(44), dp(24), dp(28));
        statusBox.addView(content, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout logoWrap = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF7C3AED, 0xFF06B6D4});
        logoBg.setCornerRadius(dp(26));
        logoBg.setStroke(dp(1), 0x40FFFFFF);
        logoWrap.setBackground(logoBg);
        logoWrap.setElevation(dp(10));
        TextView logoLetter = new TextView(this);
        logoLetter.setText("I");
        logoLetter.setTextColor(Color.WHITE);
        logoLetter.setTextSize(44);
        logoLetter.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        logoLetter.setGravity(Gravity.CENTER);
        logoWrap.addView(logoLetter, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(88), dp(88));
        logoLp.bottomMargin = dp(18);
        content.addView(logoWrap, logoLp);

        TextView title = new TextView(this);
        title.setText("IISHKO");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(34);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setLetterSpacing(0.16f);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("self-hosted  ·  локальный сервер");
        subtitle.setTextColor(0xFF8FA3BF);
        subtitle.setTextSize(12);
        subtitle.setLetterSpacing(0.06f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(22);
        content.addView(subtitle, subLp);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(0xFF22D3EE));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(168), dp(3));
        progressLp.bottomMargin = dp(24);
        content.addView(progress, progressLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x14FFFFFF);
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), 0x1FFFFFFF);
        card.setBackground(cardBg);
        card.setElevation(dp(2));

        TextView label = new TextView(this);
        label.setText("СОСТОЯНИЕ");
        label.setTextColor(0xFF67E8F9);
        label.setTextSize(10);
        label.setLetterSpacing(0.18f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        card.addView(label);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        status = new TextView(this);
        status.setText("Подготовка Linux...");
        status.setTextSize(12.5f);
        status.setTextColor(0xFFCBD5E1);
        status.setLineSpacing(dp(2), 1f);
        status.setTextIsSelectable(true);
        scroll.addView(status);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(96));
        scrollLp.topMargin = dp(8);
        card.addView(scroll, scrollLp);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));

        shareBtn = new TextView(this);
        shareBtn.setText("Поделиться логом");
        shareBtn.setAllCaps(false);
        shareBtn.setTextColor(0xFF06101F);
        shareBtn.setTextSize(14);
        shareBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        shareBtn.setGravity(Gravity.CENTER);
        shareBtn.setClickable(true);
        shareBtn.setFocusable(true);
        GradientDrawable btnBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF22D3EE, 0xFF818CF8});
        btnBg.setCornerRadius(dp(14));
        shareBtn.setBackground(btnBg);
        shareBtn.setMinHeight(0);
        shareBtn.setMinWidth(0);
        shareBtn.setPadding(dp(18), dp(12), dp(18), dp(12));
        shareBtn.setElevation(dp(4));
        shareBtn.setVisibility(View.GONE);
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareLog(); }
        });
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-1, dp(46));
        shareLp.topMargin = dp(16);
        content.addView(shareBtn, shareLp);

        TextView footer = new TextView(this);
        footer.setText("127.0.0.1:8091  ·  только на этом устройстве");
        footer.setTextColor(0xFF64748B);
        footer.setTextSize(10.5f);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(-2, -2);
        footLp.topMargin = dp(18);
        content.addView(footer, footLp);

        root.addView(statusBox, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        statusBox.setAlpha(0f);
        statusBox.animate().alpha(1f).setDuration(600).start();

        ObjectAnimator pulseX = ObjectAnimator.ofFloat(logoWrap, "scaleX", 1f, 1.04f);
        pulseX.setDuration(1800);
        pulseX.setRepeatCount(ObjectAnimator.INFINITE);
        pulseX.setRepeatMode(ObjectAnimator.REVERSE);
        pulseX.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseX.start();
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(logoWrap, "scaleY", 1f, 1.04f);
        pulseY.setDuration(1800);
        pulseY.setRepeatCount(ObjectAnimator.INFINITE);
        pulseY.setRepeatMode(ObjectAnimator.REVERSE);
        pulseY.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseY.start();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(SERVER_URL) || url.startsWith("http://localhost:" + SERVER_PORT)) return false;
                if (url.contains("t.me/") || url.contains("telegram.me/") || url.startsWith("tg://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        log("no handler for " + url);
                    }
                    return true;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        statusBox.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
    }

    private void requestStorage() {
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        } catch (Throwable t) {
            log("storage permission: " + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        try {
            StartUtils.startServerService(this);
            ServerRunner.restart(this);
            log("permissions result code=" + requestCode);
        } catch (Throwable t) {
            log("permissions result: " + t);
        }
    }

    private void requestKeepAlivePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
            }
        } catch (Throwable t) {
            log("notification permission: " + t);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    log("requested battery optimization exemption");
                } catch (Throwable t) {
                    log("battery dialog failed: " + t);
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            log("battery permission: " + t);
        }
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            log("Xiaomi/HyperOS: включи автозапуск IISHKO в настройках приложения");
        }
    }

    private void boot() {
        try {
            setStatus("Подготовка бинарников...");
            copyNative();
            File ready = new File(rootfsDir, ".ready");
            if (!ready.exists()) {
                setStatus("Распаковка Linux (2/3): rootfs ~1 ГБ. Не закрывай приложение...");
                InputStream in = getAssets().open("rootfs.dat");
                TarExtractor.extractFromGzip(in, rootfsDir, new TarExtractor.Symlinker() {
                    @Override public void symlink(String target, String link) throws Exception {
                        android.system.Os.symlink(target, link);
                    }
                    @Override public void hardlink(String existing, String link) throws Exception {
                        android.system.Os.link(existing, link);
                    }
                });
                in.close();
                new FileWriter(ready).close();
                log("rootfs extracted");
            }
            repairPermissions();
            createMountPoints();
            writeStartScript();
            setStatus("Запуск IISHKO...");
            startServer();
            if (waitForServer(240000)) {
                ui.post(new Runnable() {
                    @Override public void run() { webView.loadUrl(SERVER_URL); }
                });
            } else {
                showFailure();
            }
        } catch (Throwable t) {
            Log.e(TAG, "boot failed", t);
            log("boot failed: " + Log.getStackTraceString(t));
            setStatus("Ошибка: " + t + "\n\n" + readTail(logFile(), 40) + "\n\nЛог:\n" + logPath());
            ui.post(new Runnable() {
                @Override public void run() { shareBtn.setVisibility(View.VISIBLE); }
            });
        }
    }

    private void repairPermissions() {
        File marker = new File(rootfsDir, ".perms");
        if (marker.exists()) return;
        setStatus("Проверка прав rootfs...");
        int[] count = new int[]{0};
        repair(rootfsDir, count);
        try { new FileWriter(marker).close(); } catch (Throwable t) {}
        log("perms repaired: " + count[0]);
    }

    private void repair(File f, int[] count) {
        try {
            StructStat st = Os.lstat(f.getAbsolutePath());
            if (OsConstants.S_ISLNK(st.st_mode)) return;
            if (OsConstants.S_ISDIR(st.st_mode)) {
                Os.chmod(f.getAbsolutePath(), 0755);
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) repair(k, count);
            } else if (OsConstants.S_ISREG(st.st_mode)) {
                Os.chmod(f.getAbsolutePath(), 0755);
                count[0]++;
            }
        } catch (Throwable t) {}
    }

    private void createMountPoints() {
        new File(rootfsDir, "dev/shm").mkdirs();
        new File(rootfsDir, "sdcard").mkdirs();
        shmDir = new File(filesDir, "shm");
        shmDir.mkdirs();
    }

    private void writeStartScript() throws Exception {
        File f = new File(rootfsDir, "root/start.sh");
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileWriter w = new FileWriter(f);
        w.write("#!/bin/bash\n");
        w.write("export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin\n");
        w.write("export HOME=/root USER=root TERM=xterm-256color\n");
        w.write("cd /storage/emulated/0 2>/dev/null || cd /sdcard 2>/dev/null || cd /root\n");
        w.write("exec /lib/ld-linux-aarch64.so.1 /usr/local/bin/bun /root/iishko-app/apps/server/dist/index.mjs --port " + SERVER_PORT + " --host 127.0.0.1 --no-browser\n");
        w.close();
        makeExec(f);
    }

    private void copyNative() throws Exception {
        if (!nativeDir.exists()) nativeDir.mkdirs();
        copyAsset("native/proot", new File(nativeDir, "proot"));
        copyAsset("native/libtalloc.so.2", new File(nativeDir, "libtalloc.so.2"));
        copyAsset("native/libandroid-shmem.so", new File(nativeDir, "libandroid-shmem.so"));
        copyAsset("native/loader", new File(nativeDir, "loader"));
        copyAsset("native/loader32", new File(nativeDir, "loader32"));
        makeExec(new File(nativeDir, "proot"));
        makeExec(new File(nativeDir, "loader"));
        makeExec(new File(nativeDir, "loader32"));
    }

    private void copyAsset(String asset, File dest) throws Exception {
        if (dest.exists() && dest.length() > 0) return;
        InputStream in = getAssets().open(asset);
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } finally {
            in.close();
            out.close();
        }
        makeExec(dest);
    }

    private void makeExec(File f) {
        try {
            f.setReadable(true, false);
            f.setWritable(true, false);
            f.setExecutable(true, false);
        } catch (Throwable t) {}
    }

    private void startServer() throws Exception {
        ServerRunner.start(this);
    }

    private boolean waitForServer(long timeoutMs) {
        long start = System.currentTimeMillis();
        long end = start + timeoutMs;
        long lastNote = 0;
        while (System.currentTimeMillis() < end) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SERVER_URL).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 500) return true;
            } catch (Throwable e) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed - lastNote > 15000) {
                    lastNote = elapsed;
                    log("wait " + (elapsed / 1000) + "s: " + e);
                }
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
        }
        log("server timeout");
        return false;
    }

    private void showFailure() {
        final String tail = readTail(logFile(), 80);
        setStatus("IISHKO не поднялся.\n\n" + tail + "\n\nЛог:\n" + logPath());
        ui.post(new Runnable() {
            @Override public void run() { shareBtn.setVisibility(View.VISIBLE); }
        });
    }

    private void shareLog() {
        try {
            String text = readTail(logFile(), 500);
            if (text.length() == 0) text = "Лог пуст: " + logPath();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "IISHKO selfhost log");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "Отправить лог"));
        } catch (Throwable t) {
            log("share failed: " + t);
        }
    }

    private File publicLogFile() {
        File dir = new File(Environment.getExternalStorageDirectory(), "IISHKO");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "selfhost.log");
    }

    private File appLogFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = filesDir;
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "selfhost.log");
    }

    private String publicLogPath() {
        return publicLogFile().getAbsolutePath();
    }

    private String appLogPath() {
        return appLogFile().getAbsolutePath();
    }

    private String logPath() {
        return publicLogPath() + "\n" + appLogPath();
    }

    private File logFile() {
        File pub = publicLogFile();
        if (pub.exists() && pub.length() > 0) return pub;
        return appLogFile();
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        synchronized (LOG_LOCK) {
            appendLog(publicLogFile(), msg);
            appendLog(appLogFile(), msg);
        }
    }

    private void appendLog(File f, String msg) {
        try {
            if (f.length() > 4L * 1024 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            w.write(ts + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    private String readTail(File f, int lines) {
        ArrayDeque<String> q = new ArrayDeque<String>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            String line;
            while ((line = r.readLine()) != null) {
                if (q.size() >= lines) q.removeFirst();
                q.addLast(line);
            }
            r.close();
        } catch (Throwable t) {
            return "(лог недоступен: " + t + ")";
        }
        StringBuilder b = new StringBuilder();
        for (String line : q) {
            if (b.length() > 0) b.append('\n');
            b.append(line);
        }
        return b.toString();
    }

    private void setStatus(final String text) {
        log(text.replace('\n', ' '));
        ui.post(new Runnable() {
            @Override public void run() {
                status.setText(text);
                statusBox.setVisibility(View.VISIBLE);
            }
        });
    }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) { if (b.length() > 0) b.append(' '); b.append(s); }
        return b.toString();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
