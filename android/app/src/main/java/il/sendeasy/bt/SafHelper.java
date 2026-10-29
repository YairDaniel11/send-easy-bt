package il.sendeasy.bt;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;

/** קוד שמשתמש ב-API 21 ומעלה (SAF). נטען רק כש-SDK_INT >= 21, כדי שלא ייגע ב-API 19 (VerifyError). */
final class SafHelper {
    private SafHelper() {}

    static String treeLabel(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            if (id.startsWith("primary:")) id = id.substring(8);
            else id = id.replace(':', '/');
            return id.length() == 0 ? "/" : id;
        } catch (RuntimeException e) {
            return tree.toString();
        }
    }

    static String publishTree(Context c, File temp, String name) throws IOException {
        Uri tree = Uri.parse(Prefs.saveTree(c));
        if (!Storage.hasTreePermission(c, tree)) throw new IOException("ההרשאה לתיקייה פקעה");
        ContentResolver cr = c.getContentResolver();
        String parentId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId);
        String finalName = uniqueDocName(cr, tree, parentId, name);
        Uri doc = DocumentsContract.createDocument(cr, parent, Storage.mimeOf(finalName), finalName);
        if (doc == null) throw new IOException("יצירת הקובץ נכשלה");
        try {
            OutputStream os = cr.openOutputStream(doc);
            if (os == null) throw new IOException("אין פלט");
            InputStream is;
            try {
                is = new FileInputStream(temp);
            } catch (IOException e) {
                try { os.close(); } catch (IOException ignored) { }
                throw e;
            }
            Storage.copy(is, os);
        } catch (IOException e) {
            try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
            throw e;
        } catch (RuntimeException e) {
            try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
            throw e;
        }
        return treeLabel(tree) + "/" + finalName;
    }

    /** שם שלא קיים עדיין בתיקייה (name, name (1), ...), כדי לא לדרוס. */
    private static String uniqueDocName(ContentResolver cr, Uri tree, String parentId, String name) {
        HashSet<String> existing = new HashSet<String>();
        Cursor cur = null;
        try {
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            cur = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null) while (cur.moveToNext()) existing.add(cur.getString(0));
        } catch (RuntimeException ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (!existing.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String n = stem + " (" + i + ")" + ext;
            if (!existing.contains(n)) return n;
        }
        return stem + "-" + System.currentTimeMillis() + ext;
    }

    static android.content.Intent treePickerIntent() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    static boolean take(Context c, Uri u) {
        try {
            c.getContentResolver().takePersistableUriPermission(u,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void release(Context c, Uri u) {
        c.getContentResolver().releasePersistableUriPermission(u,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
}
