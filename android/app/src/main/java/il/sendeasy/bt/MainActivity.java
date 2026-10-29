package il.sendeasy.bt;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** מסך ראשי: רשימת מכשירים מצומדים. אישור = פעולות, תפריט = אפשרויות. */
public class MainActivity extends Activity implements Events.Listener {
    private ListView list;
    private TextView status;
    private TextView empty;
    private TextView pickHint;
    private TextView updateBar;
    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private DeviceAdapter adapter;
    /** מספר הודעות שלא נקראו לכל מכשיר (לפי כתובת). מתעדכן ב-reload וב-Events.CHAT. */
    private final java.util.HashMap<String, Integer> unreadMap = new java.util.HashMap<String, Integer>();

    /** קבצים שהגיעו משיתוף מאפליקציה אחרת וממתינים לבחירת מכשיר. */
    private ArrayList<Uri> pendingShare;
    /** המכשיר שנבחר בזמן שבוחרים קבצים. */
    private String pickAddress;

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        ThemeUtil.forceRtl(this);

        list = (ListView) findViewById(R.id.list);
        status = (TextView) findViewById(R.id.status);
        empty = (TextView) findViewById(R.id.empty);
        pickHint = (TextView) findViewById(R.id.pickHint);
        adapter = new DeviceAdapter();
        list.setAdapter(adapter);
        list.setItemsCanFocus(false);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { adapter.notifyDataSetChanged(); }
            @Override public void onNothingSelected(AdapterView<?> p) { adapter.notifyDataSetChanged(); }
        });
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { adapter.notifyDataSetChanged(); }
        });
        UiKit.avatar((TextView) findViewById(R.id.logo), "BT", 44);
        ((TextView) findViewById(R.id.logo)).setText("BT");
        ((TextView) findViewById(R.id.logo)).setBackgroundDrawable(UiKit.rect(this, 0x33FFFFFF, 14));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { onDevice(devices.get(pos)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { onDevice(devices.get(pos)); return true; }
        });

        updateBar = (TextView) findViewById(R.id.updateBar);
        updateBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (available != null) showUpdateDialog(available);
            }
        });
        updateBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                int accent = UiKit.attr(MainActivity.this, R.attr.sbAccent);
                updateBar.setBackgroundDrawable(has
                        ? UiKit.rect(MainActivity.this, accent, 10, 0xFFFFFFFF, 3)
                        : UiKit.rect(MainActivity.this, accent, 10));
            }
        });

        handleShare(getIntent());
        Perms.requestMissing(this);
    }

    @Override
    protected void onDestroy() {
        if (downloader != null) downloader.cancel();
        downloader = null;
        if (progressDlg != null) {
            try { progressDlg.dismiss(); } catch (RuntimeException ignored) { }
            progressDlg = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Events.add(this);
        reload();
        startServiceIfNeeded();
        refreshUpdateBar();
        if (pendingInstall != null) {
            File f = pendingInstall;
            pendingInstall = null;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) launchInstall(f);
            else Toast.makeText(this, R.string.upd_allow_denied, Toast.LENGTH_LONG).show();
        } else {
            checkUpdate(false);
        }
    }

    @Override
    protected void onPause() {
        Events.remove(this);
        super.onPause();
    }

    @Override
    public void onEvent(String kind, String arg) {
        if (Events.STATE.equals(kind)) updateStatus();
        if (Events.CHAT.equals(kind)) { loadUnread(); adapter.notifyDataSetChanged(); }
        if (Events.RECEIVED.equals(kind)) status.setText(getString(R.string.rx_file, arg));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        reload();
        startServiceIfNeeded();
    }

    private void startServiceIfNeeded() {
        if (Prefs.receiveEnabled(this) && Perms.hasBluetooth(this) && !BtService.isRunning()) BtService.start(this);
    }

    // ------------------------------------------------------------ שיתוף

    private void handleShare(Intent in) {
        pendingShare = null;
        if (in == null) return;
        String a = in.getAction();
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (!uris.isEmpty()) pendingShare = uris;
        pickHint.setBackgroundDrawable(UiKit.rect(this, UiKit.attr(this, R.attr.sbAccent), 14));
        pickHint.setVisibility(pendingShare == null ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------ רשימת מכשירים

    private void reload() {
        devices.clear();
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) {
            showEmpty(getString(R.string.main_no_bt));
        } else if (!bt.isEnabled()) {
            showEmpty(getString(R.string.main_bt_off));
        } else if (!Perms.hasBluetooth(this)) {
            showEmpty(getString(R.string.perm_needed));
        } else {
            try {
                Set<BluetoothDevice> bonded = bt.getBondedDevices();
                if (bonded != null) devices.addAll(bonded);
            } catch (SecurityException ignored) { }
            Collections.sort(devices, new Comparator<BluetoothDevice>() {
                @Override public int compare(BluetoothDevice x, BluetoothDevice y) {
                    int c = Boolean.valueOf(isComputer(y)).compareTo(isComputer(x));
                    return c != 0 ? c : DeviceNames.display(MainActivity.this, x).compareToIgnoreCase(DeviceNames.display(MainActivity.this, y));
                }
            });
            if (devices.isEmpty()) showEmpty(getString(R.string.main_no_devices)); else empty.setVisibility(View.GONE);
        }
        loadUnread();
        adapter.notifyDataSetChanged();
        if (!devices.isEmpty()) {
            list.requestFocus();
            if (list.getSelectedItemPosition() < 0) list.setSelection(0);
        }
        updateStatus();
    }

    private void loadUnread() {
        unreadMap.clear();
        for (BluetoothDevice d : devices) {
            int n = ChatStore.unread(this, d.getAddress());
            if (n > 0) unreadMap.put(d.getAddress(), n);
        }
    }

    private void showEmpty(String msg) {
        empty.setText(msg);
        empty.setVisibility(View.VISIBLE);
    }

    private void updateStatus() {
        boolean on = Prefs.receiveEnabled(this);
        String s = getString(on ? R.string.main_receiving_on : R.string.main_receiving_off);
        if (on) s += "\n" + getString(R.string.main_receive_dir, Fmt.ltr(Storage.receiveDirLabel(this)));
        status.setText(s);
    }

    static boolean isComputer(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER;
        } catch (SecurityException e) {
            return false;
        }
    }

    static String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && n.length() > 0) return n;
        } catch (SecurityException ignored) { }
        return d.getAddress();
    }

    private final class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() { return devices.size(); }
        @Override public Object getItem(int i) { return devices.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert : LayoutInflater.from(MainActivity.this).inflate(R.layout.row_item, parent, false);
            BluetoothDevice d = devices.get(pos);
            String name = DeviceNames.display(MainActivity.this, d);
            ((TextView) v.findViewById(R.id.rowTitle)).setText(name);
            ((TextView) v.findViewById(R.id.rowSub)).setText(Fmt.ltr(d.getAddress()) + (isComputer(d) ? "  ·  מחשב" : ""));
            UiKit.avatar((TextView) v.findViewById(R.id.avatar), name, 48);
            TextView badge = (TextView) v.findViewById(R.id.rowBadge);
            Integer un = unreadMap.get(d.getAddress());
            if (un != null && un > 0) {
                badge.setText(un == 1 ? getString(R.string.unread_one) : getString(R.string.unread_n, un));
                badge.setBackgroundDrawable(UiKit.rect(MainActivity.this, 0xFF1B9E4B, 12));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            // כרטיס עגול; בניווט במקשים מסגרת בולטת סביב הפריט הנבחר
            boolean focused = list.hasFocus() && list.getSelectedItemPosition() == pos;
            int accent = UiKit.attr(MainActivity.this, R.attr.sbAccent);
            View card = v.findViewById(R.id.card);
            card.setBackgroundDrawable(focused
                    ? UiKit.rect(MainActivity.this, UiKit.attr(MainActivity.this, R.attr.sbRow), 16, accent, 3)
                    : UiKit.rect(MainActivity.this, UiKit.attr(MainActivity.this, R.attr.sbRow), 16));
            return v;
        }
    }

    // ------------------------------------------------------------ פעולות

    private void onDevice(final BluetoothDevice d) {
        if (pendingShare != null) {
            startSend(d.getAddress(), pendingShare);
            pendingShare = null;
            pickHint.setVisibility(View.GONE);
            return;
        }
        final String[] items = {getString(R.string.act_send_file), getString(R.string.act_chat)};
        new AlertDialog.Builder(this)
                .setTitle(DeviceNames.display(this, d))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) pickFiles(d.getAddress());
                        else openChat(d.getAddress());
                    }
                })
                .show();
    }

    private void openChat(String address) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_ADDRESS, address);
        startActivity(i);
    }

    private void pickFiles(String address) {
        pickAddress = address;
        FilePicker.pick(this);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE_TREE || req == REQ_SAVE_BROWSE) {
            if (res == RESULT_OK && data != null) onSaveDirChosen(req, data);
            return;
        }
        if (res != RESULT_OK || data == null || pickAddress == null) return;
        ArrayList<Uri> uris = FilePicker.result(req, data);
        if (!uris.isEmpty()) FilePicker.startSend(this, pickAddress, uris);
    }

    private void startSend(String address, List<Uri> uris) {
        FilePicker.startSend(this, address, uris);
    }

    // ------------------------------------------------------------ עדכונים

    /** גרסה שהמשתמש בחר "אחר כך" לגביה בהפעלה הנוכחית של התהליך. */
    private static String dismissedVersion;

    /** הגרסה החדשה האחרונה שנמצאה (ללא בקשת רשת נוספת). משמשת את הפס העליון. */
    private static UpdateChecker.Info available;

    private ApkDownloader downloader;
    private AlertDialog progressDlg;
    /** קובץ שמחכה לחזרה ממסך ההרשאה "התקנת אפליקציות לא מוכרות". */
    private File pendingInstall;

    private void refreshUpdateBar() {
        UpdateChecker.Info i = available;
        if (i == null) { updateBar.setVisibility(View.GONE); return; }
        updateBar.setText(getString(R.string.upd_bar, Fmt.ltr(i.version)));
        updateBar.setBackgroundDrawable(UiKit.rect(this, UiKit.attr(this, R.attr.sbAccent), 10));
        updateBar.setVisibility(View.VISIBLE);
    }

    private void showUpdateDialog(final UpdateChecker.Info info) {
        final boolean auto = info.apkUrl != null;
        new AlertDialog.Builder(this)
                .setMessage(getString(auto ? R.string.upd_new_auto : R.string.upd_new, Fmt.ltr(info.version)))
                .setPositiveButton(auto ? R.string.upd_install : R.string.upd_open, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (auto) { startDownload(info); return; }
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)));
                        } catch (RuntimeException ignored) { } // כולל ActivityNotFoundException
                    }
                })
                .setNegativeButton(R.string.upd_later, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { dismissedVersion = info.version; }
                })
                .show();
    }

    private void startDownload(UpdateChecker.Info info) {
        if (downloader != null) return;
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        final TextView txt = new TextView(this);
        txt.setTextSize(15);
        txt.setPadding(0, pad / 2, 0, 0);
        txt.setText(R.string.upd_downloading);
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(txt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progressDlg = new AlertDialog.Builder(this)
                .setTitle(R.string.upd_downloading)
                .setView(box)
                .setNegativeButton(R.string.upd_cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cancelDownload(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { cancelDownload(); }
                })
                .create();
        progressDlg.show();
        Button cancel = progressDlg.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (cancel != null) cancel.requestFocus();

        downloader = ApkDownloader.start(this, info.apkUrl, info.apkSize, new ApkDownloader.Listener() {
            @Override public void onProgress(long done, long total) {
                if (total > 0) {
                    int p = (int) Math.min(100, done * 100 / total);
                    bar.setIndeterminate(false);
                    bar.setProgress(p);
                    txt.setText(getString(R.string.upd_progress, Fmt.ltr(Fmt.size(done)), Fmt.ltr(Fmt.size(total)), p));
                } else {
                    txt.setText(getString(R.string.upd_progress_unknown, Fmt.ltr(Fmt.size(done))));
                }
            }
            @Override public void onDone(File apk) {
                closeProgress();
                installApk(apk);
            }
            @Override public void onError(int code) {
                closeProgress();
                int msg = code == ApkDownloader.ERR_SIZE ? R.string.upd_err_size
                        : code == ApkDownloader.ERR_DISK ? R.string.upd_err_disk : R.string.upd_err_net;
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show();
            }
        });
    }

    private void cancelDownload() {
        if (downloader != null) downloader.cancel();
        downloader = null;
        progressDlg = null;
    }

    private void closeProgress() {
        downloader = null;
        AlertDialog d = progressDlg;
        progressDlg = null;
        if (d != null) {
            try { d.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private void installApk(final File apk) {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.upd_allow_source)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            pendingInstall = apk;
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                            } catch (RuntimeException e) {
                                pendingInstall = null;
                                Toast.makeText(MainActivity.this, R.string.upd_allow_denied, Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        launchInstall(apk);
    }

    private void launchInstall(File apk) {
        try {
            if (!apk.isFile()) throw new IllegalStateException();
            Uri u = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uri(this) : Uri.fromFile(apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (RuntimeException e) { // כולל ActivityNotFoundException
            new AlertDialog.Builder(this).setMessage(R.string.upd_install_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void checkUpdate(final boolean manual) {
        UpdateChecker.check(this, manual, new UpdateChecker.Callback() {
            @Override public void onResult(int status, UpdateChecker.Info info) {
                if (status == UpdateChecker.UP_TO_DATE) { available = null; refreshUpdateBar(); }
                if (status == UpdateChecker.NEWER) {
                    available = info;
                    refreshUpdateBar();
                    if (!manual && info.version.equals(dismissedVersion)) return;
                    showUpdateDialog(info);
                } else if (manual) {
                    int msg = status == UpdateChecker.NO_NET ? R.string.upd_no_net
                            : status == UpdateChecker.UP_TO_DATE ? R.string.upd_current : R.string.upd_failed;
                    new AlertDialog.Builder(MainActivity.this)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            }
        });
    }

    // ------------------------------------------------------------ תיקיית שמירה

    private static final int REQ_SAVE_TREE = 21;
    private static final int REQ_SAVE_BROWSE = 22;

    private void showSaveDirDialog() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView path = new TextView(this);
        path.setTextSize(15);
        path.setPadding(pad, pad, pad, pad / 2);
        path.setText(getString(R.string.savedir_current, Fmt.ltr(Storage.receiveDirLabel(this))));
        box.addView(path, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ListView lv = new ListView(this);
        lv.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                new String[]{getString(R.string.savedir_choose), getString(R.string.savedir_default)}));
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_save_dir)
                .setView(box)
                .create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                dlg.dismiss();
                if (pos == 0) {
                    chooseSaveDir();
                } else {
                    releaseOldTree();
                    Prefs.clearSaveLocation(MainActivity.this);
                    updateStatus();
                    Toast.makeText(MainActivity.this, R.string.savedir_reset, Toast.LENGTH_SHORT).show();
                }
            }
        });
        dlg.show();
        lv.requestFocus();
    }

    private void chooseSaveDir() {
        try {
            boolean touch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            int sdk = Build.VERSION.SDK_INT;
            // SAF (בורר המערכת) רק מ-API 21, ובמכשיר מגע (או חובה מ-API 29). ב-API 19 ובמקשים: הדפדפן שלנו בלבד
            if (sdk >= 29 || (sdk >= 21 && touch)) {
                try {
                    startActivityForResult(SafHelper.treePickerIntent(), REQ_SAVE_TREE);
                    return;
                } catch (RuntimeException e) { // כולל ActivityNotFoundException
                    if (sdk >= 29) {
                        Toast.makeText(this, R.string.savedir_unsupported, Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            }
            if (sdk >= 23 && sdk <= 28 && !Perms.has(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) Perms.requestMissing(this);
            Intent b = new Intent(this, FileBrowserActivity.class);
            b.putExtra(FileBrowserActivity.EXTRA_PICK_DIR, true);
            startActivityForResult(b, REQ_SAVE_BROWSE);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.savedir_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    private void releaseOldTree() {
        String old = Prefs.saveTree(this);
        if (old == null || Build.VERSION.SDK_INT < 21) return;
        try {
            SafHelper.release(this, Uri.parse(old));
        } catch (Throwable ignored) { }
    }

    private void onSaveDirChosen(int req, Intent data) {
        try {
            if (req == REQ_SAVE_TREE) {
                Uri u = data.getData();
                if (u == null || Build.VERSION.SDK_INT < 21) return;
                if (!SafHelper.take(this, u)) {
                    Toast.makeText(this, R.string.savedir_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                String old = Prefs.saveTree(this);
                if (old != null && !old.equals(u.toString())) releaseOldTree();
                Prefs.setSaveTree(this, u.toString());
            } else {
                String p = data.getStringExtra(FileBrowserActivity.EXTRA_DIR);
                if (p == null) return;
                releaseOldTree();
                Prefs.setSaveDir(this, p);
            }
            updateStatus();
            Toast.makeText(this, R.string.savedir_set, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.savedir_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static final String REPO_URL = "https://github.com/YairDaniel11/send-easy-bt";

    private void showAbout() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        int pad = UiKit.dp(this, 14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setText(getString(R.string.about_text, getString(R.string.app_name), Fmt.ltr(ver == null ? "" : ver), REPO_URL));
        android.text.util.Linkify.addLinks(t, android.text.util.Linkify.WEB_URLS);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final android.app.Dialog[] holder = new android.app.Dialog[1];
        TextView open = UiKit.actionButton(this, getString(R.string.about_open_repo), new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
                } catch (RuntimeException e) { // כולל ActivityNotFoundException
                    Toast.makeText(MainActivity.this, R.string.about_no_browser, Toast.LENGTH_LONG).show();
                }
            }
        });
        TextView close = UiKit.actionButton(this, getString(R.string.savedir_close), new View.OnClickListener() {
            @Override public void onClick(View v) { if (holder[0] != null) holder[0].dismiss(); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = UiKit.dp(this, 10);
        box.addView(open, bl);
        box.addView(close, bl);
        open.setId(View.generateViewId());
        close.setId(View.generateViewId());
        open.setNextFocusDownId(close.getId());
        open.setNextFocusUpId(open.getId());
        close.setNextFocusUpId(open.getId());
        close.setNextFocusDownId(close.getId());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this).setView(sv).create();
        holder[0] = d;
        d.show();
        open.requestFocus();
    }

    /** רשימת המכשירים המצומדים לבחירה, ואז דיאלוג שינוי השם. */
    private void showRenameDevice() {
        if (devices.isEmpty()) { Toast.makeText(this, R.string.main_no_devices, Toast.LENGTH_SHORT).show(); return; }
        final List<BluetoothDevice> list2 = new ArrayList<BluetoothDevice>(devices);
        String[] names = new String[list2.size()];
        for (int i = 0; i < names.length; i++) names[i] = DeviceNames.display(this, list2.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_rename_device)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        BluetoothDevice d = list2.get(which);
                        DeviceNames.rename(MainActivity.this, d.getAddress(), nameOf(d), new Runnable() {
                            @Override public void run() { reload(); }
                        });
                    }
                })
                .show();
    }

    /** בחירה מרובה של שיחות למחיקה (רק מכשירים שיש להן הודעות), עם אישור לפני המחיקה. */
    private void showDeleteChats() {
        final List<BluetoothDevice> withChat = new ArrayList<BluetoothDevice>();
        final List<String> labels = new ArrayList<String>();
        for (BluetoothDevice d : devices) {
            int n = ChatStore.load(this, d.getAddress()).size();
            if (n > 0) {
                withChat.add(d);
                labels.add(DeviceNames.display(this, d) + " (" + n + ")");
            }
        }
        if (withChat.isEmpty()) { Toast.makeText(this, R.string.chats_none, Toast.LENGTH_SHORT).show(); return; }
        final boolean[] sel = new boolean[withChat.size()];
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_delete_chats)
                .setMultiChoiceItems(labels.toArray(new String[0]), sel, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean checked) { sel[which] = checked; }
                })
                .setPositiveButton(R.string.chat_delete_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        boolean any = false;
                        for (boolean b : sel) any |= b;
                        if (!any) return;
                        confirmDeleteChats(withChat, sel);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteChats(final List<BluetoothDevice> withChat, final boolean[] sel) {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage(R.string.chats_delete_confirm)
                .setPositiveButton(R.string.chat_delete_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < sel.length; i++) {
                            if (sel[i]) ChatStore.deleteAll(getApplicationContext(), withChat.get(i).getAddress());
                        }
                        loadUnread();
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();
        Button neg = dlg.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (neg != null) neg.requestFocus();
    }

    private void showAlertsDialog() {
        final boolean[] on = {Prefs.notifySound(this), Prefs.notifyVibrate(this), Prefs.notifyWake(this)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_notify_alerts)
                .setMultiChoiceItems(new String[]{getString(R.string.alert_ring), getString(R.string.alert_vibrate), getString(R.string.alert_wake)}, on,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(DialogInterface d, int which, boolean checked) { on[which] = checked; }
                        })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Prefs.setNotifySound(MainActivity.this, on[0]);
                        Prefs.setNotifyVibrate(MainActivity.this, on[1]);
                        Prefs.setNotifyWake(MainActivity.this, on[2]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** התפריט הראשי: הגדרות, בדיקת עדכון, אודות. */
    private void showMenu() {
        final String[] items = {
                getString(R.string.menu_settings),
                getString(R.string.menu_new_chat),
                getString(R.string.menu_check_update),
                getString(R.string.menu_about)};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) showSettings();
                        else if (which == 1) showNewChat();
                        else if (which == 2) checkUpdate(true);
                        else showAbout();
                    }
                })
                .show();
    }

    /** צ'אט חדש עם המכשיר שהסמן עומד עליו ברשימה: פותח שיחה חדשה (ריקה), והקודמות נשארות זמינות מתוך הצ'אט. */
    private void showNewChat() {
        int pos = list.getSelectedItemPosition();
        if (devices.isEmpty() || pos < 0 || pos >= devices.size()) {
            Toast.makeText(this, R.string.main_no_devices, Toast.LENGTH_SHORT).show();
            return;
        }
        String addr = devices.get(pos).getAddress();
        if (!ChatStore.newThread(getApplicationContext(), addr)) Toast.makeText(this, R.string.chat_new_still_empty, Toast.LENGTH_SHORT).show();
        loadUnread();
        adapter.notifyDataSetChanged();
        openChat(addr);
    }

    /** כל ההגדרות בחלונית אחת (רשימה נגישה ב-D-pad), עם מצב בטקסט של כל פריט. */
    private void showSettings() {
        final String[] items = {
                getString(R.string.menu_toggle_receive),
                getString(Prefs.showServiceNotification(this) ? R.string.menu_svc_notif_shown : R.string.menu_svc_notif_hidden),
                getString(R.string.menu_receive_dir),
                getString(R.string.menu_save_dir),
                getString(Prefs.notifyEvents(this) ? R.string.menu_notify_shown : R.string.menu_notify_hidden),
                getString(R.string.menu_notify_alerts),
                getString(R.string.menu_chat_style),
                getString(R.string.menu_rename_device),
                getString(R.string.menu_delete_chats)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_settings)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) {
                            boolean on = !Prefs.receiveEnabled(MainActivity.this);
                            Prefs.setReceiveEnabled(MainActivity.this, on);
                            if (on) BtService.start(MainActivity.this); else BtService.stop(MainActivity.this);
                            updateStatus();
                        } else if (which == 1) {
                            Prefs.setShowServiceNotification(MainActivity.this, !Prefs.showServiceNotification(MainActivity.this));
                            // הפעלה מחדש כדי שההתראה תיבנה בערוץ המתאים
                            if (Prefs.receiveEnabled(MainActivity.this) && BtService.isRunning()) BtService.restart(MainActivity.this);
                        } else if (which == 2) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setMessage(getString(R.string.main_receive_dir, Fmt.ltr(Storage.receiveDirLabel(MainActivity.this))))
                                    .setPositiveButton(android.R.string.ok, null).show();
                        } else if (which == 3) {
                            showSaveDirDialog();
                        } else if (which == 4) {
                            Prefs.setNotifyEvents(MainActivity.this, !Prefs.notifyEvents(MainActivity.this));
                        } else if (which == 5) {
                            showAlertsDialog();
                        } else if (which == 6) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.menu_chat_style)
                                    .setSingleChoiceItems(new String[]{getString(R.string.style_wa), getString(R.string.style_gc)}, Prefs.chatStyle(MainActivity.this), new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) { Prefs.setChatStyle(MainActivity.this, w); d.dismiss(); }
                                    }).show();
                        } else if (which == 7) {
                            showRenameDevice();
                        } else {
                            showDeleteChats();
                        }
                    }
                })
                .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (Keys.isMenu(code) || Keys.isBackAlt(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (Keys.isMenu(code)) showMenu(); else finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
