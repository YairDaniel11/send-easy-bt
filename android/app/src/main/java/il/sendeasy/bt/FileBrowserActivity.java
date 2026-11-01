package il.sendeasy.bt;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * דפדפן קבצים משלנו, כי בורר הקבצים של המערכת לא תמיד מוצג במכשירי מקשים.
 * אישור = כניסה לתיקייה או סימון קובץ, תפריט = שלח את הנבחרים, חזרה = תיקייה למעלה.
 */
public class FileBrowserActivity extends Activity {
    static final String EXTRA_PATHS = "paths";
    /** true = מציג תיקיות וקבצי תמונה בלבד (לכפתור הצירוף בצ'אט). */
    static final String EXTRA_IMAGES_ONLY = "imagesOnly";

    /** true = מצב בחירת תיקייה: רק תיקיות, תפריט = בחר את התיקייה הנוכחית, כוכבית = תיקייה חדשה. */
    static final String EXTRA_PICK_DIR = "pickDir";
    /** התוצאה במצב בחירת תיקייה: נתיב התיקייה שנבחרה. */
    static final String EXTRA_DIR = "dir";

    private boolean imagesOnly;
    private boolean pickDir;

    private ListView list;
    private TextView pathT, countT, emptyT;
    private File root;
    private File dir; // null = רשימת האחסונים (כשיש יותר מאחד)
    private final List<File> volumes = new ArrayList<File>();
    private static final File CHOOSE = new File("<choose>");
    private final List<File> entries = new ArrayList<File>();
    private final Set<String> selected = new HashSet<String>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        try {
            init();
        } catch (Throwable t) {
            // במקום קריסה שקטה: מציגים מה נכשל, כדי שאפשר יהיה לתקן (במיוחד באנדרואיד 4.4)
            Toast.makeText(this, "שגיאה בפתיחת הדפדפן: " + t, Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void init() {
        setContentView(R.layout.activity_browser);
        ThemeUtil.forceRtl(this);
        list = (ListView) findViewById(R.id.browserList);
        pathT = (TextView) findViewById(R.id.browserPath);
        countT = (TextView) findViewById(R.id.browserCount);
        emptyT = (TextView) findViewById(R.id.browserEmpty);
        // root/dir חייבים להיות מוגדרים לפני setAdapter: ב-4.4 הוא קורא מיד ל-getCount ← hasUp
        root = Environment.getExternalStorageDirectory();
        dir = root;
        findVolumes();
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { onEntry(pos); }
        });

        imagesOnly = getIntent().getBooleanExtra(EXTRA_IMAGES_ONLY, false);
        pickDir = getIntent().getBooleanExtra(EXTRA_PICK_DIR, false);
        if (imagesOnly) setTitle(R.string.browser_images_title);
        if (pickDir) {
            setTitle(R.string.browser_dir_title);
            TextView hint = (TextView) findViewById(R.id.browserHint);
            if (hint != null) hint.setText(R.string.browser_dir_hint);
            try {
                String start = Prefs.saveDir(this);
                if (start != null) {
                    File sf = new File(start);
                    if (sf.isDirectory() && volumeOf(sf) != null) dir = sf;
                }
            } catch (RuntimeException ignored) { }
        }
        if (!Perms.hasReadStorage(this)) {
            Toast.makeText(this, R.string.browser_no_perm, Toast.LENGTH_LONG).show();
            Perms.requestMissing(this);
        }
        load();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        load();
    }

    private void load() {
        try {
            loadInner();
        } catch (RuntimeException e) {
            Toast.makeText(this, "שגיאה בטעינת התיקייה: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void loadInner() {
        entries.clear();
        File[] kids = null;
        if (dir == null) {
            entries.addAll(volumes);
            pathT.setText("אחסון");
            emptyT.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
            list.setSelection(0);
            list.requestFocus();
            updateCount();
            return;
        }
        try { kids = dir.listFiles(); } catch (RuntimeException ignored) { }
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File c) {
                    if (a.isDirectory() != c.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(c.getName());
                }
            });
            for (File k : kids) {
                if (k.getName().startsWith(".")) continue;
                if (pickDir && !k.isDirectory()) continue;
                if (imagesOnly && !k.isDirectory() && !Storage.isImageName(k.getName())) continue;
                entries.add(k);
            }
        }
        // ellipsize="start" עם עברית/כיווניות מעורבת קורס באנדרואיד 4.x, לכן מקצרים ידנית מההתחלה
        String shown = dir.getPath();
        if (shown.length() > 38) shown = "…" + shown.substring(shown.length() - 37);
        pathT.setText(Fmt.ltr(shown));
        emptyT.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
        updateCount();
    }

    private void addVolume(File f) {
        try {
            String c = f.getCanonicalPath();
            for (File v : volumes) if (v.getCanonicalPath().equals(c)) return;
            volumes.add(f);
        } catch (java.io.IOException ignored) { }
    }

    // אחסון פנימי + כרטיס זיכרון / USB אם קיימים
    private void findVolumes() {
        volumes.clear();
        addVolume(root);
        try {
            File[] fs = getExternalFilesDirs(null);
            if (fs != null) for (File f : fs) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int i = p.indexOf("/Android/data");
                if (i > 0) addVolume(new File(p.substring(0, i)));
            }
        } catch (RuntimeException ignored) { }
        for (String base : new String[]{"/storage", "/mnt"}) {
            try {
                File[] kids = new File(base).listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    String n = k.getName().toLowerCase(java.util.Locale.US);
                    if (n.equals("emulated") || n.equals("self") || n.startsWith(".")) continue;
                    if (base.equals("/mnt") && !(n.contains("sd") || n.contains("ext") || n.contains("usb") || n.contains("card"))) continue;
                    File[] in = k.isDirectory() ? k.listFiles() : null;
                    if (in != null && in.length > 0) addVolume(k);
                }
            } catch (RuntimeException ignored) { }
        }
    }

    private File volumeOf(File f) {
        try {
            String p = f.getCanonicalPath();
            for (File v : volumes) {
                String vp = v.getCanonicalPath();
                if (p.equals(vp) || p.startsWith(vp + "/")) return v;
            }
        } catch (java.io.IOException ignored) { }
        return null;
    }

    private boolean isVolumeRoot(File f) {
        try {
            String p = f.getCanonicalPath();
            for (File v : volumes) if (v.getCanonicalPath().equals(p)) return true;
        } catch (java.io.IOException ignored) { }
        return false;
    }

    private String volumeLabel(File v) {
        try {
            if (v.getCanonicalPath().equals(root.getCanonicalPath())) return "אחסון פנימי";
        } catch (java.io.IOException ignored) { }
        return "כרטיס זיכרון (" + v.getName() + ")";
    }

    private boolean hasChoose() {
        return pickDir && dir != null;
    }

    private boolean hasUp() {
        if (dir == null) return false;
        if (isVolumeRoot(dir)) return volumes.size() > 1;
        return dir.getParentFile() != null;
    }

    // שורה ראשונה: "בחר תיקייה זו" (בבחירת תיקייה), אחריה ".." כשאפשר לעלות
    private File entryAt(int pos) {
        if (hasChoose()) {
            if (pos == 0) return CHOOSE;
            pos--;
        }
        if (hasUp()) return pos == 0 ? null : entries.get(pos - 1);
        return entries.get(pos);
    }

    private void onEntry(int pos) {
        File f = entryAt(pos);
        if (f == null) { up(); return; }
        if (f == CHOOSE) { done(); return; }
        if (f.isDirectory()) {
            dir = f;
            load();
        } else {
            String p = f.getAbsolutePath();
            if (!selected.remove(p)) selected.add(p);
            adapter.notifyDataSetChanged();
            updateCount();
        }
    }

    private void up() {
        if (hasUp()) {
            File prev = dir;
            dir = isVolumeRoot(dir) ? null : dir.getParentFile();
            load();
            int idx = entries.indexOf(prev);
            if (idx >= 0) list.setSelection(idx + (hasUp() ? 1 : 0) + (hasChoose() ? 1 : 0));
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void updateCount() {
        if (pickDir) {
            countT.setText(R.string.browser_dir_choose);
            return;
        }
        countT.setText(selected.isEmpty() ? "" : getString(R.string.browser_selected, selected.size()) + "  ·  " + getString(R.string.browser_send));
    }

    private void newFolder() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.browser_new_folder)
                .setView(et)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String n = Storage.sanitize(et.getText().toString());
                        File nf = new File(dir, n);
                        if (nf.isDirectory() || nf.mkdirs()) {
                            dir = nf;
                            load();
                        } else {
                            Toast.makeText(FileBrowserActivity.this, R.string.browser_new_folder_fail, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void done() {
        if (pickDir) {
            if (dir == null) return;
            Intent r = new Intent();
            r.putExtra(EXTRA_DIR, dir.getAbsolutePath());
            setResult(RESULT_OK, r);
            finish();
            return;
        }
        if (selected.isEmpty()) {
            // לא סומן כלום: שולחים את הקובץ שעליו עומדים
            int pos = list.getSelectedItemPosition();
            File f = pos >= 0 && pos < adapter.getCount() ? entryAt(pos) : null;
            if (f != null && f.isFile()) selected.add(f.getAbsolutePath());
        }
        if (selected.isEmpty()) return;
        Intent r = new Intent();
        r.putStringArrayListExtra(EXTRA_PATHS, new ArrayList<String>(selected));
        setResult(RESULT_OK, r);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean menu = Keys.isMenu(code);
        boolean back = code == KeyEvent.KEYCODE_BACK;
        if (menu || back || Keys.isBackAlt(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (menu) done();
                else if (back) up();
                else { setResult(RESULT_CANCELED); finish(); }
            }
            return true;
        }
        if (pickDir && code == KeyEvent.KEYCODE_STAR) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) newFolder();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size() + (hasUp() ? 1 : 0) + (hasChoose() ? 1 : 0); }
        @Override public Object getItem(int i) { return entryAt(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert : LayoutInflater.from(FileBrowserActivity.this).inflate(R.layout.row_file, parent, false);
            TextView mark = (TextView) v.findViewById(R.id.fileMark);
            TextView name = (TextView) v.findViewById(R.id.fileName);
            TextView info = (TextView) v.findViewById(R.id.fileInfo);
            File f = entryAt(pos);
            if (f == CHOOSE) {
                mark.setText("✓");
                name.setText("✓ שמור כאן (בחר תיקייה זו)");
                info.setText(Fmt.ltr(dir.getPath()));
            } else if (f == null) {
                mark.setText("↑");
                name.setText(R.string.browser_up);
                info.setText("");
            } else if (f.isDirectory()) {
                mark.setText("›");
                boolean vol = dir == null;
                name.setText(vol ? volumeLabel(f) : f.getName());
                info.setText(vol ? Fmt.ltr(f.getPath()) : "תיקייה");
            } else {
                mark.setText(selected.contains(f.getAbsolutePath()) ? "[x]" : "[ ]");
                name.setText(f.getName());
                info.setText(Fmt.ltr(Fmt.size(f.length())));
            }
            return v;
        }
    }
}