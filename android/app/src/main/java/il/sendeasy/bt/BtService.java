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
    static final String CHANNEL_EVENTS = "se_events";
    private static final int ID_FOREGROUND = 1;
    private static final int ID_PROGRESS = 2;
    private static final int ID_EVENT_BASE = 100;

    private static volatile boolean running;

    static boolean isRunning() { return running; }

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
        if (!Perms.hasBluetooth(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannels();
        Notification n = buildServiceNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(ID_FOREGROUND, n);
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

    private void acceptLoop() {
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
                backoff = 1000;
                while (alive) {
                    BluetoothSocket sock = ss.accept(); // חוסם; close() משחרר
                    handleIncoming(sock);
                }
            } catch (IOException e) {
                // סגירת השרת בכוונה, או בלוטוס שנכבה: מנסים שוב
            } catch (SecurityException e) {
                return; // אין הרשאה: אין טעם להמשיך
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
        private final String name;

        Incoming(String address, String name) { this.address = address; this.name = name; }

        @Override public void onHello(Session s) { }

        @Override
        public void onText(Session s, long msgId, String text) {
            ChatStore.append(BtService.this, address, false, text, System.currentTimeMillis());
            Events.post(Events.CHAT, address);
            notifyEvent(getString(R.string.rx_text, name), text, address);
        }

        @Override
        public int onFileOffer(Session s, Session.IncomingFile f) {
            if (!Prefs.receiveEnabled(BtService.this)) return Protocol.REJECT_USER;
            return 0;
        }

        @Override
        public void onFileProgress(Session s, Session.IncomingFile f, long got) {
            long now = System.currentTimeMillis();
            if (now - lastProgressNotify < 700 && got < f.size) return;
            lastProgressNotify = now;
            int pct = f.size > 0 ? (int) (got * 100 / f.size) : 0;
            Events.post(Events.PROGRESS, f.name + "|" + pct);
            notifyProgress(f.name, pct);
        }

        @Override
        public boolean onFileReceived(Session s, Session.IncomingFile f, File temp) {
            cancelProgress();
            String where = Storage.publish(BtService.this, temp, f.name);
            if (where == null) return false;
            Events.post(Events.RECEIVED, f.name);
            notifyEvent(getString(R.string.rx_file, f.name), where, null);
            return true;
        }

        @Override
        public void onFileFailed(Session s, Session.IncomingFile f, int status) {
            cancelProgress();
        }

        @Override
        public void onClosed(Session s, Exception why) {
            sessions.remove(s);
            sessionEnded();
            cancelProgress();
        }
    }

    // ------------------------------------------------------------ התראות

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_EVENTS, getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT));
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
        return builder(CHANNEL_SERVICE)
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
        Notification n = builder(CHANNEL_EVENTS)
                .setContentTitle(getString(R.string.rx_progress, name))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        try { nm.notify(ID_PROGRESS, n); } catch (RuntimeException ignored) { }
    }

    private void cancelProgress() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_PROGRESS);
    }

    @SuppressWarnings("deprecation")
    private void notifyEvent(String title, String text, String chatAddress) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = builder(CHANNEL_EVENTS)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(openMain(chatAddress))
                .setAutoCancel(true)
                .build();
        try { nm.notify(ID_EVENT_BASE + (eventCounter++ % 50), n); } catch (RuntimeException ignored) { }
    }
}