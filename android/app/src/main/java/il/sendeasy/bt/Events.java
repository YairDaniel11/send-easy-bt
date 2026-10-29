package il.sendeasy.bt;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** אירועים פנימיים מהשירות לממשק (בלי LocalBroadcastManager, שדורש AndroidX). תמיד מגיעים בשרשור הראשי. */
final class Events {
    private Events() {}

    static final String CHAT = "chat";         // arg = כתובת המכשיר
    static final String VOICE = "voice";       // arg = כתובת המכשיר: הגיעה הודעה קולית
    static final String RECEIVED = "received"; // arg = שם הקובץ
    static final String PROGRESS = "progress"; // arg = "שם|אחוז"
    static final String STATE = "state";       // השירות התחיל/נעצר

    interface Listener {
        void onEvent(String kind, String arg);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void add(Listener l) { LISTENERS.addIfAbsent(l); }
    static void remove(Listener l) { LISTENERS.remove(l); }

    static void post(final String kind, final String arg) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (Listener l : LISTENERS) l.onEvent(kind, arg);
            }
        });
    }
}