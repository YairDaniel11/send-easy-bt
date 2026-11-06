package il.sendeasy.bt;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/** פרטי מכשיר שנשמרים רק בטלפון הזה: תמונת פרופיל ומספר טלפון (משיוך לאיש קשר). */
final class DeviceProfile {
    private DeviceProfile() {}

    private static final int PHOTO_PX = 192;
    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

    private static File photoFile(Context c, String address) {
        File dir = new File(c.getFilesDir(), "avatars");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, address.replaceAll("[^A-Za-z0-9]", "_") + ".jpg");
    }

    static boolean hasPhoto(Context c, String address) { return photoFile(c, address).isFile(); }

    static void removePhoto(Context c, String address) {
        photoFile(c, address).delete();
        synchronized (CACHE) { CACHE.remove(address); }
    }

    /** שומר תמונה (חיתוך ריבועי ושינוי גודל). false = לא הצליח. */
    static boolean savePhoto(Context c, String address, Uri src) {
        try {
            InputStream in = c.getContentResolver().openInputStream(src);
            if (in == null) return false;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[16 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 30L * 1024 * 1024) return false;
                    bo.write(buf, 0, n);
                }
            } finally { try { in.close(); } catch (IOException ignored) { } }
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= PHOTO_PX) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap full = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (full == null) return false;
            int side = Math.min(full.getWidth(), full.getHeight());
            Bitmap sq = Bitmap.createBitmap(full, (full.getWidth() - side) / 2, (full.getHeight() - side) / 2, side, side);
            Bitmap out = Bitmap.createScaledBitmap(sq, PHOTO_PX, PHOTO_PX, true);
            FileOutputStream fo = new FileOutputStream(photoFile(c, address));
            try { out.compress(Bitmap.CompressFormat.JPEG, 88, fo); } finally { fo.close(); }
            synchronized (CACHE) { CACHE.remove(address); }
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        } catch (OutOfMemoryError e) {
            return false;
        }
    }

    private static Bitmap round(Context c, String address) {
        synchronized (CACHE) {
            Bitmap b = CACHE.get(address);
            if (b != null) return b;
        }
        File f = photoFile(c, address);
        if (!f.isFile()) return null;
        Bitmap src = BitmapFactory.decodeFile(f.getPath());
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(src.getWidth() / 2f, src.getHeight() / 2f, src.getWidth() / 2f, p);
        synchronized (CACHE) { CACHE.put(address, out); }
        return out;
    }

    /** אווטאר של מכשיר: תמונת הפרופיל אם יש, אחרת עיגול עם האות הראשונה. */
    static void applyAvatar(TextView t, String address, String name, int sizeDp) {
        UiKit.avatar(t, name, sizeDp);
        if (address == null) return;
        Bitmap b = round(t.getContext(), address);
        if (b != null) {
            t.setText("");
            t.setBackgroundDrawable(new BitmapDrawable(t.getResources(), b));
        }
    }

    // ---- מספר טלפון ----

    static String phone(Context c, String address) { return Prefs.devPhone(c, address); }

    static void setPhone(Context c, String address, String phone) { Prefs.setDevPhone(c, address, phone); }

    /** תוצאת שיוך לאיש קשר שנבחר בבורר. */
    static final class Contact {
        String name = "";
        String phone = "";
        boolean photoSaved;
    }

    /** קורא איש קשר מ-URI שהבורר החזיר (בלי הרשאת אנשי קשר: ההרשאה ניתנת לשורה שנבחרה), ושומר תמונה אם יש. */
    static Contact importContact(Context c, String address, Uri row) {
        Contact r = new Contact();
        Cursor cur = null;
        String photo = null;
        try {
            cur = c.getContentResolver().query(row, new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                r.name = cur.isNull(0) ? "" : cur.getString(0);
                r.phone = cur.isNull(1) ? "" : cur.getString(1);
                photo = cur.isNull(2) ? null : cur.getString(2);
            }
        } catch (RuntimeException ignored) {
        } finally { if (cur != null) cur.close(); }
        if (photo != null) {
            try { r.photoSaved = savePhoto(c, address, Uri.parse(photo)); } catch (RuntimeException ignored) { }
        }
        return r;
    }
}
