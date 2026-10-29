package il.sendeasy.bt;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * שמירת קבצים שהתקבלו. אנדרואיד 10 ומעלה: MediaStore (אי אפשר לכתוב ישירות ל-Download).
 * עד אנדרואיד 9: קובץ בתיקיית ההורדות הציבורית (דורש הרשאת כתיבה), ואם אין הרשאה:
 * תיקיית האפליקציה, שלא דורשת הרשאה.
 */
final class Storage {
    private Storage() {}

    static final String DIR_NAME = "שליחה קלה";

    /** מנקה שם קובץ מהצד השני: בלי נתיבים, בלי תווים אסורים, בלי שמות שמורים של Windows. */
    static String sanitize(String raw) {
        if (raw == null) return "file";
        String s = raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) c = '_';
            sb.append(c);
        }
        s = sb.toString().trim();
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.length() > 120) {
            int dot = s.lastIndexOf('.');
            String ext = (dot > 0 && s.length() - dot <= 12) ? s.substring(dot) : "";
            s = s.substring(0, 120 - ext.length()) + ext;
        }
        String stem = s;
        int dot = s.indexOf('.');
        if (dot >= 0) stem = s.substring(0, dot);
        String up = stem.toUpperCase(Locale.US);
        if (up.equals("CON") || up.equals("PRN") || up.equals("AUX") || up.equals("NUL")
                || up.matches("COM[1-9]") || up.matches("LPT[1-9]")) {
            s = "_" + s;
        }
        if (s.length() == 0 || s.equals(".") || s.equals("..")) return "file";
        return s;
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    /** תיאור המקום שבו נשמרים קבצים, להצגה למשתמש. */
    static String receiveDirLabel(Context c) {
        if (Build.VERSION.SDK_INT >= 29) return "Download/" + DIR_NAME;
        if (canWritePublic(c)) return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME).getPath();
        File f = c.getExternalFilesDir(null);
        return f == null ? "" : new File(f, DIR_NAME).getPath();
    }

    private static boolean canWritePublic(Context c) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** מפרסם את הקובץ הזמני ביעד הסופי. מחזיר תיאור מיקום, או null בכשל. לא דורס קבצים קיימים. */
    static String publish(Context c, File temp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return publishMediaStore(c, temp, name);
            File dir;
            if (canWritePublic(c)) {
                dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
            } else {
                File base = c.getExternalFilesDir(null);
                if (base == null) base = c.getFilesDir();
                dir = new File(base, DIR_NAME);
            }
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dest = uniqueFile(dir, name);
            copy(new FileInputStream(temp), new FileOutputStream(dest));
            MediaScannerConnection.scanFile(c, new String[]{dest.getAbsolutePath()}, null, null);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    @TargetApi(29)
    private static String publishMediaStore(Context c, File temp, String name) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore לא יצר קובץ");
        try {
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("אין פלט");
            copy(new FileInputStream(temp), os);
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        return "Download/" + DIR_NAME + "/" + name;
    }

    // ---- הודעות קוליות: קובץ voice-yyyyMMdd-HHmmss-d{שניות}.m4a, נשמר בתיקייה פנימית ולא בהורדות ----

    static boolean isVoiceName(String name) {
        return name != null && name.startsWith("voice-") && name.endsWith(".m4a");
    }

    static int voiceSeconds(String name) {
        try {
            int d = name.lastIndexOf("-d");
            int dot = name.lastIndexOf('.');
            if (d >= 0 && dot > d + 2) return Integer.parseInt(name.substring(d + 2, dot));
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    static File voiceDir(Context c) {
        File d = new File(c.getFilesDir(), "voice");
        d.mkdirs();
        return d;
    }

    /** מעתיק הודעה קולית שהתקבלה לתיקייה הפנימית. מחזיר את שם הקובץ הסופי או null. */
    static String storeVoice(Context c, File temp, String name) {
        try {
            File dest = uniqueFile(voiceDir(c), sanitize(name));
            copy(new FileInputStream(temp), new FileOutputStream(dest));
            return dest.getName();
        } catch (Exception e) {
            return null;
        }
    }
    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
        }
    }
}
