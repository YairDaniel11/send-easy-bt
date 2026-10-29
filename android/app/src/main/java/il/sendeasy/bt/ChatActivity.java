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
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
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
 * צ'אט עם מכשיר מצומד: טקסט, הודעות קוליות וקבצים, בשני סגנונות (ווטסאפ או גוגל צ'אט).
 * מקשים: אישור = פעולה, תפריט = הקלטה/קובץ/עיצוב, בזמן הקלטה: אישור = שלח, חזרה = בטל.
 */
public class ChatActivity extends Activity implements Events.Listener {
    static final String EXTRA_ADDRESS = "address";
    private static final int REQ_MIC = 4002;
    private static final long MAX_RECORD_MS = 5 * 60 * 1000;

    private String address;
    private String deviceName;
    private String realName;
    private ListView list;
    private TextView emptyT, recT, titleT, subT, avatarT, micBtn, sendBtn;
    private EditText input;
    private View root, header, inputRow;
    private final List<ChatStore.Msg> msgs = new ArrayList<ChatStore.Msg>();
    private Adapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Session session; // נכתב מהשרשור של io; onDestroy סוגר אותו ישירות
    private volatile boolean destroyed;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final Handler ui = new Handler(Looper.getMainLooper());

    // פלטת הצבעים של הסגנון הנוכחי
    private int style;
    private int cBg, cHeader, cHeaderText, cHeaderSub, cText, cSub, cMine, cTheirs, cAccent, cInput;

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
    private UiKit.WaveView playingWave;
    private final Runnable playTick = new Runnable() {
        @Override public void run() {
            MediaPlayer p = player;
            if (p == null) return;
            try {
                int d = p.getDuration();
                if (playingWave != null && d > 0) playingWave.setProgress(p.getCurrentPosition() / (float) d);
            } catch (RuntimeException ignored) { }
            ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        setContentView(R.layout.activity_chat);
        ThemeUtil.forceRtl(this);

        address = getIntent().getStringExtra(EXTRA_ADDRESS);
        if (address == null) { finish(); return; }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        realName = address;
        if (bt != null) {
            try { realName = MainActivity.nameOf(bt.getRemoteDevice(address)); } catch (RuntimeException ignored) { }
        }
        deviceName = DeviceNames.resolve(this, address, realName);

        root = findViewById(R.id.chatRoot);
        header = findViewById(R.id.chatHeader);
        inputRow = findViewById(R.id.chatInputRow);
        titleT = (TextView) findViewById(R.id.chatTitle);
        subT = (TextView) findViewById(R.id.chatSub);
        avatarT = (TextView) findViewById(R.id.chatAvatar);
        list = (ListView) findViewById(R.id.chatList);
        emptyT = (TextView) findViewById(R.id.chatEmpty);
        recT = (TextView) findViewById(R.id.chatRec);
        input = (EditText) findViewById(R.id.chatInput);
        sendBtn = (TextView) findViewById(R.id.chatSend);
        micBtn = (TextView) findViewById(R.id.chatMic);

        titleT.setText(Fmt.ltr(deviceName));
        subT.setText(R.string.chat_sub);
        UiKit.avatar(avatarT, deviceName, 40);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ChatStore.Msg m = msgs.get(pos);
                if (m.isVoice()) togglePlay(m);
            }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { adapter.notifyDataSetChanged(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
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
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { updateActionButtons(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        applyStyle();
        reload();
        input.requestFocus();
    }

    // ------------------------------------------------------------ עיצוב

    private void applyStyle() {
        style = Prefs.chatStyle(this);
        boolean dark = ThemeUtil.isDark(this);
        if (style == 0) { // ווטסאפ
            cBg = dark ? 0xFF0B141A : 0xFFEFE7DE;
            cHeader = dark ? 0xFF1F2C34 : 0xFF008069;
            cHeaderText = 0xFFFFFFFF; cHeaderSub = 0xCCFFFFFF;
            cText = dark ? 0xFFE9EDEF : 0xFF111B21;
            cSub = dark ? 0xFF8696A0 : 0xFF667781;
            cMine = dark ? 0xFF005C4B : 0xFFD9FDD3;
            cTheirs = dark ? 0xFF202C33 : 0xFFFFFFFF;
            cAccent = 0xFF00A884;
            cInput = dark ? 0xFF2A3942 : 0xFFFFFFFF;
        } else { // גוגל צ'אט
            cBg = dark ? 0xFF1F1F1F : 0xFFFFFFFF;
            cHeader = dark ? 0xFF1F1F1F : 0xFFFFFFFF;
            cHeaderText = dark ? 0xFFE3E3E3 : 0xFF1F1F1F; cHeaderSub = dark ? 0xFF9AA0A6 : 0xFF5F6368;
            cText = dark ? 0xFFE3E3E3 : 0xFF1F1F1F;
            cSub = dark ? 0xFF9AA0A6 : 0xFF5F6368;
            cMine = dark ? 0xFF2D2F31 : 0xFFE9EEF6;
            cTheirs = dark ? 0xFF2D2F31 : 0xFFF0F4F9;
            cAccent = dark ? 0xFFA8C7FA : 0xFF0B57D0;
            cInput = dark ? 0xFF2D2F31 : 0xFFF0F4F9;
        }
        root.setBackgroundColor(cBg);
        header.setBackgroundColor(cHeader);
        titleT.setTextColor(cHeaderText);
        subT.setTextColor(cHeaderSub);
        emptyT.setTextColor(cSub);
        inputRow.setBackgroundColor(style == 0 ? 0x00000000 : cBg);
        input.setBackgroundDrawable(UiKit.rect(this, cInput, 24));
        input.setTextColor(cText);
        input.setHintTextColor(cSub);
        int fg = style == 1 && ThemeUtil.isDark(this) ? 0xFF1F1F1F : 0xFFFFFFFF;
        micBtn.setTextColor(fg);
        sendBtn.setTextColor(fg);
        micBtn.setBackgroundDrawable(focusCircle());
        sendBtn.setBackgroundDrawable(focusCircle());
        updateActionButtons();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** עיגול צבעוני; בפוקוס (ניווט מקשים) נוספת טבעת בולטת. */
    private StateListDrawable focusCircle() {
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable focused = UiKit.oval(cAccent);
        focused.setStroke(UiKit.dp(this, 3), style == 0 ? 0xFFFFFFFF : cText);
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, UiKit.oval(cAccent));
        return sl;
    }

    /** כשיש טקסט: כפתור שליחה; כשאין: מיקרופון. */
    private void updateActionButtons() {
        boolean has = input.getText().length() > 0;
        sendBtn.setVisibility(has ? View.VISIBLE : View.GONE);
        micBtn.setVisibility(has ? View.GONE : View.VISIBLE);
        input.setNextFocusRightId(has ? R.id.chatSend : R.id.chatMic);
    }

    // ------------------------------------------------------------ מחזור חיים

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
        destroyed = true;
        Session s = session;
        session = null;
        if (s != null) s.close();
        io.shutdownNow(); // מפסיק גם שליחה/המתנה שרצה
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
            Session s = Connector.connect(getApplicationContext(), dev, new Connector.QuietListener(), null);
            if (destroyed) { // ה-Activity נסגר בזמן ההתחברות: לא משאירים חיבור יתום
                s.close();
                throw new InterruptedException();
            }
            session = s;
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
                if (r == Session.R_CANCELLED) return getString(R.string.send_cancelled);
                if (r == Session.R_HASH) return "ההודעה הגיעה פגומה";
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
        playingWave = null;
        ui.removeCallbacks(playTick);
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
        ui.postDelayed(playTick, 150);
    }

    // ------------------------------------------------------------ מקשים ותפריט

    private void showMenu() {
        final String[] items = {getString(R.string.voice_record), getString(R.string.chat_menu_file), getString(R.string.chat_menu_style), getString(R.string.act_rename)};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) toggleRecording();
                        else if (which == 1) FilePicker.pick(ChatActivity.this);
                        else if (which == 2) chooseStyle();
                        else DeviceNames.rename(ChatActivity.this, address, realName, new Runnable() {
                            @Override public void run() {
                                deviceName = DeviceNames.resolve(ChatActivity.this, address, realName);
                                titleT.setText(Fmt.ltr(deviceName));
                                UiKit.avatar(avatarT, deviceName, 40);
                                adapter.notifyDataSetChanged();
                            }
                        });
                    }
                })
                .show();
    }

    private void chooseStyle() {
        final String[] names = {getString(R.string.style_wa), getString(R.string.style_gc)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_menu_style)
                .setSingleChoiceItems(names, Prefs.chatStyle(this), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.setChatStyle(ChatActivity.this, which);
                        d.dismiss();
                        applyStyle();
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
        // בשדה הקלט # הוא תו רגיל; התפריט נשאר זמין במקשי MENU והמקש הרך השמאלי
        boolean typingPound = code == KeyEvent.KEYCODE_POUND && input != null && input.hasFocus();
        if (Keys.isMenu(code) && !typingPound) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        // במקלדת T9 אין מקש מחיקה: חזרה/CLEAR בשדה עם טקסט מוחקת תו אחרון, ורק בשדה ריק יוצאים
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_CLEAR) && input != null && input.hasFocus() && input.getText().length() > 0) {
            int len = input.getText().length();
            input.getText().delete(len - 1, len);
            return true;
        }
        if (Keys.isBackAlt(code)) { finish(); return true; }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------ בניית שורות הודעה

    private TextView text(String s, int color, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    /** תוכן ההודעה: טקסט, קובץ, או נגן קול (כפתור, גל קול ומשך). */
    private View content(ChatStore.Msg m) {
        if (m.isVoice()) {
            boolean playing = m.text.equals(playingFile);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView play = text(playing ? "■" : "▶", 0xFFFFFFFF, 14);
            play.setGravity(Gravity.CENTER);
            play.setBackgroundDrawable(UiKit.oval(cAccent));
            row.addView(play, new LinearLayout.LayoutParams(UiKit.dp(this, 36), UiKit.dp(this, 36)));
            UiKit.WaveView wave = new UiKit.WaveView(this, m.text.hashCode(), cAccent, (cSub & 0x00FFFFFF) | 0x88000000);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(UiKit.dp(this, 120), UiKit.dp(this, 30));
            wl.leftMargin = UiKit.dp(this, 10); wl.rightMargin = UiKit.dp(this, 10);
            row.addView(wave, wl);
            if (playing) playingWave = wave;
            row.addView(text(mmss(Storage.voiceSeconds(m.text)), cSub, 12));
            return row;
        }
        TextView t = text(ChatStore.T_FILE.equals(m.kind) ? "[קובץ] " + m.text : m.text, cText, 16);
        t.setMaxWidth(UiKit.dp(this, 270));
        return t;
    }

    private View waRow(ChatStore.Msg m, boolean sel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 3), UiKit.dp(this, 12), UiKit.dp(this, 3));
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 7), UiKit.dp(this, 11), UiKit.dp(this, 5));
        int color = m.out ? cMine : cTheirs;
        bubble.setBackgroundDrawable(sel ? UiKit.rect(this, color, 14, cAccent, 3) : UiKit.rect(this, color, 14));
        bubble.addView(content(m));
        TextView time = text(fmt.format(new Date(m.time)) + (m.out ? "  ✓" : ""), cSub, 11);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.gravity = Gravity.END;
        tl.topMargin = UiKit.dp(this, 2);
        bubble.addView(time, tl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.gravity = m.out ? Gravity.START : Gravity.END;
        row.addView(bubble, bl);
        return row;
    }

    private View gcRow(ChatStore.Msg m, ChatStore.Msg prev, boolean sel) {
        boolean grouped = prev != null && prev.out == m.out && (m.time - prev.time) < 6 * 60 * 1000;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, grouped ? 1 : 10), UiKit.dp(this, 14), UiKit.dp(this, 1));
        String who = m.out ? getString(R.string.chat_me) : deviceName;
        TextView av = new TextView(this);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(UiKit.dp(this, 36), UiKit.dp(this, 36));
        al.rightMargin = UiKit.dp(this, 10);
        row.addView(av, al);
        if (grouped) av.setVisibility(View.INVISIBLE);
        UiKit.avatar(av, who, 36);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (!grouped) {
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView n = text(m.out ? who : Fmt.ltr(who), cText, 14);
            n.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(n);
            TextView tm = text("   " + fmt.format(new Date(m.time)), cSub, 12);
            head.addView(tm);
            col.addView(head);
        }
        View c = content(m);
        if (m.isVoice()) {
            // בגוגל צ'אט הקול בתוך "גלולה"
            LinearLayout pill = new LinearLayout(this);
            pill.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 12), UiKit.dp(this, 6));
            pill.setBackgroundDrawable(UiKit.rect(this, m.out ? cMine : cTheirs, 22));
            pill.addView(c);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.topMargin = UiKit.dp(this, 3);
            col.addView(pill, pl);
        } else {
            col.addView(c);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (sel) row.setBackgroundDrawable(UiKit.rect(this, 0x00000000, 12, cAccent, 3));
        return row;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return msgs.size(); }
        @Override public Object getItem(int i) { return msgs.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            ChatStore.Msg m = msgs.get(pos);
            boolean sel = list.hasFocus() && list.getSelectedItemPosition() == pos;
            return style == 0 ? waRow(m, sel) : gcRow(m, pos > 0 ? msgs.get(pos - 1) : null, sel);
        }
    }
}
