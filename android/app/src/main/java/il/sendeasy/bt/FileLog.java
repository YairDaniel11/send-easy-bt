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
    }

    private static final int MAX = 300;

    private static File file(Context c) { return new File(c.getFilesDir(), "filelog.tsv"); }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String line(long time, boolean sent, String device, String name, long size) {
        return time + "\t" + (sent ? "1" : "0") + "\t" + clean(device) + "\t" + clean(name) + "\t" + size + "\n";
    }

    static synchronized void add(Context c, boolean sent, String device, String name, long size) {
        try {
            FileOutputStream o = new FileOutputStream(file(c), true);
            try {
                o.write(line(System.currentTimeMillis(), sent, device, name, size).getBytes("UTF-8"));
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
                    for (Entry e : out) o.write(line(e.time, e.sent, e.device, e.name, e.size).getBytes("UTF-8"));
                } finally { o.close(); }
            } catch (IOException ignored) { }
        }
        Collections.reverse(out);
        return out;
    }
}
