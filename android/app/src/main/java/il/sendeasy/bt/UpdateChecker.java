package il.sendeasy.bt;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** בדיקת גרסה חדשה ב-GitHub Releases (תגיות android-vX.Y.Z). רצה ברקע ושותקת בכל כשל. */
final class UpdateChecker {
    private UpdateChecker() {}

    static final int NO_NET = 0, FAILED = 1, UP_TO_DATE = 2, NEWER = 3;

    interface Callback { void onResult(int status, String version, String url); }

    private static final String API = "https://api.github.com/repos/YairDaniel11/send-easy-bt/releases?per_page=15";
    private static final String TAG_PREFIX = "android-v";
    private static final long MIN_INTERVAL = 12L * 60 * 60 * 1000;

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * force=true: בדיקה מיידית (מהתפריט) שמדווחת כל תוצאה. אחרת: שקטה, רק אם עברו 12 שעות ויש אינטרנט,
     * ומדווחת רק כשנמצאה גרסה חדשה. התוצאה מגיעה בשרשור הממשק.
     */
    static void check(final Activity act, final boolean force, final Callback cb) {
        final Context app = act.getApplicationContext();
        if (!online(app)) {
            if (force) cb.onResult(NO_NET, null, null);
            return;
        }
        long now = System.currentTimeMillis();
        long last = Prefs.lastUpdateCheck(app);
        if (!force && last <= now && now - last < MIN_INTERVAL) return;
        Prefs.setLastUpdateCheck(app, now);
        new Thread(new Runnable() {
            @Override public void run() {
                String ver = null, url = null;
                int st = FAILED;
                try {
                    String[] r = fetchLatest();
                    if (r != null) {
                        st = UP_TO_DATE;
                        if (isNewer(r[0], installed(app))) { st = NEWER; ver = r[0]; url = r[1]; }
                    }
                } catch (Throwable ignored) { }
                if (!force && st != NEWER) return;
                final int fs = st;
                final String fv = ver, fu = url;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!act.isFinishing()) cb.onResult(fs, fv, fu);
                    }
                });
            }
        }, "update-check").start();
    }

    private static String installed(Context c) throws Exception {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
    }

    /** מחזיר {גרסה, קישור} של ה-Release הראשון של האנדרואיד, או null. */
    private static String[] fetchLatest() throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(API).openConnection();
        try {
            if (Build.VERSION.SDK_INT < 21) c.setSSLSocketFactory(new Tls12Factory(HttpsURLConnection.getDefaultSSLSocketFactory()));
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "SendEasyBT-Android");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 2 * 1024 * 1024) return null;
            }
            JSONArray arr = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String tag = o.optString("tag_name", "");
                if (!tag.startsWith(TAG_PREFIX) || o.optBoolean("draft", false)) continue;
                String ver = tag.substring(TAG_PREFIX.length());
                String url = o.optString("html_url", "");
                if (ver.length() > 0 && url.startsWith("https://")) return new String[]{ver, url};
            }
            return null;
        } finally {
            c.disconnect();
        }
    }

    static boolean isNewer(String remote, String local) {
        int[] a = parts(remote), b = parts(local);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parts(String v) {
        int[] r = new int[3];
        if (v == null) return r;
        String[] p = v.split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            int j = 0;
            while (j < p[i].length() && Character.isDigit(p[i].charAt(j))) j++;
            try { r[i] = j == 0 ? 0 : Integer.parseInt(p[i].substring(0, j)); } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    /** באנדרואיד 4.4 TLS 1.2 כבוי כברירת מחדל ב-SSLSocket, ו-GitHub דורש אותו. */
    private static final class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Tls12Factory(SSLSocketFactory base) { this.base = base; }

        private static Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> on = new ArrayList<String>();
                String[] sup = ss.getSupportedProtocols();
                for (String p : new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}) {
                    for (String q : sup) if (q.equals(p)) { on.add(p); break; }
                }
                if (!on.isEmpty()) ss.setEnabledProtocols(on.toArray(new String[0]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean auto) throws IOException { return fix(base.createSocket(s, host, port, auto)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return fix(base.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException { return fix(base.createSocket(host, port, la, lp)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return fix(base.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws IOException { return fix(base.createSocket(a, port, la, lp)); }
    }
}
