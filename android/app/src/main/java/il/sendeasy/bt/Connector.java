package il.sendeasy.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;

/** חיבור יוצא אל מכשיר מצומד: cancelDiscovery, שלושה ניסיונות עם השהיה, ולחיצת יד. */
final class Connector {
    private Connector() {}

    static final int ATTEMPTS = 3;

    interface Status {
        void onRetry(int nextAttempt, int max);
        boolean cancelled();
    }

    /**
     * ביטול מיידי של חיבור שמתבצע: sock.connect() לא ניתן להפסקה, אבל סגירת הסוקט מכשילה אותו.
     * הסוקט מוחזק רק בזמן ההתחברות; אחרי חיבור מוצלח הביטול עובר דרך הפרוטוקול (CANCEL).
     */
    static final class Canceller {
        private volatile boolean cancelled;
        private volatile BluetoothSocket sock;

        boolean isCancelled() { return cancelled; }

        void cancel() {
            cancelled = true;
            BluetoothSocket s = sock;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** מאזין לחיבור יוצא: לא מקבל קבצים (מחזיר "עסוק"), ולא עושה דבר עם שאר האירועים. */
    static class QuietListener implements Session.Listener {
        @Override public void onHello(Session s) { }
        @Override public void onText(Session s, long msgId, String text) { }
        @Override public void onRead(Session s, long msgId) { }
        @Override public int onFileOffer(Session s, Session.IncomingFile f) { return Protocol.REJECT_BUSY; }
        @Override public void onFileProgress(Session s, Session.IncomingFile f, long got) { }
        @Override public boolean onFileReceived(Session s, Session.IncomingFile f, File temp) { return false; }
        @Override public void onFileFailed(Session s, Session.IncomingFile f, int status) { }
        @Override public void onClosed(Session s, Exception why) { }
    }

    static String myName(Context c) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null && Perms.hasBluetooth(c)) {
                String n = a.getName();
                if (n != null && n.length() > 0) return n;
            }
        } catch (SecurityException ignored) { }
        return Build.MODEL == null ? "Android" : Build.MODEL;
    }

    static File tmpDir(Context c) {
        File d = new File(c.getCacheDir(), "rx");
        d.mkdirs();
        return d;
    }

    /** קבצים מעל הגודל הזה מנסים לעבור בוויפי (אם שני המכשירים ברשת משותפת). */
    static final long WIFI_MIN_BYTES = 512 * 1024;

    /** מבקש מהמחשב קישור וויפי מוצפן ומתחבר אליו. null אם אין רשת משותפת, ואז ממשיכים בבלוטוס. לא לקרוא משרשור הממשק. */
    static Session tryWifi(Context ctx, Session bt, Session.Listener l) {
        try {
            WifiLink.Offer o = bt.requestWifi(6000);
            if (o == null) return null;
            java.net.Socket sock = WifiLink.connect(o, 3000);
            if (sock == null) return null;
            WifiLink.Channel ch = new WifiLink.Channel(sock, o.key, true);
            Session s = new Session(ch.in, ch.out, sock, Prefs.deviceId(ctx), myName(ctx), tmpDir(ctx), l);
            s.start();
            if (!s.awaitHello(5000)) { s.close(); return null; }
            return s;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** העברה מעל הגודל הזה בלי רשת משותפת: הטלפון פותח נקודה חמה זמנית והמחשב מתחבר אליה. */
    static final long HOTSPOT_MIN_BYTES = 3L * 1024 * 1024;

    /** קישור וויפי דרך נקודה חמה זמנית. {@code end} סוגר אותה (קוראים אחרי סגירת {@code wifi}). */
    static final class Hot {
        Session wifi;
        Runnable end;
    }

    /** פותח נקודה חמה, מבקש מהמחשב להתחבר אליה ופותח קישור מוצפן. null אם אי אפשר. לא לקרוא משרשור הממשק. */
    static Hot tryHotspot(Context ctx, final Session bt, Session.Listener l) {
        if (Build.VERSION.SDK_INT < 26 || (bt.peerCaps() & 4) == 0) return null;
        final DirectHotspot hs = new DirectHotspot(ctx);
        try {
            String[] c = hs.start();
            if (bt.offerHotspot(c[0], c[1], 45000)) {
                Session w = tryWifi(ctx, bt, l);
                if (w != null) {
                    Hot h = new Hot();
                    h.wifi = w;
                    h.end = new Runnable() {
                        @Override public void run() {
                            bt.sendHotspotEnd();
                            hs.stop();
                        }
                    };
                    return h;
                }
            }
        } catch (IOException ignored) { }
        bt.sendHotspotEnd();
        hs.stop();
        return null;
    }

    static Session connect(Context ctx, BluetoothDevice dev, Session.Listener l, Status st)
            throws IOException, InterruptedException {
        return connect(ctx, dev, l, st, null);
    }

    static Session connect(Context ctx, BluetoothDevice dev, Session.Listener l, Status st, Canceller cn)
            throws IOException, InterruptedException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("אין בלוטוס במכשיר");
        if (!adapter.isEnabled()) throw new IOException("הבלוטוס כבוי");
        try {
            adapter.cancelDiscovery(); // גילוי פעיל מאט או מכשיל חיבור
        } catch (SecurityException ignored) { }

        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if ((st != null && st.cancelled()) || (cn != null && cn.cancelled)) throw new IOException("בוטל");
            BluetoothSocket sock = null;
            try {
                sock = dev.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID);
                if (cn != null) {
                    cn.sock = sock;
                    if (cn.cancelled) { // ביטול הגיע לפני ההצבה
                        sock.close();
                        throw new IOException("בוטל");
                    }
                }
                sock.connect();
                Session s = new Session(sock.getInputStream(), sock.getOutputStream(), sock,
                        Prefs.deviceId(ctx), myName(ctx), tmpDir(ctx), l);
                s.start();
                if (!s.awaitHello(8000)) {
                    s.close();
                    throw new IOException("הצד השני לא ענה. ודא שהתוכנה פתוחה במחשב.");
                }
                if (cn != null) cn.sock = null; // מכאן הביטול דרך הפרוטוקול, לא בסגירת הסוקט
                return s;
            } catch (SecurityException e) {
                throw new IOException("אין הרשאת בלוטוס");
            } catch (IOException e) {
                last = e;
                if (sock != null) {
                    try { sock.close(); } catch (IOException ignored) { }
                }
                if (cn != null) cn.sock = null;
                if (cn != null && cn.cancelled) throw new IOException("בוטל");
                if (attempt < ATTEMPTS) {
                    if (st != null) st.onRetry(attempt + 1, ATTEMPTS);
                    Thread.sleep(1500);
                }
            }
        }
        throw last != null ? last : new IOException("החיבור נכשל");
    }
}