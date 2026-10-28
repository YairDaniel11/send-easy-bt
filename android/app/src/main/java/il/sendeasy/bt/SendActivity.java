package il.sendeasy.bt;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** מסך שליחה: מתחבר, שולח את הקבצים אחד אחד, מציג התקדמות. הכפתור היחיד (ביטול) תמיד ממוקד. */
public class SendActivity extends Activity {
    static final String EXTRA_ADDRESS = "address";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private volatile Session session;
    private TextView statusT, nameT, infoT;
    private ProgressBar bar;
    private Button btn;
    private boolean finished;
    private Thread worker;

    private static final class Item {
        Uri uri;
        String name;
        long size;
    }

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        setContentView(R.layout.activity_send);
        ThemeUtil.forceRtl(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        statusT = (TextView) findViewById(R.id.sendStatus);
        nameT = (TextView) findViewById(R.id.sendName);
        infoT = (TextView) findViewById(R.id.sendInfo);
        bar = (ProgressBar) findViewById(R.id.sendBar);
        btn = (Button) findViewById(R.id.sendCancel);
        btn.requestFocus();
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (finished) finish(); else requestCancel();
            }
        });

        final String address = getIntent().getStringExtra(EXTRA_ADDRESS);
        final List<Uri> uris = new ArrayList<Uri>();
        ClipData cd = getIntent().getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        if (address == null || uris.isEmpty()) {
            finish();
            return;
        }

        worker = new Thread(new Runnable() {
            @Override public void run() { work(address, uris); }
        }, "SE-Send");
        worker.start();
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        Session s = session;
        if (s != null) s.close();
        super.onDestroy();
    }

    private void requestCancel() {
        cancel.set(true);
        btn.setEnabled(false);
        statusT.setText(R.string.send_cancelled);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || Keys.isBackAlt(code)) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (finished) finish(); else requestCancel();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ עבודה ברקע

    private void post(final Runnable r) {
        ui.post(new Runnable() {
            @Override public void run() { if (!isFinishing()) r.run(); }
        });
    }

    private void setStatus(final String s) {
        post(new Runnable() { @Override public void run() { statusT.setText(s); } });
    }

    private void work(String address, List<Uri> uris) {
        String result = null;
        boolean ok = false;
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) throw new IOException(getString(R.string.main_no_bt));
            final BluetoothDevice dev = bt.getRemoteDevice(address);
            setStatus(getString(R.string.send_connecting, MainActivity.nameOf(dev)));

            List<Item> items = new ArrayList<Item>();
            for (Uri u : uris) items.add(describe(u));

            Session s = Connector.connect(this, dev, new Connector.QuietListener(), new Connector.Status() {
                @Override public void onRetry(int next, int max) { setStatus(getString(R.string.send_retry, next, max)); }
                @Override public boolean cancelled() { return cancel.get(); }
            });
            session = s;
            try {
                int total = items.size();
                for (int i = 0; i < total && !cancel.get(); i++) {
                    Item it = items.get(i);
                    final String label = getString(R.string.send_file_of, i + 1, total, "");
                    final String name = it.name;
                    post(new Runnable() { @Override public void run() { statusT.setText(label.trim()); nameT.setText(name); bar.setProgress(0); infoT.setText(""); } });
                    int r = sendOne(s, it);
                    if (r == Session.R_OK) continue;
                    if (r == Session.R_CANCELLED) { result = getString(R.string.send_cancelled); break; }
                    if (r == Session.R_REJECTED) { result = getString(R.string.send_rejected); break; }
                    result = getString(R.string.send_failed, r == Session.R_HASH ? "הקובץ הגיע פגום" : "שגיאה בצד המקבל");
                    break;
                }
                if (result == null) {
                    if (cancel.get()) result = getString(R.string.send_cancelled);
                    else { result = getString(R.string.send_done); ok = true; }
                }
            } finally {
                // סגירה רק אחרי שהכל אושר (FILE_DONE), כדי שלא יאבדו נתונים בחיץ
                s.close();
                session = null;
            }
        } catch (IOException e) {
            result = cancel.get() ? getString(R.string.send_cancelled) : getString(R.string.send_failed, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            result = getString(R.string.send_cancelled);
        } catch (RuntimeException e) {
            result = getString(R.string.send_failed, String.valueOf(e.getMessage()));
        }
        showResult(result, ok);
    }

    private void showResult(final String text, final boolean ok) {
        post(new Runnable() {
            @Override public void run() {
                finished = true;
                statusT.setText(text);
                statusT.setTextColor(getResources().getColor(ok ? R.color.ok_green : R.color.error_red));
                if (ok) bar.setProgress(100);
                btn.setEnabled(true);
                btn.setText(R.string.send_close);
                btn.requestFocus();
            }
        });
    }

    private int sendOne(Session s, final Item it) throws IOException, InterruptedException {
        ContentResolver cr = getContentResolver();
        InputStream in = cr.openInputStream(it.uri);
        if (in == null) throw new IOException("אי אפשר לפתוח את הקובץ");
        final long t0 = System.currentTimeMillis();
        try {
            return s.sendFile(in, it.name, it.size, new Session.SendProgress() {
                @Override public void onProgress(final long sent, final long total) {
                    post(new Runnable() {
                        @Override public void run() {
                            int pct = total > 0 ? (int) (sent * 100 / total) : 0;
                            bar.setProgress(pct);
                            double secs = Math.max((System.currentTimeMillis() - t0) / 1000.0, 0.5);
                            infoT.setText(Fmt.ltr(Fmt.size(sent) + " / " + Fmt.size(total)) + "   " + Fmt.ltr(Fmt.speed(sent / secs)));
                        }
                    });
                }
            }, cancel);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private Item describe(Uri u) throws IOException {
        Item it = new Item();
        it.uri = u;
        if ("file".equals(u.getScheme())) {
            File f = new File(u.getPath());
            it.name = f.getName();
            it.size = f.length();
        } else {
            Cursor c = null;
            try {
                c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    it.name = c.getString(0);
                    if (!c.isNull(1)) it.size = c.getLong(1);
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) c.close();
            }
            if (it.name == null) {
                String seg = u.getLastPathSegment();
                it.name = seg == null ? "file" : seg;
            }
            if (it.size <= 0) it.size = measure(u);
        }
        if (it.name == null || it.name.length() == 0) it.name = "file";
        if (it.size < 0) throw new IOException("גודל הקובץ לא ידוע");
        return it;
    }

    /** כשהספק לא מדווח גודל: קוראים את הזרם פעם אחת כדי לדעת (הפרוטוקול דורש גודל מראש). */
    private long measure(Uri u) throws IOException {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("אי אפשר לפתוח את הקובץ");
        long n = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
        } finally {
            in.close();
        }
        return n;
    }
}
