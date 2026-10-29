package il.sendeasy.bt;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** בחירת קבצים לשליחה: בורר המערכת בטלפון עם מגע, ודפדפן הקבצים שלנו במכשיר מקשים. */
final class FilePicker {
    private FilePicker() {}

    static final int REQ_PICK = 11;
    static final int REQ_BROWSE = 12;

    static void pick(Activity a) {
        boolean touch = a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        if (touch) {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                a.startActivityForResult(Intent.createChooser(i, a.getString(R.string.act_send_file)), REQ_PICK);
                return;
            } catch (RuntimeException ignored) { }
        }
        a.startActivityForResult(new Intent(a, FileBrowserActivity.class), REQ_BROWSE);
    }

    /** הקבצים שנבחרו (ריק אם הבקשה לא שלנו). */
    static ArrayList<Uri> result(int req, Intent data) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (data == null) return uris;
        if (req == REQ_PICK) {
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        } else if (req == REQ_BROWSE) {
            ArrayList<String> paths = data.getStringArrayListExtra(FileBrowserActivity.EXTRA_PATHS);
            if (paths != null) for (String p : paths) uris.add(Uri.fromFile(new File(p)));
        }
        return uris;
    }

    static void startSend(Context c, String address, List<Uri> uris) {
        Intent i = new Intent(c, SendActivity.class);
        i.putExtra(SendActivity.EXTRA_ADDRESS, address);
        ClipData cd = ClipData.newRawUri("files", uris.get(0));
        for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(i);
    }
}