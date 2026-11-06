package il.sendeasy.bt;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** היסטוריית קבצים שנשלחו ונתקבלו (לא כולל צ'אט): שורה לכל קובץ, האחרונים בסוף. */
final class FileLog {
    private FileLog() {}

    static final class Entry {
        long time;
        boolean sent;
        String device;
        String name;
        long size;
        String loc = ""; // נתיב או URI של הקובץ; ריק = לא ידוע (רשומות ישנות)
    }

    private static final int MAX = 300;

    private static File file(Context c) { return new File(c.getFilesDir(), "filelog.tsv"); }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String line(long time, boolean sent, String device, String name, long size, String loc) {
        return time + "\t" + (sent ? "1" : "0") + "\t" + clean(device) + "\t" + clean(name) + "\t" + size + "\t" + clean(loc) + "\n";
    }

    static void add(Context c, boolean sent, String device, String name, long size) { add(c, sent, device, name, size, null); }

    static synchronized void add(Context c, boolean sent, String device, String name, long size, String loc) {
        try {
            FileOutputStream o = new FileOutputStream(file(c), true);
            try {
                o.write(line(System.currentTimeMillis(), sent, device, name, size, loc).getBytes("UTF-8"));
            } finally { o.close(); }
        } catch (IOException ignored) { }
    }

    /** מהחדש לישן. */
    static synchronized List<Entry> all(Context c) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        File f = file(c);
        if (!f.isFile()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String ln;
                while ((ln = r.readLine()) != null) {
                    String[] p = ln.split("\t", -1);
                    if (p.length < 5) continue;
                    try {
                        Entry e = new Entry();
                        e.time = Long.parseLong(p[0]);
                        e.sent = "1".equals(p[1]);
                        e.device = p[2];
                        e.name = p[3];
                        e.size = Long.parseLong(p[4]);
                        if (p.length > 5) e.loc = p[5];
                        out.add(e);
                    } catch (NumberFormatException ignored) { }
                }
            } finally { r.close(); }
        } catch (IOException ignored) { }
        if (out.size() > MAX) {
            out = new ArrayList<Entry>(out.subList(out.size() - MAX, out.size()));
            try {
                FileOutputStream o = new FileOutputStream(f, false);
                try {
                    for (Entry e : out) o.write(line(e.time, e.sent, e.device, e.name, e.size, e.loc).getBytes("UTF-8"));
                } finally { o.close(); }
            } catch (IOException ignored) { }
        }
        Collections.reverse(out);
        return out;
    }

    /** האם הקובץ שנרשם עדיין קיים. כשלא ידוע (רשומה ישנה, או אין הרשאה לבדוק) נחשב כקיים. */
    static boolean exists(Context c, Entry e) {
        String l = e.loc;
        if (l == null || l.length() == 0) return true;
        try {
            if (l.startsWith("/")) return new File(l).exists();
            android.net.Uri u = android.net.Uri.parse(l);
            if ("file".equals(u.getScheme())) return new File(u.getPath()).exists();
            android.database.Cursor cur = c.getContentResolver().query(u, null, null, null, null);
            if (cur == null) return false;
            try { return cur.getCount() > 0; } finally { cur.close(); }
        } catch (SecurityException ex) {
            return true;
        } catch (RuntimeException ex) {
            return false; // למשל IllegalArgumentException/FileNotFound: המסמך כבר לא קיים
        }
    }

    /** נתיב קובץ אמיתי מ-URI של קובץ לשליחה (כשהספק מגלה אותו), או ריק. */
    static String pathOf(Context c, android.net.Uri u) {
        try {
            if ("file".equals(u.getScheme())) return u.getPath();
            android.database.Cursor cur = c.getContentResolver().query(u, new String[]{"_data"}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst() && !cur.isNull(0)) {
                        String p = cur.getString(0);
                        if (p != null && p.startsWith("/") && new File(p).exists()) return p;
                    }
                } finally { cur.close(); }
            }
        } catch (RuntimeException ignored) { }
        return "";
    }
}
