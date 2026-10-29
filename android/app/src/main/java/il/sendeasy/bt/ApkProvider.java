package il.sendeasy.bt;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** מספק קובץ אחד בלבד, קריאה בלבד: ה-APK של העדכון מתיקיית cache/updates (להתקנה ב-API 24 ומעלה). */
public class ApkProvider extends ContentProvider {

    static Uri uri(Context c) {
        return Uri.parse("content://" + c.getPackageName() + ".apkprovider/" + ApkDownloader.FILE_NAME);
    }

    @Override public boolean onCreate() { return true; }

    /** מחזיר את הקובץ רק אם הנתיב המבוקש הוא בדיוק קובץ העדכון. */
    private File target(Uri u) throws FileNotFoundException {
        Context c = getContext();
        if (c == null || u == null || !("/" + ApkDownloader.FILE_NAME).equals(u.getPath())) throw new FileNotFoundException();
        File f = ApkDownloader.apkFile(c);
        if (!f.isFile()) throw new FileNotFoundException();
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(target(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri u, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = target(u); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = ApkDownloader.FILE_NAME;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        MatrixCursor mc = new MatrixCursor(cols, 1);
        mc.addRow(row);
        return mc;
    }

    @Override public String getType(Uri u) { return "application/vnd.android.package-archive"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
