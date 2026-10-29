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
    static final class QuietListener implements Session.Listener {
        @Override public void onHello(Session s) { }
        @Override public void onText(Session s, long msgId, String text) { }
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