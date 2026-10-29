package il.sendeasy.bt;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * חיבור אחד בפרוטוקול הפרטי (ראה PROTOCOL.md): מסגור, לחיצת יד, פינג, טקסט וקבצים.
 * עובד על זרמים רגילים, ולכן אין תלות בבלוטוס עצמו.
 * הקריאה בשרשור נפרד; כתיבה מוגנת במנעול כך ש-PING משתלב בין חלקי קובץ.
 */
final class Session {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final int R_OK = 0;
    static final int R_REJECTED = 1;
    static final int R_CANCELLED = 2;
    static final int R_HASH = 3;
    static final int R_REMOTE_IO = 4;

    /** קובץ נכנס שנמצא בקבלה. */
    static final class IncomingFile {
        long id;
        long size;
        String name;
        long got;
        File temp;
        FileOutputStream out;
        MessageDigest md;
    }

    interface Listener {
        void onHello(Session s);
        void onText(Session s, long msgId, String text);
        /** 0 = לקבל, אחרת קוד סירוב (Protocol.REJECT_*). נקרא בשרשור הקריאה. */
        int onFileOffer(Session s, IncomingFile f);
        void onFileProgress(Session s, IncomingFile f, long got);
        /** נקרא אחרי שה-SHA-256 תקין. מחזיר true אם הקובץ פורסם ביעד. */
        boolean onFileReceived(Session s, IncomingFile f, File temp);
        void onFileFailed(Session s, IncomingFile f, int status);
        void onClosed(Session s, Exception why);
    }

    interface SendProgress {
        void onProgress(long sent, long total);
    }

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable closer;
    private final byte[] myId;
    private final String myName;
    private final File tmpDir;
    private final Listener listener;
    private final SecureRandom rnd = new SecureRandom();

    private final Object wlock = new Object();
    private final Object sendLock = new Object();
    private final BlockingQueue<long[]> xferEvents = new LinkedBlockingQueue<long[]>();
    private final BlockingQueue<Long> acks = new LinkedBlockingQueue<Long>();
    private final CountDownLatch helloLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile long lastRx = System.currentTimeMillis();
    private volatile boolean cancelIncoming;
    private volatile byte[] peerId;
    private volatile String peerName = "";
    private volatile int lastRejectReason;
    /** מוחלף בצורה אטומית: closeInternal רץ גם מהפינגר, ו-onFileFailed חייב להיקרא פעם אחת בלבד. */
    private final AtomicReference<IncomingFile> incoming = new AtomicReference<IncomingFile>();
    /** true בזמן שהשרשור שלנו עסוק בעבודה מקומית ארוכה (העתקת קובץ): לא נחשב "אין תקשורת". */
    private volatile boolean busy;

    private Thread reader;
    private Thread pinger;

    Session(InputStream in, OutputStream out, Closeable closer, byte[] myId, String myName, File tmpDir, Listener l) {
        this.in = new DataInputStream(in);
        this.out = new DataOutputStream(new BufferedOutputStream(out, 32 * 1024));
        this.closer = closer;
        this.myId = myId;
        this.myName = myName;
        this.tmpDir = tmpDir;
        this.listener = l;
    }

    String peerName() { return peerName; }
    byte[] peerId() { return peerId; }
    boolean isClosed() { return closed.get(); }
    int lastRejectReason() { return lastRejectReason; }

    /** מתחיל לקרוא, שולח HELLO ומפעיל פינג. */
    void start() throws IOException {
        reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "SE-Reader");
        reader.setDaemon(true);
        reader.start();

        byte[] nm = utf8Limit(myName, 64);
        byte[] hello = new byte[1 + 2 + 16 + 1 + nm.length];
        hello[0] = (byte) Protocol.VERSION;
        // caps (2 בייט): 0 בגרסה 1
        System.arraycopy(myId, 0, hello, 3, 16);
        hello[19] = (byte) nm.length;
        System.arraycopy(nm, 0, hello, 20, nm.length);
        send(Protocol.T_HELLO, hello, null, 0, 0);

        pinger = new Thread(new Runnable() {
            @Override public void run() { pingLoop(); }
        }, "SE-Ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    /** ממתין לחיצת היד של הצד השני. */
    boolean awaitHello(long timeoutMs) {
        try {
            return helloLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && !closed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------ כתיבה

    private void send(int type, byte[] head, byte[] body, int off, int len) throws IOException {
        int plen = (head == null ? 0 : head.length) + len;
        synchronized (wlock) {
            if (closed.get()) throw new IOException("החיבור נסגר");
            try {
                out.writeInt(2 + plen);
                out.writeByte(type);
                out.writeByte(0);
                if (head != null) out.write(head);
                if (len > 0) out.write(body, off, len);
                out.flush();
            } catch (IOException e) {
                closeInternal(e);
                throw e;
            }
        }
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) v; v >>= 8; }
        return b;
    }

    private static long getLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    /** מקודד ל-UTF-8 וחותך עד max בייטים על גבול תו (לא באמצע רצף). */
    static byte[] utf8Limit(String s, int max) {
        byte[] b = s.getBytes(UTF8);
        if (b.length <= max) return b;
        int cut = max;
        while (cut > 0 && (b[cut] & 0xC0) == 0x80) cut--; // b[cut] הוא הבייט הראשון שנשמט
        return Arrays.copyOf(b, cut);
    }

    private long newId() {
        long id;
        do { id = rnd.nextLong(); } while (id == 0);
        return id;
    }

    // ------------------------------------------------------------ קריאה

    private void readLoop() {
        Exception why = null;
        try {
            while (!closed.get()) {
                int len = in.readInt();
                if (len < 2 || len > Protocol.MAX_FRAME) throw new IOException("מסגרת לא תקינה (" + len + ")");
                int type = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                byte[] p = new byte[len - 2];
                in.readFully(p);
                lastRx = System.currentTimeMillis();
                if (flags != 0) throw new IOException("דגלים לא נתמכים");
                dispatch(type, p);
            }
        } catch (Exception e) {
            why = e;
        }
        closeInternal(why);
    }

    private void dispatch(int type, byte[] p) throws IOException {
        switch (type) {
            case Protocol.T_HELLO: onHello(p); break;
            case Protocol.T_PING: send(Protocol.T_PONG, null, null, 0, 0); break;
            case Protocol.T_PONG: break;
            case Protocol.T_TEXT: onText(p); break;
            case Protocol.T_ACK:
                if (p.length >= 8) acks.offer(getLong(p, 0));
                break;
            case Protocol.T_FILE_OFFER: onOffer(p); break;
            case Protocol.T_FILE_CHUNK: onChunk(p); break;
            case Protocol.T_FILE_END: onEnd(p); break;
            case Protocol.T_CANCEL:
                if (p.length >= 8) {
                    long id = getLong(p, 0);
                    IncomingFile cur = incoming.get();
                    if (cur != null && cur.id == id) {
                        failIncoming(Protocol.DONE_CANCELLED);
                    } else {
                        xferEvents.offer(new long[]{Protocol.T_CANCEL, id, 0});
                    }
                }
                break;
            case Protocol.T_FILE_ACCEPT:
                if (p.length >= 8) xferEvents.offer(new long[]{type, getLong(p, 0), 0});
                break;
            case Protocol.T_FILE_REJECT:
                if (p.length >= 9) xferEvents.offer(new long[]{type, getLong(p, 0), p[8] & 0xFF});
                break;
            case Protocol.T_FILE_DONE:
                if (p.length >= 9) xferEvents.offer(new long[]{type, getLong(p, 0), p[8] & 0xFF});
                break;
            case Protocol.T_ERROR:
                throw new IOException("הצד השני דיווח על שגיאה");
            default:
                break; // סוג לא מוכר: מתעלמים (תאימות קדימה)
        }
    }

    private void onHello(byte[] p) throws IOException {
        if (p.length < 20) throw new IOException("HELLO קצר מדי");
        int ver = p[0] & 0xFF;
        if (ver != Protocol.VERSION) {
            try { send(Protocol.T_ERROR, concat(new byte[]{1}, "גרסת פרוטוקול לא נתמכת".getBytes(UTF8)), null, 0, 0); } catch (IOException ignored) { }
            throw new IOException("גרסת פרוטוקול לא תואמת: " + ver);
        }
        peerId = Arrays.copyOfRange(p, 3, 19);
        int nl = p[19] & 0xFF;
        if (20 + nl > p.length) nl = p.length - 20;
        peerName = new String(p, 20, nl, UTF8);
        helloLatch.countDown();
        listener.onHello(this);
    }

    private void onText(byte[] p) throws IOException {
        if (p.length < 8) return;
        long id = getLong(p, 0);
        int n = p.length - 8;
        if (n > Protocol.MAX_TEXT_BYTES) return;
        String text = new String(p, 8, n, UTF8);
        listener.onText(this, id, text);
        send(Protocol.T_ACK, longBytes(id), null, 0, 0);
    }

    private void onOffer(byte[] p) throws IOException {
        if (p.length < 18) {
            if (p.length >= 8) {
                send(Protocol.T_FILE_REJECT, concat(longBytes(getLong(p, 0)), new byte[]{(byte) Protocol.REJECT_NAME}), null, 0, 0);
            }
            return; // בלי מזהה אין למי לענות
        }
        long id = getLong(p, 0);
        long size = getLong(p, 8);
        int nl = ((p[16] & 0xFF) << 8) | (p[17] & 0xFF);
        if (nl > Protocol.MAX_NAME_BYTES || 18 + nl > p.length) {
            send(Protocol.T_FILE_REJECT, concat(longBytes(id), new byte[]{(byte) Protocol.REJECT_NAME}), null, 0, 0);
            return;
        }
        String name = Storage.sanitize(new String(p, 18, nl, UTF8));
        if (incoming.get() != null) {
            send(Protocol.T_FILE_REJECT, concat(longBytes(id), new byte[]{(byte) Protocol.REJECT_BUSY}), null, 0, 0);
            return;
        }
        if (name.length() == 0 || size < 0 || size > Protocol.MAX_FILE_SIZE) {
            send(Protocol.T_FILE_REJECT, concat(longBytes(id), new byte[]{(byte) Protocol.REJECT_NAME}), null, 0, 0);
            return;
        }
        IncomingFile f = new IncomingFile();
        f.id = id;
        f.size = size;
        f.name = name;
        int r = listener.onFileOffer(this, f);
        if (r != 0) {
            send(Protocol.T_FILE_REJECT, concat(longBytes(id), new byte[]{(byte) r}), null, 0, 0);
            return;
        }
        try {
            f.md = MessageDigest.getInstance("SHA-256");
            tmpDir.mkdirs();
            f.temp = new File(tmpDir, "rx_" + Long.toHexString(id) + ".part");
            f.out = new FileOutputStream(f.temp);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } catch (IOException e) {
            send(Protocol.T_FILE_REJECT, concat(longBytes(id), new byte[]{(byte) Protocol.REJECT_STORAGE}), null, 0, 0);
            return;
        }
        cancelIncoming = false;
        incoming.set(f);
        send(Protocol.T_FILE_ACCEPT, concat(longBytes(id), longBytes(0)), null, 0, 0);
    }

    private void onChunk(byte[] p) throws IOException {
        IncomingFile f = incoming.get();
        if (f == null || p.length < 8 || getLong(p, 0) != f.id) return;
        if (cancelIncoming) {
            send(Protocol.T_CANCEL, longBytes(f.id), null, 0, 0);
            failIncoming(Protocol.DONE_CANCELLED);
            return;
        }
        int n = p.length - 8;
        if (f.got + n > f.size) {
            send(Protocol.T_FILE_DONE, concat(longBytes(f.id), new byte[]{(byte) Protocol.DONE_IO}), null, 0, 0);
            failIncoming(Protocol.DONE_IO);
            return;
        }
        try {
            f.out.write(p, 8, n);
        } catch (IOException e) {
            send(Protocol.T_FILE_DONE, concat(longBytes(f.id), new byte[]{(byte) Protocol.DONE_IO}), null, 0, 0);
            failIncoming(Protocol.DONE_IO);
            return;
        }
        f.md.update(p, 8, n);
        f.got += n;
        listener.onFileProgress(this, f, f.got);
    }

    private void onEnd(byte[] p) throws IOException {
        IncomingFile f = incoming.get();
        if (f == null || p.length < 40 || getLong(p, 0) != f.id) return;
        int status;
        try {
            f.out.close();
        } catch (IOException e) {
            status = Protocol.DONE_IO;
            send(Protocol.T_FILE_DONE, concat(longBytes(f.id), new byte[]{(byte) status}), null, 0, 0);
            failIncoming(status);
            return;
        }
        byte[] expect = Arrays.copyOfRange(p, 8, 40);
        if (f.got != f.size) {
            status = Protocol.DONE_IO;
        } else if (!MessageDigest.isEqual(expect, f.md.digest())) {
            status = Protocol.DONE_HASH;
        } else {
            // ההעתקה ליעד סינכרונית וארוכה: מסמנים עסוק כדי שהפינגר לא יסגור את החיבור
            boolean ok;
            busy = true;
            try {
                ok = listener.onFileReceived(this, f, f.temp);
            } finally {
                lastRx = System.currentTimeMillis();
                busy = false;
            }
            status = ok ? Protocol.DONE_OK : Protocol.DONE_IO;
        }
        File t = f.temp;
        boolean mine = incoming.compareAndSet(f, null);
        if (t != null) t.delete();
        if (!mine) return; // החיבור נסגר בינתיים ו-closeInternal כבר דיווח כשל
        send(Protocol.T_FILE_DONE, concat(longBytes(f.id), new byte[]{(byte) status}), null, 0, 0);
        if (status != Protocol.DONE_OK) listener.onFileFailed(this, f, status);
    }

    private void failIncoming(int status) {
        IncomingFile f = incoming.getAndSet(null);
        if (f == null) return;
        try { if (f.out != null) f.out.close(); } catch (IOException ignored) { }
        if (f.temp != null) f.temp.delete();
        listener.onFileFailed(this, f, status);
    }

    /** ביטול קובץ נכנס מצד המשתמש (ייכנס לתוקף בחלק הבא שמגיע). */
    void cancelIncomingFile() {
        cancelIncoming = true;
    }

    // ------------------------------------------------------------ פינג

    private void pingLoop() {
        long lastPing = 0;
        while (!closed.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!busy && now - lastRx > Protocol.DEAD_AFTER_MS) {
                closeInternal(new IOException("אין תקשורת מהצד השני"));
                return;
            }
            if (now - lastPing >= Protocol.PING_INTERVAL_MS) {
                lastPing = now;
                try {
                    send(Protocol.T_PING, null, null, 0, 0);
                } catch (IOException e) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------ שליחה

    /** שולח הודעת טקסט וממתין לאישור. מחזיר false אם לא התקבל אישור בזמן. */
    boolean sendText(String text) throws IOException, InterruptedException {
        byte[] t = text.getBytes(UTF8);
        if (t.length > Protocol.MAX_TEXT_BYTES) throw new IOException("ההודעה ארוכה מדי");
        long id = newId();
        acks.clear();
        send(Protocol.T_TEXT, longBytes(id), t, 0, t.length);
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (closed.get()) throw new IOException("החיבור נסגר");
            Long a = acks.poll(200, TimeUnit.MILLISECONDS);
            if (a != null && a == id) return true;
        }
        return false;
    }

    private long[] waitEvent(long id, long timeoutMs, AtomicBoolean cancel) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (closed.get()) throw new IOException("החיבור נסגר");
            if (cancel != null && cancel.get()) return new long[]{Protocol.T_CANCEL, id, 0};
            long[] ev = xferEvents.poll(200, TimeUnit.MILLISECONDS);
            if (ev != null && ev[1] == id) return ev;
        }
        return null;
    }

    /**
     * שולח קובץ אחד. חוסם עד הסיום. מחזיר R_*; בשגיאת תקשורת זורק IOException.
     * קובץ אחד בכל רגע: שליחות נוספות ממתינות בתור.
     */
    int sendFile(InputStream src, String name, long size, SendProgress progress, AtomicBoolean cancel)
            throws IOException, InterruptedException {
        synchronized (sendLock) {
            byte[] nb = utf8Limit(name, Protocol.MAX_NAME_BYTES);
            long id = newId();
            xferEvents.clear();
            byte[] head = new byte[8 + 8 + 2 + nb.length];
            System.arraycopy(longBytes(id), 0, head, 0, 8);
            System.arraycopy(longBytes(size), 0, head, 8, 8);
            head[16] = (byte) (nb.length >> 8);
            head[17] = (byte) nb.length;
            System.arraycopy(nb, 0, head, 18, nb.length);
            send(Protocol.T_FILE_OFFER, head, null, 0, 0);

            long[] ev = waitEvent(id, 60000, cancel);
            if (ev == null) throw new IOException("הצד השני לא הגיב להצעת הקובץ");
            if (ev[0] == Protocol.T_CANCEL) {
                send(Protocol.T_CANCEL, longBytes(id), null, 0, 0);
                return R_CANCELLED;
            }
            if (ev[0] == Protocol.T_FILE_REJECT) {
                lastRejectReason = (int) ev[2];
                return R_REJECTED;
            }

            MessageDigest md;
            try { md = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
            byte[] idb = longBytes(id);
            byte[] buf = new byte[Protocol.CHUNK];
            long sent = 0;
            long lastReport = 0;
            int n;
            while ((n = readFully(src, buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    send(Protocol.T_CANCEL, idb, null, 0, 0);
                    return R_CANCELLED;
                }
                long[] remote = xferEvents.poll();
                if (remote != null && remote[1] == id && remote[0] == Protocol.T_CANCEL) return R_CANCELLED;
                if (remote != null && remote[1] == id && remote[0] == Protocol.T_FILE_DONE) return R_REMOTE_IO;
                md.update(buf, 0, n);
                send(Protocol.T_FILE_CHUNK, idb, buf, 0, n);
                sent += n;
                long now = System.currentTimeMillis();
                if (progress != null && now - lastReport > 150) {
                    lastReport = now;
                    progress.onProgress(sent, size);
                }
            }
            if (sent != size) {
                send(Protocol.T_CANCEL, idb, null, 0, 0);
                throw new IOException("גודל הקובץ השתנה בזמן השליחה");
            }
            if (progress != null) progress.onProgress(sent, size);
            send(Protocol.T_FILE_END, concat(idb, md.digest()), null, 0, 0);

            ev = waitEvent(id, 120000, cancel);
            if (ev == null) throw new IOException("הצד השני לא אישר את סיום הקובץ");
            if (ev[0] == Protocol.T_CANCEL) return R_CANCELLED;
            int status = (int) ev[2];
            if (status == Protocol.DONE_OK) return R_OK;
            if (status == Protocol.DONE_HASH) return R_HASH;
            if (status == Protocol.DONE_CANCELLED) return R_CANCELLED;
            return R_REMOTE_IO;
        }
    }

    private static int readFully(InputStream s, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = s.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    // ------------------------------------------------------------ סגירה

    void close() {
        closeInternal(null);
    }

    private void closeInternal(Exception why) {
        if (!closed.compareAndSet(false, true)) return;
        try { closer.close(); } catch (IOException ignored) { }
        helloLatch.countDown();
        IncomingFile f = incoming.getAndSet(null);
        if (f != null) {
            try { if (f.out != null) f.out.close(); } catch (IOException ignored) { }
            if (f.temp != null) f.temp.delete();
            listener.onFileFailed(this, f, Protocol.DONE_CANCELLED);
        }
        if (pinger != null) pinger.interrupt();
        listener.onClosed(this, why);
    }
}
