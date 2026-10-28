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

/** היסטוריית צ'אט בקבצים (לא ב-SharedPreferences): קובץ אחד לכל מכשיר, שורה לכל הודעה. */
final class ChatStore {
    private ChatStore() {}

    static final class Msg {
        final long time;
        final boolean out;
        final String text;
        Msg(long time, boolean out, String text) { this.time = time; this.out = out; this.text = text; }
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

    static synchronized void append(Context c, String address, boolean out, String text, long time) {
        File f = file(c, address);
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            try {
                w.write(time + "|" + (out ? "o" : "i") + "|" + esc(text) + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
            return;
        }
        if (f.length() > 200 * 1024) {
            List<Msg> all = load(c, address);
            int from = Math.max(0, all.size() - MAX_KEEP);
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
                try {
                    for (int i = from; i < all.size(); i++) {
                        Msg m = all.get(i);
                        w.write(m.time + "|" + (m.out ? "o" : "i") + "|" + esc(m.text) + "\n");
                    }
                } finally {
                    w.close();
                }
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
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\\|", 3);
                    if (p.length < 3) continue;
                    try {
                        list.add(new Msg(Long.parseLong(p[0]), "o".equals(p[1]), unesc(p[2])));
                    } catch (NumberFormatException ignored) { }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) { }
        return list;
    }
}