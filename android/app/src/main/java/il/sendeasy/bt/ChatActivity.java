package il.sendeasy.bt;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** צ'אט טקסט עם מכשיר מצומד. החיבור נשמר פתוח כל עוד המסך פתוח. */
public class ChatActivity extends Activity implements Events.Listener {
    static final String EXTRA_ADDRESS = "address";

    private String address;
    private String deviceName;
    private ListView list;
    private TextView emptyT;
    private EditText input;
    private Button sendBtn;
    private final List<ChatStore.Msg> msgs = new ArrayList<ChatStore.Msg>();
    private Adapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Session session; // רק מהשרשור של io
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);

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
        ((TextView) findViewById(R.id.chatTitle)).setText(getString(R.string.chat_title, deviceName));

        list = (ListView) findViewById(R.id.chatList);
        emptyT = (TextView) findViewById(R.id.chatEmpty);
        input = (EditText) findViewById(R.id.chatInput);
        sendBtn = (Button) findViewById(R.id.chatSend);
        adapter = new Adapter();
        list.setAdapter(adapter);

        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
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
        if (Events.CHAT.equals(kind) && address.equals(arg)) reload();
    }

    private void reload() {
        msgs.clear();
        msgs.addAll(ChatStore.load(this, address));
        emptyT.setVisibility(msgs.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (!msgs.isEmpty()) list.setSelection(msgs.size() - 1);
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
                    if (!trySend(text)) err = "לא התקבל אישור";
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
    private boolean trySend(String text) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (session == null || session.isClosed()) {
                BluetoothDevice dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
                session = Connector.connect(this, dev, new Connector.QuietListener(), null);
            }
            try {
                return session.sendText(text);
            } catch (IOException e) {
                session.close();
                session = null;
                if (attempt == 1) throw e;
            }
        }
        return false;
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

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
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
            t.setText(m.text);
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