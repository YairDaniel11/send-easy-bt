package il.sendeasy.bt;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** מספק כתיבה לקובץ תמונה אחד בתיקיית cache/camera, כדי שאפליקציית המצלמה תשמור בו צילום. */
public class CamProvider extends ContentProvider {
    static final String NAME = "shot.jpg";

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "camera");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File file(Context c) { return new File(dir(c), NAME); }

    static Uri uri(Context c) { return Uri.parse("content://" + c.getPackageName() + ".camprovider/" + NAME); }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        Context c = getContext();
        if (c == null || u == null || !("/" + NAME).equals(u.getPath())) throw new FileNotFoundException();
        int m = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY
                : ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(file(c), m);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return "image/jpeg"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
