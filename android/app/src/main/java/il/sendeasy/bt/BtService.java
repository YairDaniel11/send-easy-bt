package il.sendeasy.bt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * שירות קבלה ברקע: מאזין ל-UUID של הפרוטוקול ומקבל קבצים והודעות ממכשירים מצומדים.
 * שירות חזית מסוג connectedDevice (חובה באנדרואיד 14), עם WakeLock רק בזמן חיבור פעיל.
 */
public class BtService extends Service {
    static final String CHANNEL_SERVICE = "se_service";
    static final String CHANNEL_SERVICE_MIN = "se_service_min";
    static final String CHANNEL_EVENTS = "se_events2";
    static final String CHANNEL_EVENTS_SILENT = "se_events_silent";
    private static final int ID_FOREGROUND = 1;
    private static final int ID_PROGRESS = 2;
    static final String ACTION_CANCEL_RX = "il.sendeasy.bt.CANCEL_RX";

    /** חיבורים שמקבלים עכשיו קובץ (לכתובת). */
    private static final ConcurrentHashMap<Session, String> RECEIVING = new ConcurrentHashMap<Session, String>();

    /** מבטל קבלת קובץ פעילה: מהמכשיר עם הכתובת הזו, או מכולם כש-address הוא null. */
    static void cancelReceiving(String address) {
        for (java.util.Map.Entry<Session, String> e : RECEIVING.entrySet()) {
            if (address == null || address.equals(e.getValue())) e.getKey().cancelIncomingFile();
        }
    }
    private static final int ID_EVENT_BASE = 100;

    private static volatile boolean running;

    static boolean isRunning() { return running; }

    /** חיבורים נכנסים פתוחים לפי כתובת המכשיר (לשימוש חוזר, למשל לשליחת READ מהצ'אט). */
    private static final ConcurrentHashMap<String, Session> BY_ADDRESS = new ConcurrentHashMap<String, Session>();

    /** חיבור נכנס פתוח מהמכשיר הזה, או null. */
    static Session openSession(String address) {
        Session s = BY_ADDRESS.get(address);
        return s != null && !s.isClosed() ? s : null;
    }

    /** מפעיל מחדש את השירות (נקרא מהחזית), כדי שההתראה הקבועה תיבנה מחדש. */
    static void restart(Context c) {
        stop(c);
        start(c);
    }

    /** מפעיל את השירות. חייבים לקרוא מהחזית (Activity גלוי), וכשיש הרשאת בלוטוס. */
    static void start(Context c) {
        if (!Perms.hasBluetooth(c)) return;
        Intent i = new Intent(c, BtService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException ignored) {
            // אנדרואיד 12+ אוסר הפעלה מהרקע: לא קריטי, המשתמש יפתח את האפליקציה
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BtService.class));
    }

    private volatile boolean alive;
    private Thread acceptThread;
    private volatile BluetoothServerSocket server;
    private PowerManager.WakeLock wakeLock;
    private int activeSessions;
    private int eventCounter;
    private long lastProgressNotify;
    private final ConcurrentHashMap<Session, Boolean> sessions = new ConcurrentHashMap<Session, Boolean>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // אחרי startForegroundService חובה לקרוא ל-startForeground, גם אם עומדים לעצור (אחרת קריסה ב-API 26+)
        if (intent != null && ACTION_CANCEL_RX.equals(intent.getAction()) && alive) {
            cancelReceiving(null);
            cancelProgress();
            return START_STICKY;
        }
        boolean fg = false;
        try {
            createChannels();
            Notification n = buildServiceNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(ID_FOREGROUND, n);
            }
            fg = true;
        } catch (RuntimeException e) {
            // כולל ForegroundServiceStartNotAllowedException (API 31+, תת-מחלקה של IllegalStateException)
        }
        if (!fg || !Perms.hasBluetooth(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!alive) {
            alive = true;
            running = true;
            acceptThread = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "SE-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Events.post(Events.STATE, "on");
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        alive = false;
        running = false;
        closeServer();
        for (Session s : sessions.keySet()) s.close();
        sessions.clear();
        synchronized (this) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
        Events.post(Events.STATE, "off");
        super.onDestroy();
    }

    private void closeServer() {
        BluetoothServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------ האזנה

    /** מוחק קבצי קבלה חלקיים ישנים (מעל שעה) שנשארו אחרי קריסה או ניתוק. */
    private void cleanOldParts() {
        try {
            File[] fs = Connector.tmpDir(this).listFiles();
            if (fs == null) return;
            long cutoff = System.currentTimeMillis() - 60L * 60 * 1000;
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith("rx_") && n.endsWith(".part") && f.lastModified() < cutoff) f.delete();
            }
        } catch (RuntimeException ignored) { }
    }

    private void acceptLoop() {
        cleanOldParts();
        long backoff = 1000;
        while (alive) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                sleep(3000);
                continue;
            }
            try {
                BluetoothServerSocket ss = adapter.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID);
                server = ss;
                if (!alive) { // onDestroy רץ לפני ההצבה ולא ראה את השרת: סוגרים בעצמנו (גם ב-finally)
                    closeServer();
                    return;
                }
                backoff = 1000;
                while (alive) {
                    BluetoothSocket sock = ss.accept(); // חוסם; close() משחרר
                    try {
                        handleIncoming(sock);
                    } catch (RuntimeException e) {
                        // חיבור בעייתי אחד לא יהרוג את שרשור ההאזנה
                        try { sock.close(); } catch (IOException ignored) { }
                    }
                }
            } catch (IOException e) {
                // סגירת השרת בכוונה, או בלוטוס שנכבה: מנסים שוב
            } catch (SecurityException e) {
                stopSelf(); // אין הרשאה: אין טעם להמשיך, ושלא יישאר שירות חזית ריק
                return;
            } finally {
                closeServer();
            }
            if (alive) {
                sleep(backoff);
                backoff = Math.min(backoff * 2, 10000);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void handleIncoming(BluetoothSocket sock) {
        BluetoothDevice dev = sock.getRemoteDevice();
        try {
            if (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
                sock.close();
                return;
            }
            final String address = dev.getAddress();
            Session s = new Session(sock.getInputStream(), sock.getOutputStream(), sock,
                    Prefs.deviceId(this), Connector.myName(this), Connector.tmpDir(this), new Incoming(address, deviceName(dev)));
            sessions.put(s, Boolean.TRUE);
            BY_ADDRESS.put(address, s);
            sessionStarted();
            s.start();
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private static String deviceName(BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && n.length() > 0) return n;
        } catch (SecurityException ignored) { }
        return d.getAddress();
    }

    // ------------------------------------------------------------ WakeLock

    private synchronized void sessionStarted() {
        activeSessions++;
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SendEasyBT::Session");
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(30 * 60 * 1000L);
    }

    private synchronized void sessionEnded() {
        if (activeSessions > 0) activeSessions--;
        if (activeSessions == 0 && wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ------------------------------------------------------------ אירועי חיבור נכנס

    private final class Incoming implements Session.Listener {
        private final String address;
        private final String realName;

        Incoming(String address, String name) { this.address = address; this.realName = name; }

        /** נקרא בכל אירוע כדי שכינוי שהשתנה באמצע חיבור ייכנס לתוקף. */
        private String name() { return DeviceNames.resolve(BtService.this, address, realName); }

        @Override public void onHello(Session s) { }

        @Override
        public void onText(Session s, long msgId, String text) {
            ChatStore.add(BtService.this, address, new ChatStore.Msg(System.currentTimeMillis(), false, ChatStore.T_TEXT, text, msgId, ChatStore.S_UNREAD));
            Events.post(Events.CHAT, address);
            notifyEvent(getString(R.string.rx_text, name()), text, address);
        }

        @Override
        public void onRead(Session s, long msgId) {
            if (ChatStore.markRead(BtService.this, address, msgId)) Events.post(Events.CHAT, address);
        }

        @Override
        public int onFileOffer(Session s, Session.IncomingFile f) {
            if (!Prefs.receiveEnabled(BtService.this)) return Protocol.REJECT_USER;
            if (!Storage.hasSpaceFor(BtService.this, f.size, f.name)) return Protocol.REJECT_STORAGE;
            return 0;
        }

        @Override
        public void onFileProgress(Session s, Session.IncomingFile f, long got) {
            RECEIVING.put(s, address);
            long now = System.currentTimeMillis();
            if (now - lastProgressNotify < 700 && got < f.size) return;
            lastProgressNotify = now;
            int pct = f.size > 0 ? (int) (got * 100 / f.size) : 0;
            Events.post(Events.PROGRESS, f.name + "|" + pct + "|" + address + "|" + (f.fromChat ? "1" : "0"));
            notifyProgress(f.name, pct);
        }

        @Override
        public boolean onFileReceived(Session s, Session.IncomingFile f, File temp) {
            RECEIVING.remove(s);
            cancelProgress(address);
            if (Storage.isVoiceName(f.name)) {
                // הודעה קולית: נשמרת בתיקייה פנימית ומופיעה בצ'אט, לא בתיקיית ההורדות
                String stored = Storage.storeVoice(BtService.this, temp, f.name);
                if (stored == null) return false;
                ChatStore.add(BtService.this, address, new ChatStore.Msg(System.currentTimeMillis(), false, ChatStore.T_VOICE, stored, 0, ChatStore.S_UNREAD));
                Events.post(Events.CHAT, address);
                Events.post(Events.VOICE, address);
                int secs = Storage.voiceSeconds(stored);
                notifyEvent(getString(R.string.rx_voice, name()), getString(R.string.voice_label, (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60)), address);
                return true;
            }
            // רק קובץ שנשלח מתוך הצ'אט מופיע בשיחה. קובץ שנשלח בשליחה רגילה (מסך שליחה במחשב) נשמר בתיקייה
            // ומוצג כהתראת קבלה רגילה, בלי שורה בצ'אט.
            boolean pic = f.fromChat && Storage.isImageName(f.name);
            // תמונה מהצ'אט: עותק פנימי להצגה בשיחה (בנוסף לפרסום בהורדות)
            String stored = pic ? Storage.storeImage(BtService.this, temp, f.name) : null;
            String where = Storage.publish(BtService.this, temp, f.name);
            if (where == null) {
                if (stored != null) new File(Storage.imagesDir(BtService.this), stored).delete();
                return false;
            }
            if (stored != null) {
                ChatStore.add(BtService.this, address, new ChatStore.Msg(System.currentTimeMillis(), false, ChatStore.T_PIC, stored, 0, ChatStore.S_UNREAD));
                Events.post(Events.CHAT, address);
                Events.post(Events.RECEIVED, f.name);
                notifyEvent(getString(R.string.rx_pic, name()), f.name, address);
                return true;
            }
            if (f.fromChat) {
                ChatStore.add(BtService.this, address, new ChatStore.Msg(System.currentTimeMillis(), false, ChatStore.T_FILE, f.name, 0, ChatStore.S_UNREAD));
                Events.post(Events.CHAT, address);
            }
            Events.post(Events.RECEIVED, f.name);
            notifyEvent(getString(R.string.rx_file, f.name), where, null, openFolder());
            return true;
        }

        @Override
        public void onFileFailed(Session s, Session.IncomingFile f, int status) {
            RECEIVING.remove(s);
            cancelProgress(address);
        }

        @Override
        public void onClosed(Session s, Exception why) {
            sessions.remove(s);
            BY_ADDRESS.remove(address, s);
            sessionEnded();
            RECEIVING.remove(s);
            cancelProgress(address);
        }
    }

    // ------------------------------------------------------------ התראות

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW));
            // ערוץ שקט וממוזער (בלי אייקון בסטטוס בר) להתראה הקבועה כשהמשתמש בחר להסתיר אותה
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_SERVICE_MIN, getString(R.string.channel_service_min), NotificationManager.IMPORTANCE_MIN));
            try { nm.deleteNotificationChannel("se_events"); } catch (RuntimeException ignored) { } // ישן: היה עם רטט של הערוץ
            NotificationChannel ev = new NotificationChannel(CHANNEL_EVENTS, getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT);
            ev.enableVibration(false); // הרטט ידני, לפי ההגדרה
            nm.createNotificationChannel(ev);
            NotificationChannel quiet = new NotificationChannel(CHANNEL_EVENTS_SILENT, getString(R.string.channel_events_silent), NotificationManager.IMPORTANCE_DEFAULT);
            quiet.setSound(null, null);
            quiet.enableVibration(false);
            nm.createNotificationChannel(quiet);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private PendingIntent openMain(String chatAddress) {
        Intent i = new Intent(this, chatAddress == null ? MainActivity.class : ChatActivity.class);
        if (chatAddress != null) i.putExtra(ChatActivity.EXTRA_ADDRESS, chatAddress);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, chatAddress == null ? 0 : chatAddress.hashCode(), i, fl);
    }

    @SuppressWarnings("deprecation")
    private Notification buildServiceNotification() {
        boolean show = Prefs.showServiceNotification(this);
        Notification.Builder b = builder(show ? CHANNEL_SERVICE : CHANNEL_SERVICE_MIN);
        if (!show && Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_ready))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(openMain(null))
                .setOngoing(true)
                .build();
    }

    private void notifyProgress(String name, int pct) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent ci = new Intent(this, BtService.class);
        ci.setAction(ACTION_CANCEL_RX);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, ci, fl);
        Notification n = builder(CHANNEL_EVENTS)
                .setContentTitle(getString(R.string.rx_progress, name))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.rx_cancel), cancelPi)
                .build();
        try { nm.notify(ID_PROGRESS, n); } catch (RuntimeException ignored) { }
    }

    private void cancelProgress(String address) {
        Events.post(Events.PROGRESS, "|-1|" + address + "|0"); // מסיר את שורת ההתקדמות בצ'אט
        cancelProgress();
    }

    private void cancelProgress() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_PROGRESS);
    }

    @SuppressWarnings("deprecation")
    private void notifyEvent(String title, String text, String chatAddress) {
        notifyEvent(title, text, chatAddress, null);
    }

    /** לחיצה על ההתראה של קובץ שהתקבל פותחת את התיקייה שבה הוא נשמר; אם אין אפליקציית קבצים מתאימה - את מסך ההורדות, ואחרת את האפליקציה. */
    private PendingIntent openFolder() {
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        try {
            Intent i = Storage.folderIntent(this);
            if (getPackageManager().resolveActivity(i, 0) != null) return PendingIntent.getActivity(this, 7, i, fl);
        } catch (RuntimeException ignored) { }
        Intent d = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().resolveActivity(d, 0) != null) return PendingIntent.getActivity(this, 8, d, fl);
        return openMain(null);
    }

    private void notifyEvent(String title, String text, String chatAddress, PendingIntent target) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !Prefs.notifyEvents(this)) return;
        if (chatAddress != null && chatAddress.equals(ChatActivity.openAddress)) return; // הצ'אט פתוח בחזית
        boolean sound = Prefs.notifySound(this);
        Notification n = builder(sound ? CHANNEL_EVENTS : CHANNEL_EVENTS_SILENT)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(target != null ? target : openMain(chatAddress))
                .setAutoCancel(true)
                .setDefaults(sound ? (Notification.DEFAULT_SOUND | Notification.DEFAULT_LIGHTS) : Notification.DEFAULT_LIGHTS) // צליל ורטט ל-API פחות מ-26 (מ-26 הערוץ קובע)
                .build();
        try { nm.notify(ID_EVENT_BASE + (eventCounter++ % 50), n); } catch (RuntimeException ignored) { }
        if (Prefs.notifyVibrate(this)) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(400);
            } catch (RuntimeException ignored) { }
        }
        if (Prefs.notifyWake(this)) wakeScreen();
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "sendeasy:notify");
            w.setReferenceCounted(false);
            w.acquire(5000); // משתחרר אוטומטית אחרי 5 שניות
        } catch (RuntimeException ignored) { }
    }
}