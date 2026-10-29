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
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

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
    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private DeviceAdapter adapter;

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

        handleShare(getIntent());
        Perms.requestMissing(this);
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
        checkUpdate(false);
    }

    @Override
    protected void onPause() {
        Events.remove(this);
        super.onPause();
    }

    @Override
    public void onEvent(String kind, String arg) {
        if (Events.STATE.equals(kind)) updateStatus();
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
        adapter.notifyDataSetChanged();
        if (!devices.isEmpty()) {
            list.requestFocus();
            if (list.getSelectedItemPosition() < 0) list.setSelection(0);
        }
        updateStatus();
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
        final String[] items = {getString(R.string.act_send_file), getString(R.string.act_chat), getString(R.string.act_rename)};
        new AlertDialog.Builder(this)
                .setTitle(DeviceNames.display(this, d))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) pickFiles(d.getAddress());
                        else if (which == 1) openChat(d.getAddress());
                        else DeviceNames.rename(MainActivity.this, d.getAddress(), nameOf(d), new Runnable() {
                            @Override public void run() { reload(); }
                        });
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

    private void checkUpdate(final boolean manual) {
        UpdateChecker.check(this, manual, new UpdateChecker.Callback() {
            @Override public void onResult(int status, final String version, final String url) {
                if (status == UpdateChecker.NEWER) {
                    if (!manual && version.equals(dismissedVersion)) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setMessage(getString(R.string.upd_new, Fmt.ltr(version)))
                            .setPositiveButton(R.string.upd_open, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    try {
                                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                                    } catch (RuntimeException ignored) { } // כולל ActivityNotFoundException
                                }
                            })
                            .setNegativeButton(R.string.upd_later, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) { dismissedVersion = version; }
                            })
                            .show();
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

    private void showMenu() {
        final String[] items = {
                getString(R.string.menu_toggle_receive),
                getString(R.string.menu_receive_dir),
                getString(R.string.menu_chat_style),
                getString(R.string.menu_check_update),
                getString(R.string.menu_about)};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) {
                            boolean on = !Prefs.receiveEnabled(MainActivity.this);
                            Prefs.setReceiveEnabled(MainActivity.this, on);
                            if (on) BtService.start(MainActivity.this); else BtService.stop(MainActivity.this);
                            updateStatus();
                        } else if (which == 1) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setMessage(getString(R.string.main_receive_dir, Fmt.ltr(Storage.receiveDirLabel(MainActivity.this))))
                                    .setPositiveButton(android.R.string.ok, null).show();
                        } else if (which == 2) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.menu_chat_style)
                                    .setSingleChoiceItems(new String[]{getString(R.string.style_wa), getString(R.string.style_gc)}, Prefs.chatStyle(MainActivity.this), new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) { Prefs.setChatStyle(MainActivity.this, w); d.dismiss(); }
                                    }).show();
                        } else if (which == 3) {
                            checkUpdate(true);
                        } else {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setMessage(R.string.about_text)
                                    .setPositiveButton(android.R.string.ok, null).show();
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
