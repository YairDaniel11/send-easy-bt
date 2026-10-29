package il.sendeasy.bt;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
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
            int cut = 120 - ext.length();
            if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) cut--; // לא באמצע זוג חלופי
            s = s.substring(0, cut) + ext;
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

    static String mimeOf(String name) {
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
        String custom = customLabel(c);
        if (custom != null) return custom;
        return defaultLabel(c);
    }

    private static String defaultLabel(Context c) {
        if (Build.VERSION.SDK_INT >= 29) return "Download/" + DIR_NAME;
        if (canWritePublic(c)) return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME).getPath();
        File f = c.getExternalFilesDir(null);
        return f == null ? "" : new File(f, DIR_NAME).getPath();
    }

    // ---- תיקיית שמירה מותאמת ----

    /** האם המשתמש הגדיר תיקייה מותאמת שרלוונטית לגרסת המכשיר (גם אם כרגע אין גישה אליה). */
    static boolean hasCustom(Context c) {
        if (Build.VERSION.SDK_INT >= 21 && Prefs.saveTree(c) != null) return true;
        return Build.VERSION.SDK_INT < 29 && Prefs.saveDir(c) != null;
    }

    /** האם התיקייה המותאמת ניתנת לכתיבה כרגע (הרשאה בתוקף). */
    private static boolean customUsable(Context c) {
        if (Build.VERSION.SDK_INT >= 21 && Prefs.saveTree(c) != null) {
            return hasTreePermission(c, Uri.parse(Prefs.saveTree(c)));
        }
        return Build.VERSION.SDK_INT < 29 && Prefs.saveDir(c) != null && canWritePublic(c);
    }

    /** תיאור התיקייה המותאמת (null אם אין או אם אין גישה אליה). */
    private static String customLabel(Context c) {
        if (!hasCustom(c) || !customUsable(c)) return null;
        if (Build.VERSION.SDK_INT >= 21 && Prefs.saveTree(c) != null) return SafHelper.treeLabel(Uri.parse(Prefs.saveTree(c)));
        return Prefs.saveDir(c);
    }

    static boolean hasTreePermission(Context c, Uri tree) {
        try {
            List<UriPermission> l = c.getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : l) {
                if (p.getUri().equals(tree) && p.isWritePermission()) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static void toastMain(Context c, final int res) {
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(app, res, Toast.LENGTH_LONG).show(); } catch (RuntimeException ignored) { }
            }
        });
    }

    /** שמירה בתיקייה המותאמת. זורק חריגה בכשל (והקובץ הזמני נשאר שלם). */
    private static String publishCustom(Context c, File temp, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 21 && Prefs.saveTree(c) != null) return SafHelper.publishTree(c, temp, name);
        if (!canWritePublic(c)) throw new IOException("אין הרשאת כתיבה");
        File dir = new File(Prefs.saveDir(c));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("אי אפשר ליצור תיקייה");
        File dest = uniqueFile(dir, name);
        if (!temp.renameTo(dest)) copyToFile(temp, dest);
        MediaScannerConnection.scanFile(c, new String[]{dest.getAbsolutePath()}, null, null);
        return dest.getAbsolutePath();
    }

    private static boolean canWritePublic(Context c) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** מפרסם את הקובץ הזמני ביעד הסופי. מחזיר תיאור מיקום, או null בכשל. לא דורס קבצים קיימים. */
    static String publish(Context c, File temp, String name) {
        boolean customFailed = false;
        if (hasCustom(c)) {
            try {
                return publishCustom(c, temp, name);
            } catch (Exception e) {
                customFailed = true; // נופלים לברירת המחדל; הקובץ הזמני עדיין קיים
            }
        }
        String where = publishDefault(c, temp, name);
        if (customFailed && where != null) {
            toastMain(c, R.string.save_fallback_toast);
            return c.getString(R.string.save_fallback, where);
        }
        return where;
    }

    private static String publishDefault(Context c, File temp, String name) {
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
            // אותה מערכת קבצים: שינוי שם מיידי, בלי העתקה ארוכה. אחרת (או בכשל) מעתיקים
            if (!temp.renameTo(dest)) copyToFile(temp, dest);
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
            InputStream is;
            try {
                is = new FileInputStream(temp);
            } catch (IOException e) {
                try { os.close(); } catch (IOException ignored) { }
                throw e;
            }
            copy(is, os);
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        } catch (RuntimeException e) {
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
            copyToFile(temp, dest);
            return dest.getName();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- תמונות בצ'אט: עותק בתיקייה פנימית כדי שאפשר יהיה להציג אחרי השליחה/הקבלה ----

    static boolean isImageName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif")
                || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    static File imagesDir(Context c) {
        File d = new File(c.getFilesDir(), "images");
        d.mkdirs();
        return d;
    }

    /** מעתיק תמונה שהתקבלה לתיקייה הפנימית. מחזיר את שם הקובץ השמור או null. */
    static String storeImage(Context c, File temp, String name) {
        try {
            File dest = uniqueFile(imagesDir(c), sanitize(name));
            copyToFile(temp, dest);
            return dest.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** מעתיק תמונה לשליחה (מ-Uri) לתיקייה הפנימית. מחזיר את הקובץ או null. */
    static File storeImage(Context c, Uri src, String name) {
        File dest = null;
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(src);
            if (in == null) return null;
            dest = uniqueFile(imagesDir(c), sanitize(name));
            OutputStream out = new FileOutputStream(dest);
            copy(in, out); // סוגר את שני הזרמים
            in = null;
            return dest;
        } catch (Exception e) {
            if (dest != null) dest.delete();
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
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

    /** מעתיק קובץ; בכשל באמצע מוחק את היעד החלקי. הזרמים נפתחים בנפרד כדי שלא ידלפו. */
    private static void copyToFile(File src, File dest) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out;
        try {
            out = new FileOutputStream(dest);
        } catch (IOException e) {
            try { in.close(); } catch (IOException ignored) { }
            throw e;
        }
        boolean ok = false;
        try {
            copy(in, out); // סוגר את שני הזרמים
            ok = true;
        } finally {
            if (!ok) dest.delete();
        }
    }

    // ---- בדיקת מקום פנוי לפני קבלה ----

    static final long SPACE_MARGIN = 20L * 1024 * 1024;

    private static long freeBytes(File f) {
        while (f != null && !f.exists()) f = f.getParentFile();
        if (f == null) return Long.MAX_VALUE; // לא ידוע: לא חוסמים
        try {
            return new StatFs(f.getPath()).getAvailableBytes(); // API 18+
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    /** האם יש מקום לקובץ בגודל size בתיקיית ה-cache (קובץ זמני) וביעד הסופי. כולל מרווח של 20MB. */
    static boolean hasSpaceFor(Context c, long size, String name) {
        long need = size + SPACE_MARGIN;
        if (freeBytes(c.getCacheDir()) < need) return false;
        File dest;
        if (isVoiceName(name)) {
            dest = c.getFilesDir();
        } else if (hasCustom(c) && customUsable(c)) {
            if (Build.VERSION.SDK_INT >= 21 && Prefs.saveTree(c) != null) dest = Environment.getExternalStorageDirectory();
            else dest = new File(Prefs.saveDir(c));
        } else if (Build.VERSION.SDK_INT >= 29) {
            dest = Environment.getExternalStorageDirectory();
        } else if (canWritePublic(c)) {
            dest = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
        } else {
            dest = c.getExternalFilesDir(null);
            if (dest == null) dest = c.getFilesDir();
        }
        return freeBytes(dest) >= need;
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
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
