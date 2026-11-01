package il.sendeasy.bt;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.EditText;

/** EditText שמזהה הדבקת תמונה מהלוח (לחיצה ארוכה, הדבק, או Ctrl+V) ומעביר אותה לקולבק במקום להדביק טקסט. */
public class PasteEditText extends EditText {
    interface Listener {
        void onImagePasted(Uri uri);
    }

    private Listener listener;

    public PasteEditText(Context c) { super(c); }
    public PasteEditText(Context c, AttributeSet a) { super(c, a); }
    public PasteEditText(Context c, AttributeSet a, int s) { super(c, a, s); }

    void setImageListener(Listener l) { listener = l; }

    /** ה-URI של התמונה שבלוח, או null אם אין (או שאי אפשר לקרוא). */
    static Uri clipImage(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null) return null;
            ClipDescription desc = cd.getDescription();
            boolean imageMime = desc != null && desc.hasMimeType("image/*");
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u == null) continue;
                if (imageMime) return u;
                String t = c.getContentResolver().getType(u);
                if (t != null && t.startsWith("image/")) return u;
            }
        } catch (RuntimeException ignored) { // כולל SecurityException על URI שפג
        }
        return null;
    }

    /** הטקסט שבלוח, או null אם אין. */
    static CharSequence clipText(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null) return null;
            for (int i = 0; i < cd.getItemCount(); i++) {
                ClipData.Item it = cd.getItemAt(i);
                if (it.getText() != null) return it.getText();
                if (it.getUri() == null) {
                    CharSequence s = it.coerceToText(c);
                    if (s != null && s.length() > 0) return s;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private boolean pasteImage() {
        if (listener == null) return false;
        Uri u = clipImage(getContext());
        if (u == null) return false;
        listener.onImagePasted(u);
        return true;
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste && pasteImage()) return true;
        return super.onTextContextMenuItem(id);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_V && e.isCtrlPressed() && pasteImage()) return true;
        return super.onKeyDown(keyCode, e);
    }
}
