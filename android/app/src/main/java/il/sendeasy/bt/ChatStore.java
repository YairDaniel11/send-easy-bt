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
 * פורמט שורה: זמן|i או o|סוג (t טקסט, v קול, f קובץ)|תוכן.
 * קול: שם הקובץ בתיקיית voice. קובץ: שם הקובץ שהתקבל או נשלח.
 */
final class ChatStore {
    private ChatStore() {}

    static final String T_TEXT = "t", T_VOICE = "v", T_FILE = "f";

    static final class Msg {
        final long time;
        final boolean out;
        final String kind;
        final String text;
        Msg(long time, boolean out, String kind, String text) { this.time = time; this.out = out; this.kind = kind; this.text = text; }
        boolean isVoice() { return T_VOICE.equals(kind); }
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
        return m.time + "|" + (m.out ? "o" : "i") + "|" + m.kind + "|" + esc(m.text) + "\n";
    }

    static void append(Context c, String address, boolean out, String text, long time) {
        add(c, address, new Msg(time, out, T_TEXT, text));
    }

    static synchronized void add(Context c, String address, Msg m) {
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
                        if (p.length == 4 && (T_TEXT.equals(p[2]) || T_VOICE.equals(p[2]) || T_FILE.equals(p[2]))) {
                            list.add(new Msg(Long.parseLong(p[0]), "o".equals(p[1]), p[2], unesc(p[3])));
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