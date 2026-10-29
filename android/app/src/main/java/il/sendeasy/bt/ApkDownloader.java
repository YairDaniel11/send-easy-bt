package il.sendeasy.bt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/** הורדת ה-APK של העדכון לתיקיית cache/updates, ברקע. כל ההודעות חוזרות בשרשור הממשק. */
final class ApkDownloader {
    static final int ERR_NET = 1, ERR_SIZE = 2, ERR_DISK = 3;
    static final String FILE_NAME = "update.apk";

    interface Listener {
        /** total = 0 כשהגודל לא ידוע. */
        void onProgress(long done, long total);
        void onDone(File apk);
        void onError(int code);
    }

    private static final int MAX_REDIRECTS = 5;

    private final Context app;
    private final String url;
    private final long expected;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile HttpsURLConnection conn;

    private ApkDownloader(Context c, String url, long expected, Listener l) {
        this.app = c.getApplicationContext();
        this.url = url;
        this.expected = expected;
        this.listener = l;
    }

    static File dir(Context c) {
        return new File(c.getCacheDir(), "updates");
    }

    static File apkFile(Context c) {
        return new File(dir(c), FILE_NAME);
    }

    static ApkDownloader start(Context c, String url, long expectedSize, Listener l) {
        final ApkDownloader d = new ApkDownloader(c, url, expectedSize, l);
        new Thread(new Runnable() {
            @Override public void run() { d.run(); }
        }, "apk-download").start();
        return d;
    }

    /** ביטול: לא תגיע אחריו שום הודעה למאזין. */
    void cancel() {
        cancelled = true;
        final HttpsURLConnection c = conn;
        if (c != null) {
            // ניתוק סוקט אסור בשרשור הממשק
            new Thread(new Runnable() {
                @Override public void run() { try { c.disconnect(); } catch (RuntimeException ignored) { } }
            }).start();
        }
    }

    private void run() {
        File d = dir(app);
        File part = new File(d, FILE_NAME + ".part");
        int err = 0;
        try {
            clean(d);
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("mkdirs");
            download(part);
            if (cancelled) { part.delete(); return; }
            if (expected > 0 && part.length() != expected) { part.delete(); err = ERR_SIZE; }
            else {
                File out = apkFile(app);
                if (!part.renameTo(out)) throw new IOException("rename");
                // בגרסאות ישנות המתקין קורא את הקובץ ישירות (file://) ולכן צריך הרשאת קריאה/מעבר
                try { d.setExecutable(true, false); out.setReadable(true, false); } catch (RuntimeException ignored) { }
                if (cancelled) { out.delete(); return; }
                final File fo = out;
                ui.post(new Runnable() { @Override public void run() { if (!cancelled) listener.onDone(fo); } });
                return;
            }
        } catch (DiskException e) {
            part.delete();
            err = ERR_DISK;
        } catch (Throwable e) {
            part.delete();
            err = ERR_NET;
        }
        if (cancelled) return;
        final int fe = err == 0 ? ERR_NET : err;
        ui.post(new Runnable() { @Override public void run() { if (!cancelled) listener.onError(fe); } });
    }

    private static final class DiskException extends IOException {
        DiskException(Throwable t) { super(t.toString()); }
    }

    private static void clean(File d) {
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private void download(File part) throws IOException {
        URL u = new URL(url);
        HttpsURLConnection c = null;
        for (int hop = 0; ; hop++) {
            if (!"https".equals(u.getProtocol())) throw new IOException("not https");
            if (cancelled) throw new IOException("cancelled");
            c = (HttpsURLConnection) u.openConnection();
            conn = c;
            UpdateChecker.configure(c, 20000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (hop >= MAX_REDIRECTS || loc == null) throw new IOException("redirect");
                u = new URL(u, loc);
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("http " + code); }
            break;
        }
        try {
            final long total = expected > 0 ? expected : Math.max(0, c.getContentLength());
            InputStream in = c.getInputStream();
            FileOutputStream out;
            try {
                out = new FileOutputStream(part);
            } catch (IOException e) {
                throw new DiskException(e);
            }
            try {
                byte[] buf = new byte[16384];
                long done = 0, lastPost = 0;
                int n;
                while (!cancelled && (n = in.read(buf)) > 0) {
                    try {
                        out.write(buf, 0, n);
                    } catch (IOException e) {
                        throw new DiskException(e);
                    }
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastPost >= 150) {
                        lastPost = now;
                        postProgress(done, total);
                    }
                }
                if (!cancelled) {
                    postProgress(done, total);
                    // חיבור שנקטע באמצע אחרי כותרת Content-Length נתפס בבדיקת הגודל
                    if (total > 0 && done != total) throw new IOException("short");
                }
            } finally {
                try { out.close(); } catch (IOException ignored) { }
                try { in.close(); } catch (IOException ignored) { }
            }
        } finally {
            c.disconnect();
        }
    }

    private void postProgress(final long done, final long total) {
        ui.post(new Runnable() { @Override public void run() { if (!cancelled) listener.onProgress(done, total); } });
    }
}
