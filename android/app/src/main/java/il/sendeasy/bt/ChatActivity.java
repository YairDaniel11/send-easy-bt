package il.sendeasy.bt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
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
    private TextView emptyT, recT, recHintT, titleT, subT, avatarT, micBtn, sendBtn, attachBtn, sendProgressT;
    private TextView recPauseBtn, recSendBtn, recCancelBtn;
    private View recBar;
    private EditText input;
    private View root, header, inputRow;
    private final List<ChatStore.Msg> msgs = new ArrayList<ChatStore.Msg>();
    private Adapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Session session; // נכתב מהשרשור של io; onDestroy סוגר אותו ישירות
    private volatile boolean destroyed;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean touch;
    private int lastPicPos = -1;
    private long lastPicTime;
    private int highlightPos = -1;
    private final AtomicBoolean picCancel = new AtomicBoolean(false);
    private volatile boolean picSending;
    private static final int GREEN = 0xFF1B9E4B;

    // פלטת הצבעים של הסגנון הנוכחי
    private int style;
    private int cBg, cHeader, cHeaderText, cHeaderSub, cText, cSub, cMine, cTheirs, cAccent, cInput;

    private final VoiceRecorder recorder = new VoiceRecorder();
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recorder.isRecording()) return;
            long ms = recorder.elapsedMs();
            if (recorder.hasFailed()) {
                cancelRecording();
                Toast.makeText(ChatActivity.this, R.string.voice_no_mic, Toast.LENGTH_LONG).show();
                return;
            }
            if (ms >= MAX_RECORD_MS) { stopRecordingAndSend(); return; }
            updateRecUi();
            ui.postDelayed(this, 300);
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
        recHintT = (TextView) findViewById(R.id.chatRecHint);
        recBar = findViewById(R.id.chatRecBar);
        recPauseBtn = (TextView) findViewById(R.id.chatRecPause);
        recSendBtn = (TextView) findViewById(R.id.chatRecSend);
        recCancelBtn = (TextView) findViewById(R.id.chatRecCancel);
        attachBtn = (TextView) findViewById(R.id.chatAttach);
        sendProgressT = (TextView) findViewById(R.id.chatSendProgress);
        touch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        if (!touch) {
            recHintT.setText(R.string.voice_rec_hint);
            recHintT.setVisibility(View.VISIBLE);
        }
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
                else if (m.isPic()) onPicClick(pos, m);
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
        attachBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { FilePicker.pickImages(ChatActivity.this); }
        });
        recPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePause(); }
        });
        recSendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopRecordingAndSend(); }
        });
        recCancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelRecording(); }
        });
        sendProgressT.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { picCancel.set(true); }
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
        attachBtn.setTextColor(fg);
        micBtn.setBackgroundDrawable(focusCircle());
        sendBtn.setBackgroundDrawable(focusCircle());
        attachBtn.setBackgroundDrawable(focusCircle());
        // כפתורי פס ההקלטה על רקע אדום: עיגול שקוף-לבן, ובפוקוס טבעת לבנה
        recPauseBtn.setBackgroundDrawable(recCircle());
        recSendBtn.setBackgroundDrawable(recCircle());
        recCancelBtn.setBackgroundDrawable(recCircle());
        sendProgressT.setTextColor(cText);
        sendProgressT.setBackgroundColor(cInput);
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

    private StateListDrawable recCircle() {
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable focused = UiKit.oval(0x66FFFFFF);
        focused.setStroke(UiKit.dp(this, 3), 0xFFFFFFFF);
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, UiKit.oval(0x33FFFFFF));
        return sl;
    }

    /** כשיש טקסט: כפתור שליחה; כשאין: מיקרופון. */
    private void updateActionButtons() {
        boolean has = input.getText().length() > 0;
        sendBtn.setVisibility(has ? View.VISIBLE : View.GONE);
        micBtn.setVisibility(has ? View.GONE : View.VISIBLE);
        attachBtn.setNextFocusRightId(has ? R.id.chatSend : R.id.chatMic);
    }

    // ------------------------------------------------------------ מחזור חיים

    @Override
    protected void onResume() {
        super.onResume();
        if (address == null) return;
        Events.add(this);
        reload();
        flushReads();
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
        picCancel.set(true);
        ui.removeCallbacks(clearHighlight);
        Session s = session;
        session = null;
        if (s != null) s.close();
        io.shutdownNow(); // מפסיק גם שליחה/המתנה שרצה
        super.onDestroy();
    }

    @Override
    public void onEvent(String kind, String arg) {
        if (address == null || !address.equals(arg)) return;
        if (Events.CHAT.equals(kind)) {
            reload();
            flushReads();
        } else if (Events.VOICE.equals(kind)) {
            onVoiceArrived();
        }
    }

    private final Runnable clearHighlight = new Runnable() {
        @Override public void run() {
            highlightPos = -1;
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    };

    /** הגיעה הודעה קולית והצ'אט פתוח: גוללים אליה, מדגישים לרגע ורוטטים קצרות. */
    private void onVoiceArrived() {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).isVoice() && !msgs.get(i).out) {
                highlightPos = i;
                list.setSelection(i);
                adapter.notifyDataSetChanged();
                ui.removeCallbacks(clearHighlight);
                ui.postDelayed(clearHighlight, 2500);
                break;
            }
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(150);
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------ אישורי קריאה

    /** הצ'אט גלוי: ההודעות הנכנסות נקראו. שולחים READ (פעם אחת לכל הודעה) ומסמנים. */
    private void flushReads() {
        if (address == null || destroyed) return;
        final Context app = getApplicationContext();
        ChatStore.markInboundSeen(app, address);
        try {
            io.execute(new Runnable() {
                @Override public void run() { sendPendingReads(app); }
            });
        } catch (RuntimeException ignored) { } // RejectedExecutionException אחרי onDestroy
    }

    private void sendPendingReads(Context app) {
        List<Long> ids = ChatStore.pendingReadIds(app, address);
        if (ids.isEmpty()) return;
        Session s = null;
        try {
            s = BtService.openSession(address); // מעדיפים חיבור נכנס פתוח
            if (s == null) s = ensureSession();
            for (Long id : ids) {
                s.sendRead(id);
                ChatStore.markReadSent(app, address, id);
            }
        } catch (IOException e) {
            if (s != null && s == session) { s.close(); session = null; }
        } catch (InterruptedException e) {
            // הצ'אט נסגר
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------ תמונות

    private void onPicClick(int pos, ChatStore.Msg m) {
        if (touch) { openImage(m); return; }
        // מכשיר מקשים: לחיצה כפולה על אישור
        long now = SystemClock.uptimeMillis();
        if (pos == lastPicPos && now - lastPicTime <= 700) {
            lastPicPos = -1;
            openImage(m);
        } else {
            lastPicPos = pos;
            lastPicTime = now;
            Toast.makeText(this, R.string.img_tap_again, Toast.LENGTH_SHORT).show();
        }
    }

    private void openImage(ChatStore.Msg m) {
        Intent i = new Intent(this, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_NAME, m.text);
        startActivity(i);
    }

    private String imageNameOf(Uri u) {
        String name = null;
        if ("file".equals(u.getScheme())) {
            name = new File(u.getPath()).getName();
        } else {
            Cursor c = null;
            try {
                c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        if (name == null || name.length() == 0) name = "image";
        if (Storage.isImageName(name)) return name;
        String mime = null;
        try { mime = getContentResolver().getType(u); } catch (RuntimeException ignored) { }
        if (mime == null) return null;
        if (mime.equals("image/jpeg")) return name + ".jpg";
        if (mime.equals("image/png")) return name + ".png";
        if (mime.equals("image/gif")) return name + ".gif";
        if (mime.equals("image/webp")) return name + ".webp";
        if (mime.equals("image/bmp")) return name + ".bmp";
        return null;
    }

    private void setPicProgress(final int idx, final int total, final int pct) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                sendProgressT.setText(getString(R.string.sending_pics, idx, total, pct));
                sendProgressT.setVisibility(View.VISIBLE);
            }
        });
    }

    /** שולח תמונות על ה-session של הצ'אט: עותק פנימי, בועה מיידית עם ✓, ואחרי השליחה ✓✓. */
    private void sendImages(final List<Uri> uris) {
        if (picSending) { Toast.makeText(this, R.string.sending_pics_busy, Toast.LENGTH_SHORT).show(); return; }
        final Context app = getApplicationContext();
        picSending = true;
        picCancel.set(false);
        setPicProgress(1, uris.size(), 0);
        try {
            io.execute(new Runnable() {
                @Override public void run() {
                    int total = uris.size();
                    try {
                        for (int i = 0; i < total && !picCancel.get(); i++) {
                            final int idx = i + 1;
                            setPicProgress(idx, total, 0);
                            Uri u = uris.get(i);
                            String name = imageNameOf(u);
                            File copy = name == null ? null : Storage.storeImage(app, u, name);
                            if (copy == null) { fail(getString(R.string.img_open_failed)); continue; }
                            ChatStore.add(app, address, new ChatStore.Msg(System.currentTimeMillis(), true, ChatStore.T_PIC, copy.getName(), 0, ChatStore.S_SENT));
                            Events.post(Events.CHAT, address);
                            String err = null;
                            try {
                                final int tot2 = total;
                                err = sendLocalFile(copy, picCancel, new Session.SendProgress() {
                                    @Override public void onProgress(long sent, long tot) {
                                        setPicProgress(idx, tot2, tot > 0 ? (int) (sent * 100 / tot) : 0);
                                    }
                                });
                            } catch (IOException e) {
                                err = String.valueOf(e.getMessage());
                            } catch (RuntimeException e) {
                                err = String.valueOf(e.getMessage());
                            }
                            if (err == null) {
                                ChatStore.markDelivered(app, address, copy.getName());
                            } else {
                                ChatStore.removeOut(app, address, copy.getName());
                                copy.delete();
                                if (!picCancel.get()) fail(getString(R.string.chat_failed) + ": " + err);
                            }
                            Events.post(Events.CHAT, address);
                        }
                    } catch (InterruptedException e) {
                        // הצ'אט נסגר
                    } finally {
                        picSending = false;
                        ui.post(new Runnable() {
                            @Override public void run() { sendProgressT.setVisibility(View.GONE); }
                        });
                    }
                }
            });
        } catch (RuntimeException e) {
            picSending = false;
            sendProgressT.setVisibility(View.GONE);
        }
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
            Session s = Connector.connect(getApplicationContext(), dev, new Connector.QuietListener() {
                @Override public void onRead(Session sess, long msgId) {
                    if (ChatStore.markRead(getApplicationContext(), address, msgId)) Events.post(Events.CHAT, address);
                }
            }, null);
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
                long msgId = 0;
                try {
                    msgId = trySendText(text);
                    if (msgId == 0) err = "לא התקבל אישור";
                } catch (IOException e) {
                    err = e.getMessage();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    err = String.valueOf(e.getMessage());
                }
                if (err == null) {
                    ChatStore.add(app, address, new ChatStore.Msg(System.currentTimeMillis(), true, ChatStore.T_TEXT, text, msgId, ChatStore.S_DELIVERED));
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
    private long trySendText(String text) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session s = ensureSession();
            try {
                return s.sendTextWithId(text);
            } catch (IOException e) {
                s.close();
                session = null;
                if (attempt == 1) throw e;
            }
        }
        return 0;
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
        recBar.setVisibility(View.VISIBLE);
        inputRow.setVisibility(View.GONE);
        recPauseBtn.setVisibility(recorder.canPause() ? View.VISIBLE : View.GONE);
        updateRecUi();
        ui.postDelayed(recTick, 300);
        recPauseBtn.requestFocus();
    }

    private void updateRecUi() {
        boolean paused = recorder.isPaused();
        recT.setText(getString(paused ? R.string.voice_paused : R.string.voice_recording, mmss((int) (recorder.elapsedMs() / 1000))));
        recPauseBtn.setText(paused ? "▶" : "⏸");
    }

    private void togglePause() {
        if (!recorder.isRecording() || !recorder.canPause()) return;
        if (recorder.isPaused()) recorder.resume(); else recorder.pause();
        updateRecUi();
    }

    private void endRecordingUi() {
        ui.removeCallbacks(recTick);
        recBar.setVisibility(View.GONE);
        inputRow.setVisibility(View.VISIBLE);
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
                    err = sendLocalFile(f, new AtomicBoolean(false), null);
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
    private String sendLocalFile(File f, AtomicBoolean cancel, Session.SendProgress progress) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session s = ensureSession();
            try {
                FileInputStream in = new FileInputStream(f);
                int r;
                try {
                    r = s.sendFile(in, f.getName(), f.length(), progress, cancel);
                } finally {
                    try { in.close(); } catch (IOException ignored) { }
                }
                if (r == Session.R_OK) return null;
                if (r == Session.R_REJECTED) return getString(R.string.send_rejected);
                if (r == Session.R_CANCELLED) return getString(R.string.send_cancelled);
                if (r == Session.R_HASH) return "הקובץ הגיע פגום";
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
        ArrayList<Uri> imgs = FilePicker.imageResult(req, data);
        if (!imgs.isEmpty()) { sendImages(imgs); return; }
        ArrayList<Uri> uris = FilePicker.result(req, data);
        if (!uris.isEmpty()) FilePicker.startSend(this, address, uris);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (recorder.isRecording()) {
            // בזמן הקלטה: אישור = השהה/המשך (או הכפתור הממוקד), תפריט/מקש רך שמאלי = שלח, חזרה/מקש רך ימני = בטל
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
            if (code == KeyEvent.KEYCODE_BACK || Keys.isBackAlt(code)) {
                if (down) cancelRecording();
                return true;
            }
            if (Keys.isMenu(code)) {
                if (down) stopRecordingAndSend();
                return true;
            }
            if (Keys.isSelect(code)) {
                View f = getCurrentFocus();
                if (f == recPauseBtn || f == recSendBtn || f == recCancelBtn) return super.dispatchKeyEvent(e); // לחיצה על הכפתור הממוקד
                if (down) togglePause();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return super.dispatchKeyEvent(e); // מעבר בין שלושת הכפתורים
            }
            return true;
        }
        if (picSending && Keys.isBackAlt(code)) { // מקש רך ימני בזמן שליחת תמונות = ביטול
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) picCancel.set(true);
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
        if (m.isPic()) return picView(m);
        TextView t = text(ChatStore.T_FILE.equals(m.kind) ? "[קובץ] " + m.text : m.text, cText, 16);
        t.setMaxWidth(UiKit.dp(this, 270));
        return t;
    }

    private int picHeight(Bitmap b) {
        int w = UiKit.dp(this, 220);
        if (b == null || b.getWidth() == 0) return UiKit.dp(this, 150);
        int h = (int) ((long) w * b.getHeight() / b.getWidth());
        return Math.max(UiKit.dp(this, 90), Math.min(UiKit.dp(this, 300), h));
    }

    /** תמונה ממוזערת בבועה: מהמטמון מיד, אחרת פענוח ברקע. */
    private View picView(ChatStore.Msg m) {
        final File f = new File(Storage.imagesDir(this), new File(m.text).getName());
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundDrawable(UiKit.rect(this, 0x22888888, 10));
        iv.setTag(f.getPath());
        Bitmap b = Thumbs.cached(f);
        iv.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, 220), picHeight(b)));
        if (b != null) {
            iv.setImageBitmap(b);
        } else {
            Thumbs.load(f, new Thumbs.Callback() {
                @Override public void onThumb(Bitmap bm) {
                    if (bm == null || !f.getPath().equals(iv.getTag())) return;
                    iv.setImageBitmap(bm);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(ChatActivity.this, 220), picHeight(bm)));
                }
            });
        }
        return iv;
    }

    /** שעה, ולהודעה יוצאת גם סימני ✓ (נשלחה), ✓✓ אפור (נמסרה), ✓✓ ירוק (נקראה). */
    private CharSequence timeWithTicks(ChatStore.Msg m, boolean withTime) {
        String t = withTime ? fmt.format(new Date(m.time)) : "";
        if (!m.out) return t;
        SpannableStringBuilder sb = new SpannableStringBuilder(withTime ? t + "  " : t);
        int start = sb.length();
        sb.append(ChatStore.S_SENT.equals(m.status) ? "✓" : "✓✓");
        sb.setSpan(new ForegroundColorSpan(ChatStore.S_READ.equals(m.status) ? GREEN : cSub), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
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
        TextView time = text("", cSub, 11);
        time.setText(timeWithTicks(m, true));
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
            TextView tm = text("", cSub, 12);
            tm.setText(new SpannableStringBuilder("   ").append(timeWithTicks(m, true)));
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
        if (m.out && grouped) { // בלי כותרת: סימני הסטטוס בשורה קטנה מתחת להודעה
            TextView st = text("", cSub, 11);
            st.setText(timeWithTicks(m, false));
            col.addView(st);
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
            boolean sel = (list.hasFocus() && list.getSelectedItemPosition() == pos) || pos == highlightPos;
            return style == 0 ? waRow(m, sel) : gcRow(m, pos > 0 ? msgs.get(pos - 1) : null, sel);
        }
    }
}
