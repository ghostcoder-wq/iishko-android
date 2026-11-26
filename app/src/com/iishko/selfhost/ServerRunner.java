package com.iishko.selfhost;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ServerRunner {
    private static final String TAG = "IISHKO";
    private static final int SERVER_PORT = 8091;
    private static final Object LOCK = new Object();
    private static final Object LOG_LOCK = new Object();

    private static Process server;
    private static Thread watchdog;

    private ServerRunner() {}

    public static boolean isAlive() {
        synchronized (LOCK) {
            return server != null && server.isAlive();
        }
    }

    public static Process process() {
        synchronized (LOCK) {
            return server;
        }
    }

    public static void restart(Context context) {
        synchronized (LOCK) {
            if (server != null) {
                try { server.destroy(); } catch (Throwable ignored) {}
                server = null;
            }
        }
        start(context);
    }

    public static void start(Context context) {
        final Context ctx = context.getApplicationContext();
        synchronized (LOCK) {
            if (watchdog != null && watchdog.isAlive()) return;
            watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        Process p = null;
                        synchronized (LOCK) {
                            if (server == null || !server.isAlive()) {
                                try {
                                    server = launch(ctx);
                                } catch (Throwable t) {
                                    log(ctx, "server launch failed: " + t);
                                }
                            }
                            p = server;
                        }
                        if (p == null) {
                            sleep(5000);
                            continue;
                        }
                        try {
                            int code = p.waitFor();
                            log(ctx, "server exited code=" + code);
                        } catch (Throwable t) {
                            log(ctx, "server wait failed: " + t);
                        }
                        synchronized (LOCK) {
                            if (server == p) server = null;
                        }
                        sleep(3000);
                    }
                }
            }, "iishko-watchdog");
            watchdog.start();
        }
    }

    private static Process launch(Context ctx) throws Exception {
        File files = ctx.getFilesDir();
        File rootfs = new File(files, "rootfs");
        if (!new File(rootfs, ".ready").exists()) return null;
        if (!new File(rootfs, ".perms").exists()) return null;

        new File(rootfs, "dev/shm").mkdirs();
        new File(rootfs, "sdcard").mkdirs();

        File nativeDir = new File(files, "native");
        File proot = new File(nativeDir, "proot");
        File loader = new File(nativeDir, "loader");
        File bun = new File(rootfs, "usr/local/bin/bun");
        if (!proot.exists() || !loader.exists() || !bun.exists()) return null;

        ensureStartScript(rootfs);

        File tmp = new File(files, "tmp");
        if (!tmp.exists()) tmp.mkdirs();
        File shm = new File(files, "shm");
        if (!shm.exists()) shm.mkdirs();

        List<String> cmd = new ArrayList<String>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("--sysvipc");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/dev/urandom:/dev/random");
        cmd.add("-b"); cmd.add("/proc/self/fd:/dev/fd");
        cmd.add("-b"); cmd.add("/proc/self/fd/0:/dev/stdin");
        cmd.add("-b"); cmd.add("/proc/self/fd/1:/dev/stdout");
        cmd.add("-b"); cmd.add("/proc/self/fd/2:/dev/stderr");
        cmd.add("-b"); cmd.add(shm.getAbsolutePath() + ":/dev/shm");

        File ext = Environment.getExternalStorageDirectory();
        boolean extOk = ext != null && ext.canRead();
        File appExt = ctx.getExternalFilesDir(null);
        if (extOk) {
            new File(rootfs, "storage/emulated/0").mkdirs();
            cmd.add("-b"); cmd.add(ext.getAbsolutePath() + ":/storage/emulated/0");
            cmd.add("-b"); cmd.add(ext.getAbsolutePath() + ":/sdcard");
        } else if (appExt != null) {
            appExt.mkdirs();
            cmd.add("-b"); cmd.add(appExt.getAbsolutePath() + ":/sdcard");
        }
        cmd.add("/bin/bash");
        cmd.add("/root/start.sh");

        final String[] arr = cmd.toArray(new String[0]);
        ProcessBuilder pb = new ProcessBuilder(arr);
        pb.directory(files);
        pb.redirectErrorStream(true);
        pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        pb.environment().put("PROOT_LOADER_32", new File(nativeDir, "loader32").getAbsolutePath());
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        pb.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        pb.environment().put("HOME", "/root");
        pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");

        log(ctx, "exec: " + join(arr));
        final Process p = pb.start();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) log(ctx, "srv: " + line);
                } catch (Throwable t) {
                    log(ctx, "srv read: " + t);
                }
            }
        }, "iishko-log").start();
        return p;
    }

    private static void ensureStartScript(File rootfs) throws Exception {
        File f = new File(rootfs, "root/start.sh");
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileWriter w = new FileWriter(f);
        w.write("#!/bin/bash\n");
        w.write("export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin\n");
        w.write("export HOME=/root USER=root TERM=xterm-256color\n");
        w.write("cd /storage/emulated/0 2>/dev/null || cd /sdcard 2>/dev/null || cd /root\n");
        w.write("exec /lib/ld-linux-aarch64.so.1 /usr/local/bin/bun /root/iishko-app/apps/server/dist/index.mjs --port " + SERVER_PORT + " --host 127.0.0.1 --no-browser\n");
        w.close();
        f.setExecutable(true, false);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) {
            if (b.length() > 0) b.append(' ');
            b.append(s);
        }
        return b.toString();
    }

    static void log(Context ctx, String msg) {
        Log.i(TAG, msg);
        synchronized (LOG_LOCK) {
            append(publicLog(ctx), msg);
            append(appLog(ctx), msg);
        }
    }

    private static File publicLog(Context ctx) {
        File dir = new File(Environment.getExternalStorageDirectory(), "IISHKO");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "selfhost.log");
    }

    private static File appLog(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "selfhost.log");
    }

    private static void append(File f, String msg) {
        try {
            if (f.length() > 4L * 1024 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            w.write(ts + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }
}
