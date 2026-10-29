package il.sendeasy.bt;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * היסטוריית צ'אט בקבצים (לא ב-SharedPreferences): קובץ אחד לכל מכשיר, שורה לכל הודעה.
 * פורמט שורה: זמן|כיוון|סוג|תוכן. סוג: t טקסט, v קול, f קובץ, p תמונה.
 * כיוון: i או o, ואופציונלית עם מזהה וסטטוס: "o:msgIdHex:סטטוס" (שורות ישנות בלי זה ממשיכות להיקרא).
 * סטטוס יוצא: s נשלחה, d נמסרה, r נקראה. סטטוס נכנס: u לא נקראה, n נקראה (READ עוד לא נשלח), r נקראה ו-READ נשלח.
 * קול/תמונה: שם הקובץ בתיקיית voice / images. קובץ: שם הקובץ שהתקבל או נשלח.
 */
final class ChatStore {
    private ChatStore() {}

    static final String T_TEXT = "t", T_VOICE = "v", T_FILE = "f", T_PIC = "p";
    static final String S_SENT = "s", S_DELIVERED = "d", S_READ = "r", S_UNREAD = "u", S_READ_PENDING = "n";

    static final class Msg {
        final long time;
        final boolean out;
        final String kind;
        final String text;
        final long msgId;
        final String status;
        Msg(long time, boolean out, String kind, String text) { this(time, out, kind, text, 0, out ? S_DELIVERED : S_READ); }
        Msg(long time, boolean out, String kind, String text, long msgId, String status) {
            this.time = time; this.out = out; this.kind = kind; this.text = text; this.msgId = msgId; this.status = status;
        }
        Msg withStatus(String st) { return new Msg(time, out, kind, text, msgId, st); }
        boolean isVoice() { return T_VOICE.equals(kind); }
        boolean isPic() { return T_PIC.equals(kind); }
    }

    private static final int MAX_KEEP = 500;

    private static File file(Context c, String address) {
        File dir = new File(c.getFilesDir(), "chat");
        dir.mkdirs();
        return new File(dir, address.replace(":", "").toLowerCase() + ".log");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String line(Msg m) {
        return m.time + "|" + (m.out ? "o" : "i") + ":" + Long.toHexString(m.msgId) + ":" + m.status + "|" + m.kind + "|" + esc(m.text) + "\n";
    }

    static void append(Context c, String address, boolean out, String text, long time) {
        add(c, address, new Msg(time, out, T_TEXT, text));
    }

    /** מחליף את הקובץ כולו (קורא את הכל, משנה לפי mode, וכותב רק אם משהו השתנה). מחזיר את מספר ההודעות ששונו. */
    private static final int M_OUT_READ = 1, M_IN_PENDING = 2, M_IN_SENT = 3, M_OUT_DELIVERED = 4, M_REMOVE = 5;
    /** READ שהגיע לפני ששמרנו את ההודעה היוצאת (מרוץ קצר): ייושם ב-add. */
    private static final java.util.LinkedHashSet<Long> EARLY = new java.util.LinkedHashSet<Long>();

    private static synchronized int rewrite(Context c, String address, int mode, long id) {
        return rewrite(c, address, mode, id, null);
    }

    private static synchronized int rewrite(Context c, String address, int mode, long id, String key) {
        List<Msg> all = load(c, address);
        int changed = 0;
        for (int i = 0; i < all.size(); i++) {
            Msg m = all.get(i);
            if (mode == M_OUT_READ && m.out && id != 0 && m.msgId == id && !S_READ.equals(m.status)) {
                all.set(i, m.withStatus(S_READ)); changed++;
            } else if (mode == M_IN_PENDING && !m.out && S_UNREAD.equals(m.status)) {
                all.set(i, m.withStatus(m.msgId != 0 ? S_READ_PENDING : S_READ)); changed++;
            } else if (mode == M_OUT_DELIVERED && m.out && key != null && key.equals(m.text) && S_SENT.equals(m.status)) {
                all.set(i, m.withStatus(S_DELIVERED)); changed++;
            } else if (mode == M_REMOVE && m.out && key != null && key.equals(m.text) && S_SENT.equals(m.status)) {
                all.remove(i--); changed++;
            } else if (mode == M_IN_SENT && !m.out && id != 0 && m.msgId == id && S_READ_PENDING.equals(m.status)) {
                all.set(i, m.withStatus(S_READ)); changed++;
            }
        }
        if (changed == 0) return 0;
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file(c, address), false), "UTF-8");
            try { for (Msg m : all) w.write(line(m)); } finally { w.close(); }
        } catch (IOException ignored) { }
        return changed;
    }

    /** הודעה יוצאת עם msgId זה נקראה אצל הצד השני (קיבלנו READ). true אם משהו השתנה. */
    static boolean markRead(Context c, String address, long msgId) {
        synchronized (ChatStore.class) {
            boolean ok = rewrite(c, address, M_OUT_READ, msgId) > 0;
            if (!ok && msgId != 0) {
                EARLY.add(msgId);
                if (EARLY.size() > 64) EARLY.remove(EARLY.iterator().next());
            }
            return ok;
        }
    }

    /** תמונה יוצאת (לפי שם הקובץ) נמסרה: ✓ הופך ל-✓✓. */
    static void markDelivered(Context c, String address, String fileName) {
        rewrite(c, address, M_OUT_DELIVERED, 0, fileName);
    }

    /** מסיר תמונה יוצאת שהשליחה שלה נכשלה או בוטלה. */
    static void removeOut(Context c, String address, String fileName) {
        rewrite(c, address, M_REMOVE, 0, fileName);
    }

    /** המשתמש ראה את ההודעות הנכנסות: הכל נקרא. הודעות עם msgId נשארות "ממתינות ל-READ" עד markReadSent. */
    static boolean markInboundSeen(Context c, String address) {
        return rewrite(c, address, M_IN_PENDING, 0) > 0;
    }

    /** מזהי הודעות נכנסות שנקראו אך עוד לא נשלח עליהן READ. */
    static synchronized List<Long> pendingReadIds(Context c, String address) {
        List<Long> ids = new ArrayList<Long>();
        for (Msg m : load(c, address)) {
            if (!m.out && m.msgId != 0 && S_READ_PENDING.equals(m.status)) ids.add(m.msgId);
        }
        return ids;
    }

    /** נשלח READ על ההודעה הנכנסת: לא נשלח שוב. */
    static void markReadSent(Context c, String address, long msgId) {
        rewrite(c, address, M_IN_SENT, msgId);
    }

    /** מספר ההודעות הנכנסות שלא נקראו. */
    static synchronized int unread(Context c, String address) {
        int n = 0;
        for (Msg m : load(c, address)) if (!m.out && S_UNREAD.equals(m.status)) n++;
        return n;
    }

    static synchronized void add(Context c, String address, Msg m) {
        if (m.out && m.msgId != 0 && EARLY.remove(m.msgId)) m = m.withStatus(S_READ);
        File f = file(c, address);
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            try { w.write(line(m)); } finally { w.close(); }
        } catch (IOException ignored) {
            return;
        }
        if (f.length() > 200 * 1024) {
            List<Msg> all = load(c, address);
            int from = Math.max(0, all.size() - MAX_KEEP);
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
                try { for (int i = from; i < all.size(); i++) w.write(line(all.get(i))); } finally { w.close(); }
            } catch (IOException ignored) { }
        }
    }

    static synchronized List<Msg> load(Context c, String address) {
        List<Msg> list = new ArrayList<Msg>();
        File f = file(c, address);
        if (!f.exists()) return list;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String ln;
                while ((ln = r.readLine()) != null) {
                    String[] p = ln.split("\\|", 4);
                    try {
                        if (p.length == 4 && (T_TEXT.equals(p[2]) || T_VOICE.equals(p[2]) || T_FILE.equals(p[2]) || T_PIC.equals(p[2]))) {
                            String[] d = p[1].split(":");
                            boolean out = "o".equals(d[0]);
                            long id = 0;
                            String st = out ? S_DELIVERED : S_READ;
                            if (d.length >= 3) {
                                id = new java.math.BigInteger(d[1], 16).longValue();
                                if (d[2].length() == 1) st = d[2];
                            }
                            list.add(new Msg(Long.parseLong(p[0]), out, p[2], unesc(p[3]), id, st));
                        } else if (p.length == 3) { // פורמט ישן: בלי סוג
                            list.add(new Msg(Long.parseLong(p[0]), "o".equals(p[1]), T_TEXT, unesc(p[2])));
                        }
                    } catch (NumberFormatException ignored) { }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) { }
        return list;
    }
}