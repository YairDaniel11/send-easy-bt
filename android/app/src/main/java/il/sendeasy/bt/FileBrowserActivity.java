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

    private ListView list;
    private TextView pathT, countT, emptyT;
    private File root;
    private File dir;
    private final List<File> entries = new ArrayList<File>();
    private final Set<String> selected = new HashSet<String>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        setContentView(R.layout.activity_browser);
        ThemeUtil.forceRtl(this);
        list = (ListView) findViewById(R.id.browserList);
        pathT = (TextView) findViewById(R.id.browserPath);
        countT = (TextView) findViewById(R.id.browserCount);
        emptyT = (TextView) findViewById(R.id.browserEmpty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { onEntry(pos); }
        });

        root = Environment.getExternalStorageDirectory();
        dir = root;
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
        entries.clear();
        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File c) {
                    if (a.isDirectory() != c.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(c.getName());
                }
            });
            for (File k : kids) if (!k.getName().startsWith(".")) entries.add(k);
        }
        pathT.setText(Fmt.ltr(dir.getPath()));
        emptyT.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
        updateCount();
    }

    private boolean hasUp() {
        return !dir.equals(root) && dir.getParentFile() != null;
    }

    // שורה 0 היא ".." כשאפשר לעלות
    private File entryAt(int pos) {
        if (hasUp()) return pos == 0 ? null : entries.get(pos - 1);
        return entries.get(pos);
    }

    private void onEntry(int pos) {
        File f = entryAt(pos);
        if (f == null) { up(); return; }
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
            dir = dir.getParentFile();
            load();
            int idx = entries.indexOf(prev);
            if (idx >= 0) list.setSelection(idx + (hasUp() ? 1 : 0));
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void updateCount() {
        countT.setText(selected.isEmpty() ? "" : getString(R.string.browser_selected, selected.size()) + "  ·  " + getString(R.string.browser_send));
    }

    private void done() {
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
        return super.dispatchKeyEvent(e);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size() + (hasUp() ? 1 : 0); }
        @Override public Object getItem(int i) { return entryAt(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert : LayoutInflater.from(FileBrowserActivity.this).inflate(R.layout.row_file, parent, false);
            TextView mark = (TextView) v.findViewById(R.id.fileMark);
            TextView name = (TextView) v.findViewById(R.id.fileName);
            TextView info = (TextView) v.findViewById(R.id.fileInfo);
            File f = entryAt(pos);
            if (f == null) {
                mark.setText("↑");
                name.setText(R.string.browser_up);
                info.setText("");
            } else if (f.isDirectory()) {
                mark.setText("›");
                name.setText(f.getName());
                info.setText("תיקייה");
            } else {
                mark.setText(selected.contains(f.getAbsolutePath()) ? "[x]" : "[ ]");
                name.setText(f.getName());
                info.setText(Fmt.ltr(Fmt.size(f.length())));
            }
            return v;
        }
    }
}