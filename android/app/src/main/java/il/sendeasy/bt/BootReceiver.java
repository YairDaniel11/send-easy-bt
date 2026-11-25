package il.sendeasy.bt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** מפעיל את שירות הקבלה אחרי הדלקת המכשיר ואחרי עדכון האפליקציה, כדי שהמחשב ימשיך לזהות את הטלפון בלי לפתוח את האפליקציה. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (Prefs.receiveEnabled(c)) BtService.start(c);
    }
}
