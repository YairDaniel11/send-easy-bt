package il.sendeasy.bt;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** בדיקה (עובדת מכל Context) ובקשה (רק מ-Activity) של הרשאות, לפי גרסת אנדרואיד. */
final class Perms {
    private Perms() {}

    static final int REQUEST = 4001;

    static boolean has(Context c, String perm) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** הרשאת בלוטוס לחיבור: חובה מאנדרואיד 12, לפני כן מאושרת בהתקנה. */
    static boolean hasBluetooth(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return has(c, Manifest.permission.BLUETOOTH_CONNECT);
    }

    static boolean hasReadStorage(Context c) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 32) return true;
        return has(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    static List<String> missing(Context c) {
        List<String> m = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31 && !has(c, Manifest.permission.BLUETOOTH_CONNECT)) m.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && !has(c, Manifest.permission.POST_NOTIFICATIONS)) m.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32 && !has(c, Manifest.permission.READ_EXTERNAL_STORAGE)) m.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28 && !has(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)) m.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return m;
    }

    /** מבקש את כל מה שחסר בבת אחת. מחזיר true אם יש מה לבקש. */
    static boolean requestMissing(Activity a) {
        List<String> m = missing(a);
        if (m.isEmpty() || Build.VERSION.SDK_INT < 23) return false;
        a.requestPermissions(m.toArray(new String[0]), REQUEST);
        return true;
    }
}