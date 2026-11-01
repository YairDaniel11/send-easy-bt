package il.sendeasy.bt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.io.StreamConnection;

/**
 * חיבור אחד בפרוטוקול הפרטי של "שליחה קלה - BT" (ראה PROTOCOL.md): נפתח על ידינו או התקבל מהמחשב.
 * גרסה ראשונה: הודעות טקסט בלבד. הצעת קובץ נדחית ב"עסוק".
 * הקוד נכתב בתחביר Java 1.3 (CLDC 1.1): בלי generics, בלי for משופר ובלי StringBuilder.
 */
final class Link implements Runnable {
    interface Listener {
        void onText(Link l, String text);
        void onHello(Link l, String peerName);
        void onClosed(Link l, String why);
    }

    static final int T_HELLO = 0x01, T_PING = 0x02, T_PONG = 0x03, T_TEXT = 0x10, T_ACK = 0x11, T_READ = 0x12;
    static final int T_FILE_OFFER = 0x20, T_FILE_REJECT = 0x22, T_ERROR = 0xF0;
    static final int VERSION = 1, MAX_FRAME = 65600, REJECT_BUSY = 4;

    private final StreamConnection conn;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Listener listener;
    private final byte[] deviceId;
    private final String myName;
    private final Object writeLock = new Object();
    private Timer pinger;
    private volatile boolean closed;
    String peerName = "";

    Link(StreamConnection conn, Listener listener, byte[] deviceId, String myName) throws IOException {
        this.conn = conn;
        this.in = conn.openDataInputStream();
        this.out = conn.openDataOutputStream();
        this.listener = listener;
        this.deviceId = deviceId;
        this.myName = myName;
    }

    boolean isClosed() { return closed; }

    void start() {
        new Thread(this).start();
    }

    public void run() {
        String why = null;
        try {
            sendHello();
            pinger = new Timer();
            pinger.schedule(new TimerTask() {
                public void run() {
                    try { send(T_PING, new byte[0]); } catch (IOException e) { close("החיבור נותק"); }
                }
            }, 8000, 8000);
            while (!closed) {
                int len = in.readInt();
                if (len < 2 || len > MAX_FRAME) throw new IOException("מסגרת לא חוקית");
                int type = in.readUnsignedByte();
                in.readUnsignedByte(); // flags
                byte[] p = new byte[len - 2];
                in.readFully(p);
                handle(type, p);
            }
        } catch (IOException e) {
            why = closed ? null : "החיבור נותק";
        } catch (RuntimeException e) {
            why = "שגיאה: " + e;
        }
        close(why);
    }

    private void handle(int type, byte[] p) throws IOException {
        if (type == T_HELLO) {
            if (p.length < 20) return;
            int nl = p[19] & 0xFF;
            if (p.length >= 20 + nl) peerName = utf8(p, 20, nl);
            if ((p[0] & 0xFF) != VERSION) { send(T_ERROR, error(1, "גרסת פרוטוקול לא נתמכת")); close("גרסה שונה"); return; }
            listener.onHello(this, peerName);
        } else if (type == T_PING) {
            send(T_PONG, new byte[0]);
        } else if (type == T_TEXT) {
            if (p.length < 8) return;
            byte[] id = new byte[8];
            System.arraycopy(p, 0, id, 0, 8);
            send(T_ACK, id);
            listener.onText(this, utf8(p, 8, p.length - 8));
            send(T_READ, id); // ההודעה הוצגה למשתמש
        } else if (type == T_FILE_OFFER) {
            if (p.length < 8) return;
            byte[] r = new byte[9];
            System.arraycopy(p, 0, r, 0, 8);
            r[8] = REJECT_BUSY;
            send(T_FILE_REJECT, r); // קבלת קבצים עוד לא נתמכת בגרסה זו
        }
        // כל סוג אחר: מתעלמים (כמו ב-PROTOCOL.md)
    }

    private void sendHello() throws IOException {
        byte[] nb = bytes(myName.length() > 60 ? myName.substring(0, 60) : myName);
        byte[] p = new byte[1 + 2 + 16 + 1 + nb.length];
        p[0] = (byte) VERSION;
        System.arraycopy(deviceId, 0, p, 3, 16);
        p[19] = (byte) nb.length;
        System.arraycopy(nb, 0, p, 20, nb.length);
        send(T_HELLO, p);
    }

    /** שולח הודעת טקסט. מחזיר false אם החיבור סגור. */
    boolean sendText(String text) {
        if (closed) return false;
        try {
            byte[] tb = bytes(text);
            if (tb.length > 16 * 1024) return false;
            byte[] p = new byte[8 + tb.length];
            long id = System.currentTimeMillis();
            for (int i = 7; i >= 0; i--) { p[i] = (byte) (id & 0xFF); id >>= 8; }
            System.arraycopy(tb, 0, p, 8, tb.length);
            send(T_TEXT, p);
            return true;
        } catch (IOException e) {
            close("החיבור נותק");
            return false;
        }
    }

    private void send(int type, byte[] payload) throws IOException {
        synchronized (writeLock) {
            out.writeInt(2 + payload.length);
            out.writeByte(type);
            out.writeByte(0);
            out.write(payload);
            out.flush();
        }
    }

    void close(String why) {
        if (closed) return;
        closed = true;
        if (pinger != null) pinger.cancel();
        try { in.close(); } catch (IOException e) { }
        try { out.close(); } catch (IOException e) { }
        try { conn.close(); } catch (IOException e) { }
        listener.onClosed(this, why);
    }

    private static byte[] error(int code, String msg) {
        byte[] mb = bytes(msg);
        byte[] p = new byte[1 + mb.length];
        p[0] = (byte) code;
        System.arraycopy(mb, 0, p, 1, mb.length);
        return p;
    }

    static byte[] bytes(String s) {
        try { return s.getBytes("UTF-8"); } catch (UnsupportedEncodingException e) { return s.getBytes(); }
    }

    static String utf8(byte[] b, int off, int len) {
        try { return new String(b, off, len, "UTF-8"); } catch (UnsupportedEncodingException e) { return new String(b, off, len); }
    }
}
