package il.sendeasy.bt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * קישור TCP מוצפן ברשת מקומית משותפת (בלי אינטרנט), כמו WifiLink.cs במחשב. המפרט ב-PROTOCOL.md.
 * ההצעה (פורט, מפתח חד-פעמי, כתובות) עוברת רק בתוך חיבור הבלוטוס, והמידע ב-TCP מוצפן AES-256-GCM.
 */
final class WifiLink {
    private WifiLink() {}

    /** ביט 0 ב-caps של HELLO. */
    static final int CAP = 1;
    private static final int MAX_PLAIN = 60 * 1024;

    static final class Offer {
        final int port;
        final byte[] key;
        final String[] ips;

        Offer(int port, byte[] key, String[] ips) { this.port = port; this.key = key; this.ips = ips; }

        byte[] toBytes() {
            int n = Math.min(ips.length, 8);
            byte[] b = new byte[2 + 32 + 1 + 4 * n];
            b[0] = (byte) (port >> 8);
            b[1] = (byte) port;
            System.arraycopy(key, 0, b, 2, 32);
            b[34] = (byte) n;
            for (int i = 0; i < n; i++) {
                try {
                    byte[] a = InetAddress.getByName(ips[i]).getAddress();
                    System.arraycopy(a, 0, b, 35 + 4 * i, 4);
                } catch (IOException ignored) { }
            }
            return b;
        }

        static Offer parse(byte[] p) {
            if (p.length < 35) return null;
            int n = p[34] & 0xFF;
            if (p.length < 35 + 4 * n) return null;
            String[] ips = new String[n];
            for (int i = 0; i < n; i++) {
                int o = 35 + 4 * i;
                ips[i] = (p[o] & 0xFF) + "." + (p[o + 1] & 0xFF) + "." + (p[o + 2] & 0xFF) + "." + (p[o + 3] & 0xFF);
            }
            byte[] key = new byte[32];
            System.arraycopy(p, 2, key, 0, 32);
            return new Offer(((p[0] & 0xFF) << 8) | (p[1] & 0xFF), key, ips);
        }
    }

    /** כתובות IPv4 של כרטיסי רשת פעילים (לא לולאה ולא 169.254). */
    static String[] localIps() {
        List<String> r = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    String s = a.getHostAddress();
                    if (a.getAddress().length == 4 && !s.startsWith("169.254.") && !r.contains(s)) r.add(s);
                }
            }
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) { }
        return r.toArray(new String[0]);
    }

    /** מאזין לחיבור אחד. */
    static final class Listener {
        final Offer offer;
        private final ServerSocket ss;

        private Listener(Offer o, ServerSocket ss) { this.offer = o; this.ss = ss; }

        /** ממתין לחיבור ומחזיר סוקט, או null בפקיעת הזמן. נסגר בכל מקרה. */
        Socket accept(int timeoutMs) {
            try {
                ss.setSoTimeout(timeoutMs);
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                return null;
            } finally {
                try { ss.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** null אם אין רשת או שאי אפשר לפתוח פורט. */
    static Listener listen() {
        String[] ips = localIps();
        if (ips.length == 0) return null;
        try {
            ServerSocket ss = new ServerSocket(0);
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return new Listener(new Offer(ss.getLocalPort(), key, ips), ss);
        } catch (IOException e) {
            return null;
        }
    }

    /** מתחבר לכל הכתובות במקביל; הראשון שמצליח מנצח. null אם אין נתיב. */
    static Socket connect(final Offer o, final int timeoutMs) {
        final AtomicReference<Socket> won = new AtomicReference<Socket>();
        final CountDownLatch done = new CountDownLatch(o.ips.length);
        for (final String ip : o.ips) {
            new Thread(new Runnable() {
                @Override public void run() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(ip, o.port), timeoutMs);
                        s.setTcpNoDelay(true);
                        if (!won.compareAndSet(null, s)) s.close();
                    } catch (IOException e) {
                        try { s.close(); } catch (IOException ignored) { }
                    } finally {
                        done.countDown();
                    }
                }
            }, "SE-WifiConnect").start();
        }
        try {
            done.await(timeoutMs + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return won.get();
    }

    /** זרמים מוצפנים מעל סוקט: כל מסגרת [u32 אורך][AES-GCM(מטען)+תג 16], nonce = 4 אפסים + מונה u64, מפתח לכל כיוון. */
    static final class Channel {
        final InputStream in;
        final OutputStream out;

        Channel(Socket sock, byte[] key, boolean client) throws IOException {
            byte[] c2s = hmac(key, "se-wifi c2s");
            byte[] s2c = hmac(key, "se-wifi s2c");
            this.in = new SecureIn(sock.getInputStream(), client ? s2c : c2s);
            this.out = new SecureOut(sock.getOutputStream(), client ? c2s : s2c);
        }
    }

    private static byte[] hmac(byte[] key, String text) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(text.getBytes("US-ASCII"));
        } catch (GeneralSecurityException e) {
            throw new IOException(e.toString());
        }
    }

    private static byte[] nonce(long n) {
        byte[] b = new byte[12];
        for (int i = 11; i >= 4; i--) { b[i] = (byte) n; n >>>= 8; }
        return b;
    }

    private static Cipher cipher(int mode, byte[] key, long counter) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(counter)));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException(e.toString());
        }
    }

    private static final class SecureOut extends OutputStream {
        private final OutputStream net;
        private final byte[] key;
        private final byte[] buf = new byte[MAX_PLAIN];
        private int len;
        private long counter;

        SecureOut(OutputStream net, byte[] key) { this.net = net; this.key = key; }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int n) throws IOException {
            while (n > 0) {
                int k = Math.min(n, MAX_PLAIN - len);
                System.arraycopy(b, off, buf, len, k);
                len += k; off += k; n -= k;
                if (len == MAX_PLAIN) flushFrame();
            }
        }

        private void flushFrame() throws IOException {
            if (len == 0) return;
            try {
                byte[] ct = cipher(Cipher.ENCRYPT_MODE, key, counter++).doFinal(buf, 0, len);
                len = 0;
                byte[] hdr = {(byte) (ct.length >>> 24), (byte) (ct.length >>> 16), (byte) (ct.length >>> 8), (byte) ct.length};
                net.write(hdr);
                net.write(ct);
            } catch (GeneralSecurityException e) {
                throw new IOException(e.toString());
            }
        }

        @Override public void flush() throws IOException {
            flushFrame();
            net.flush();
        }

        @Override public void close() throws IOException { net.close(); }
    }

    private static final class SecureIn extends InputStream {
        private final InputStream net;
        private final byte[] key;
        private byte[] plain = new byte[0];
        private int pos;
        private long counter;

        SecureIn(InputStream net, byte[] key) { this.net = net; this.key = key; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int n) throws IOException {
            if (n == 0) return 0;
            if (pos >= plain.length) {
                byte[] hdr = new byte[4];
                if (!full(hdr, true)) return -1;
                int len = ((hdr[0] & 0xFF) << 24) | ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
                if (len < 16 || len > MAX_PLAIN + 16) throw new IOException("מסגרת וויפי לא תקינה");
                byte[] ct = new byte[len];
                full(ct, false);
                try {
                    plain = cipher(Cipher.DECRYPT_MODE, key, counter++).doFinal(ct);
                } catch (GeneralSecurityException e) {
                    throw new IOException("אימות קישור הוויפי נכשל");
                }
                pos = 0;
            }
            int k = Math.min(n, plain.length - pos);
            System.arraycopy(plain, pos, b, off, k);
            pos += k;
            return k;
        }

        /** true אם נקרא הכל; false רק בסוף זרם נקי בתחילת מסגרת (eofOk). */
        private boolean full(byte[] b, boolean eofOk) throws IOException {
            int got = 0;
            while (got < b.length) {
                int r = net.read(b, got, b.length - got);
                if (r < 0) {
                    if (got == 0 && eofOk) return false;
                    throw new IOException("החיבור נסגר");
                }
                got += r;
            }
            return true;
        }

        @Override public void close() throws IOException { net.close(); }
    }
}
