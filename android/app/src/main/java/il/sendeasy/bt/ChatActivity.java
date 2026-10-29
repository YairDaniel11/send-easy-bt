package il.sendeasy.bt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * צ'אט עם מכשיר מצומד: טקסט, הודעות קוליות וקבצים. החיבור נשמר פתוח כל עוד המסך פתוח.
 * מקשים: אישור = פעולה, תפריט = הקלטה/קובץ, בזמן הקלטה: אישור = שלח, חזרה = בטל.
 */
public class ChatActivity extends Activity implements Events.Listener {
    static final String EXTRA_ADDRESS = "address";
    private static final int REQ_MIC = 4002;
    private static final long MAX_RECORD_MS = 5 * 60 * 1000;

    private String address;
    private String deviceName;
    private ListView list;
    private TextView emptyT, recT;
    private EditText input;
    private Button sendBtn, micBtn;
    private final List<ChatStore.Msg> msgs = new ArrayList<ChatStore.Msg>();
    private Adapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Session session; // רק מהשרשור של io
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final VoiceRecorder recorder = new VoiceRecorder();
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recorder.isRecording()) return;
            long ms = recorder.elapsedMs();
            if (ms >= MAX_RECORD_MS) { stopRecordingAndSend(); return; }
            recT.setText(getString(R.string.voice_recording, mmss((int) (ms / 1000))));
            ui.postDelayed(this, 500);
        }
    };

    private MediaPlayer player;
    private String playingFile;

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        setContentView(R.layout.activity_chat);
        ThemeUtil.forceRtl(this);

        address = getIntent().getStringExtra(EXTRA_ADDRESS);
        if (address == null) { finish(); return; }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        deviceName = address;
        if (bt != null) {
            try { deviceName = MainActivity.nameOf(bt.getRemoteDevice(address)); } catch (RuntimeException ignored) { }
        }
        ((TextView) findViewById(R.id.chatTitle)).setText(getString(R.string.chat_title, Fmt.ltr(deviceName)));

        list = (ListView) findViewById(R.id.chatList);
        emptyT = (TextView) findViewById(R.id.chatEmpty);
        recT = (TextView) findViewById(R.id.chatRec);
        input = (EditText) findViewById(R.id.chatInput);
        sendBtn = (Button) findViewById(R.id.chatSend);
        micBtn = (Button) findViewById(R.id.chatMic);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ChatStore.Msg m = msgs.get(pos);
                if (m.isVoice()) togglePlay(m);
            }
        });

        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
        });
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecording(); }
        });
        input.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                // במקלדת T9 מקש Enter מחזיר IME_NULL
                boolean enter = action == EditorInfo.IME_NULL && e != null && e.getAction() == KeyEvent.ACTION_DOWN && Keys.isSelect(e.getKeyCode());
                if (action == EditorInfo.IME_ACTION_SEND || enter) { send(); return true; }
                return false;
            }
        });
        reload();
        input.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (address == null) return;
        Events.add(this);
        reload();
    }

    @Override
    protected void onPause() {
        Events.remove(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        recorder.cancel();
        stopPlay();
        io.execute(new Runnable() {
            @Override public void run() {
                if (session != null) session.close();
                session = null;
            }
        });
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onEvent(String kind, String arg) {
        if (Events.CHAT.equals(kind) && address != null && address.equals(arg)) reload();
    }

    private void reload() {
        msgs.clear();
        msgs.addAll(ChatStore.load(this, address));
        emptyT.setVisibility(msgs.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (!msgs.isEmpty()) list.setSelection(msgs.size() - 1);
    }

    private static String mmss(int s) {
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ------------------------------------------------------------ חיבור ושליחה

    /** חיבור פתוח לשליחה (מהשרשור של io בלבד). אם נפל, פותח חדש. */
    private Session ensureSession() throws IOException, InterruptedException {
        if (session == null || session.isClosed()) {
            BluetoothDevice dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
            session = Connector.connect(this, dev, new Connector.QuietListener(), null);
        }
        return session;
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(ChatActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        input.setText("");
        final Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    if (!trySendText(text)) err = "לא התקבל אישור";
                } catch (IOException e) {
                    err = e.getMessage();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    err = String.valueOf(e.getMessage());
                }
                if (err == null) {
                    ChatStore.append(app, address, true, text, System.currentTimeMillis());
                    Events.post(Events.CHAT, address);
                } else {
                    final String m = err;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(ChatActivity.this, getString(R.string.chat_failed) + ": " + m, Toast.LENGTH_LONG).show();
                            input.setText(text);
                            input.setSelection(text.length());
                        }
                    });
                }
            }
        });
    }

    /** שולח, ובכשל מנסה פעם אחת חיבור חדש (החיבור הישן אולי נפל). */
    private boolean trySendText(String text) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session s = ensureSession();
            try {
                return s.sendText(text);
            } catch (IOException e) {
                s.close();
                session = null;
                if (attempt == 1) throw e;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ הקלטה

    private void toggleRecording() {
        if (recorder.isRecording()) { stopRecordingAndSend(); return; }
        if (!Perms.has(this, Manifest.permission.RECORD_AUDIO)) {
            if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startRecording();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording();
            else Toast.makeText(this, R.string.voice_no_mic, Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording() {
        stopPlay();
        if (!recorder.start(this)) {
            Toast.makeText(this, R.string.voice_no_mic, Toast.LENGTH_LONG).show();
            return;
        }
        recT.setVisibility(View.VISIBLE);
        recT.setText(getString(R.string.voice_recording, "0:00"));
        ui.postDelayed(recTick, 500);
        micBtn.requestFocus();
    }

    private void endRecordingUi() {
        ui.removeCallbacks(recTick);
        recT.setVisibility(View.GONE);
        input.requestFocus();
    }

    private void cancelRecording() {
        recorder.cancel();
        endRecordingUi();
    }

    private void stopRecordingAndSend() {
        final File f = recorder.stop(this);
        endRecordingUi();
        if (f == null) {
            Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        final Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    err = sendVoiceFile(f);
                } catch (IOException e) {
                    err = e.getMessage();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    err = String.valueOf(e.getMessage());
                }
                if (err == null) {
                    ChatStore.add(app, address, new ChatStore.Msg(System.currentTimeMillis(), true, ChatStore.T_VOICE, f.getName()));
                    Events.post(Events.CHAT, address);
                } else {
                    f.delete();
                    fail(getString(R.string.chat_failed) + ": " + err);
                }
            }
        });
    }

    /** שולח את ההקלטה כקובץ רגיל. מחזיר null בהצלחה, אחרת הודעת שגיאה. */
    private String sendVoiceFile(File f) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session s = ensureSession();
            try {
                FileInputStream in = new FileInputStream(f);
                int r;
                try {
                    r = s.sendFile(in, f.getName(), f.length(), null, new AtomicBoolean(false));
                } finally {
                    try { in.close(); } catch (IOException ignored) { }
                }
                if (r == Session.R_OK) return null;
                if (r == Session.R_REJECTED) return getString(R.string.send_rejected);
                return "שגיאה בצד המקבל";
            } catch (IOException e) {
                s.close();
                session = null;
                if (attempt == 1) throw e;
            }
        }
        return "השליחה נכשלה";
    }

    // ------------------------------------------------------------ השמעה

    private void stopPlay() {
        MediaPlayer p = player;
        player = null;
        playingFile = null;
        if (p != null) {
            try { p.stop(); } catch (RuntimeException ignored) { }
            try { p.release(); } catch (RuntimeException ignored) { }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void togglePlay(ChatStore.Msg m) {
        boolean same = m.text.equals(playingFile);
        stopPlay();
        if (same) return;
        File f = new File(Storage.voiceDir(this), m.text);
        if (!f.exists()) { Toast.makeText(this, R.string.voice_play_failed, Toast.LENGTH_SHORT).show(); return; }
        MediaPlayer mp = new MediaPlayer();
        try {
            FileInputStream in = new FileInputStream(f);
            try { mp.setDataSource(in.getFD()); } finally { in.close(); }
            mp.prepare();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) { stopPlay(); }
            });
            mp.start();
        } catch (IOException e) {
            try { mp.release(); } catch (RuntimeException ignored) { }
            Toast.makeText(this, R.string.voice_play_failed, Toast.LENGTH_SHORT).show();
            return;
        } catch (RuntimeException e) {
            try { mp.release(); } catch (RuntimeException ignored) { }
            Toast.makeText(this, R.string.voice_play_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        player = mp;
        playingFile = m.text;
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ מקשים

    private void showMenu() {
        final String[] items = {getString(R.string.voice_record), getString(R.string.chat_menu_file)};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) toggleRecording(); else FilePicker.pick(ChatActivity.this);
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = FilePicker.result(req, data);
        if (!uris.isEmpty()) FilePicker.startSend(this, address, uris);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (recorder.isRecording()) {
            // בזמן הקלטה: אישור/תפריט = שלח, חזרה/מקש רך ימני = בטל. שאר המקשים מושתקים
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (Keys.isSelect(code) || Keys.isMenu(code)) stopRecordingAndSend();
                else if (code == KeyEvent.KEYCODE_BACK || Keys.isBackAlt(code)) cancelRecording();
            }
            return true;
        }
        if (Keys.isMenu(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        // במקלדת T9 אין מקש מחיקה: חזרה בשדה עם טקסט מוחקת תו אחרון, ורק בשדה ריק יוצאים
        if (code == KeyEvent.KEYCODE_BACK && input != null && input.hasFocus() && input.getText().length() > 0) {
            int len = input.getText().length();
            input.getText().delete(len - 1, len);
            return true;
        }
        if (Keys.isBackAlt(code)) { finish(); return true; }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------ תצוגה

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private String describe(ChatStore.Msg m) {
        if (m.isVoice()) {
            boolean playing = m.text.equals(playingFile);
            return (playing ? "■ " : "▶ ") + getString(R.string.voice_label, mmss(Storage.voiceSeconds(m.text)));
        }
        if (ChatStore.T_FILE.equals(m.kind)) return "[קובץ] " + m.text;
        return m.text;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return msgs.size(); }
        @Override public Object getItem(int i) { return msgs.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert : LayoutInflater.from(ChatActivity.this).inflate(R.layout.row_msg, parent, false);
            ChatStore.Msg m = msgs.get(pos);
            TextView t = (TextView) v.findViewById(R.id.msgText);
            TextView time = (TextView) v.findViewById(R.id.msgTime);
            t.setText(describe(m));
            time.setText(fmt.format(new Date(m.time)));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(18);
            bg.setColor(attrColor(m.out ? R.attr.sbBubbleOut : R.attr.sbBubbleIn));
            t.setBackgroundDrawable(bg);
            int g = m.out ? Gravity.START : Gravity.END;
            ((LinearLayout.LayoutParams) t.getLayoutParams()).gravity = g;
            ((LinearLayout.LayoutParams) time.getLayoutParams()).gravity = g;
            return v;
        }
    }
}
