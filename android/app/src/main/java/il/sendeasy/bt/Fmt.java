package il.sendeasy.bt;

import java.util.Locale;

/** עיצוב טקסט. כל ערך לטיני בתוך טקסט עברי נעטף ב-LRI/PDI כדי שלא יתהפך. */
final class Fmt {
    private Fmt() {}

    /** הודעת שגיאה בעברית: שגיאות מערכת באנגלית (ניתוק, timeout) מוחלפות בהסבר כללי. */
    static String heb(String msg) {
        if (msg != null) for (int i = 0; i < msg.length(); i++) if (msg.charAt(i) >= '֐' && msg.charAt(i) <= '׿') return msg;
        return "החיבור נותק או שהמכשיר השני לא ענה. נסה שוב";
    }

    static String ltr(String s) {
        return "\u2066" + s + "\u2069";
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    static String speed(double bytesPerSec) {
        return size((long) bytesPerSec) + "/s";
    }
}