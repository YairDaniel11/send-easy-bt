package il.sendeasy.bt;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/** ווידג'טים למסך הבית: שליחת קובץ וצ'אט. לחיצה פותחת את המסך הראשי במצב בחירת מכשיר לפעולה. */
abstract class ActionWidget extends AppWidgetProvider {
    abstract String mode();
    abstract int labelRes();

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_action);
        v.setTextViewText(R.id.widgetLabel, c.getString(labelRes()));
        Intent i = new Intent(c, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_WIDGET_MODE, mode());
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        v.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(c, mode().hashCode(), i, flags));
        for (int id : ids) mgr.updateAppWidget(id, v);
    }

    /** ווידג'ט שליחת קובץ. */
    public static final class Send extends ActionWidget {
        @Override String mode() { return MainActivity.WIDGET_SEND; }
        @Override int labelRes() { return R.string.widget_send; }
    }

    /** ווידג'ט צ'אט. */
    public static final class Chat extends ActionWidget {
        @Override String mode() { return MainActivity.WIDGET_CHAT; }
        @Override int labelRes() { return R.string.widget_chat; }
    }
}
