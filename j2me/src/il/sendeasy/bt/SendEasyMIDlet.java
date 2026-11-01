package il.sendeasy.bt;

import java.util.Random;

import javax.bluetooth.RemoteDevice;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * שליחה קלה - BT למכשירי נוקיה עם Java (גרסה ראשונה): בוחרים מחשב מצומד, מתחברים ומחליפים הודעות טקסט.
 * מסך "אבחון" מראה מה קרה בחיבור, כדי לדעת איפה זה נתקע במכשיר אמיתי.
 */
public class SendEasyMIDlet extends MIDlet implements CommandListener, Link.Listener {
    private final Command cSelect = new Command("בחר", Command.OK, 1);
    private final Command cSend = new Command("שלח", Command.OK, 1);
    private final Command cBack = new Command("חזרה", Command.BACK, 2);
    private final Command cDiag = new Command("אבחון", Command.SCREEN, 3);
    private final Command cRefresh = new Command("רענן", Command.SCREEN, 2);
    private final Command cExit = new Command("יציאה", Command.EXIT, 9);

    private Display display;
    private boolean started;
    private byte[] deviceId;
    private List deviceList;
    private RemoteDevice[] devices = new RemoteDevice[0];
    private Form chat, diag;
    private TextField input;
    private volatile Link link;
    private final StringBuffer log = new StringBuffer();

    protected void startApp() {
        display = Display.getDisplay(this);
        if (started) return;
        started = true;
        deviceId = loadDeviceId();
        log("התחלה, השם שלי: " + Bt.myName());
        startServer();
        showDevices();
    }

    protected void pauseApp() { }

    protected void destroyApp(boolean unconditional) {
        Link l = link;
        if (l != null) l.close(null);
    }

    // ---------------------------------------------------------------- מסכים

    private void showDevices() {
        chat = null;
        deviceList = new List("בחר מחשב", List.IMPLICIT);
        try {
            devices = Bt.paired();
            for (int i = 0; i < devices.length; i++) deviceList.append(Bt.nameOf(devices[i]), null);
            if (devices.length == 0) deviceList.append("אין מכשירים מצומדים. צמד את המחשב בהגדרות הבלוטוס", null);
            log("מכשירים מצומדים: " + devices.length);
        } catch (Exception e) {
            devices = new RemoteDevice[0];
            deviceList.append("הבלוטוס כבוי או לא זמין", null);
            log("שגיאת בלוטוס: " + e);
        }
        deviceList.setSelectCommand(cSelect);
        deviceList.addCommand(cRefresh);
        deviceList.addCommand(cDiag);
        deviceList.addCommand(cExit);
        deviceList.setCommandListener(this);
        display.setCurrent(deviceList);
    }

    private void showChat(String title) {
        chat = new Form(title);
        input = new TextField("הודעה", "", 1000, TextField.ANY);
        chat.append(input);
        chat.addCommand(cSend);
        chat.addCommand(cDiag);
        chat.addCommand(cBack);
        chat.setCommandListener(this);
        display.setCurrent(chat);
    }

    private void showDiag() {
        diag = new Form("אבחון");
        diag.append(new StringItem(null, log.toString()));
        diag.addCommand(cBack);
        diag.setCommandListener(this);
        display.setCurrent(diag);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cExit) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == cRefresh) {
            showDevices();
        } else if (c == cDiag) {
            showDiag();
        } else if (c == cBack) {
            if (d == diag && chat != null) display.setCurrent(chat);
            else showDevices();
        } else if (c == cSelect && d == deviceList) {
            int i = deviceList.getSelectedIndex();
            if (i >= 0 && i < devices.length) connect(devices[i]);
        } else if (c == cSend && d == chat) {
            String text = input.getString().trim();
            if (text.length() == 0) return;
            input.setString("");
            Link l = link;
            if (l == null || !l.sendText(text)) {
                add("⚠ לא נשלח: אין חיבור", true);
                return;
            }
            add("אני: " + text, false);
        }
    }

    // ---------------------------------------------------------------- חיבור

    /** פותח חיבור למחשב שנבחר (ברקע, כי החיפוש והחיבור לוקחים שניות). */
    private void connect(final RemoteDevice dev) {
        final String name = Bt.nameOf(dev);
        showChat(name);
        add("מתחבר אל " + name + "...", false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    log("מחפש שירות ב-" + name);
                    String url = Bt.findUrl(dev);
                    if (url == null) {
                        add("⚠ התוכנה לא נמצאה במחשב. ודא שהיא פתוחה והבלוטוס במחשב פועל", true);
                        return;
                    }
                    log("מתחבר: " + url);
                    StreamConnection sc = (StreamConnection) Connector.open(url);
                    Link l = new Link(sc, SendEasyMIDlet.this, deviceId, Bt.myName());
                    link = l;
                    l.start();
                } catch (Exception e) {
                    add("⚠ החיבור נכשל: " + e, true);
                }
            }
        }).start();
    }

    /** האזנה לחיבורים שהמחשב פותח אל הנוקיה (בשביל הודעות שנשלחות מהמחשב). */
    private void startServer() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    StreamConnectionNotifier n = (StreamConnectionNotifier) Connector.open(Bt.serverUrl());
                    log("מאזין לחיבורים מהמחשב");
                    while (true) {
                        StreamConnection sc = n.acceptAndOpen();
                        Link l = new Link(sc, SendEasyMIDlet.this, deviceId, Bt.myName());
                        if (link == null || link.isClosed()) {
                            link = l;
                            if (chat == null) showChat("שיחה");
                        }
                        l.start();
                    }
                } catch (Exception e) {
                    log("האזנה נכשלה: " + e);
                }
            }
        }).start();
    }

    public void onHello(Link l, String peerName) {
        log("מחובר אל " + peerName);
        add("✓ מחובר אל " + peerName, false);
    }

    public void onText(Link l, String text) {
        add(l.peerName + ": " + text, false);
        try { AlertType.INFO.playSound(display); } catch (RuntimeException e) { }
    }

    public void onClosed(Link l, String why) {
        if (link == l) link = null;
        if (why != null) {
            log("נסגר: " + why);
            add("⚠ " + why, true);
        }
    }

    // ---------------------------------------------------------------- עזרים

    private void add(String line, boolean error) {
        log(line);
        Form f = chat;
        if (f != null) f.append(new StringItem(null, line + "\n"));
    }

    private void log(String s) {
        if (log.length() > 3000) log.delete(0, 1500);
        log.append(s).append('\n');
    }

    /** ב-CLDC אין Random.nextBytes. */
    private static void randomize(byte[] b, long seed) {
        Random r = new Random(seed);
        for (int i = 0; i < b.length; i++) b[i] = (byte) r.nextInt();
    }

    /** מזהה קבוע של המכשיר (16 בייט), נשמר ב-RMS. */
    private byte[] loadDeviceId() {
        byte[] id = new byte[16];
        try {
            RecordStore rs = RecordStore.openRecordStore("sendeasy", true);
            try {
                if (rs.getNumRecords() > 0) return rs.getRecord(1);
                randomize(id, System.currentTimeMillis() ^ (long) hashCode());
                rs.addRecord(id, 0, id.length);
                return id;
            } finally {
                rs.closeRecordStore();
            }
        } catch (Exception e) {
            randomize(id, System.currentTimeMillis());
            return id;
        }
    }
}
