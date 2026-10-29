package il.sendeasy.bt;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;

/** שם מכשיר להצגה: כינוי מקומי אם הוגדר, אחרת השם האמיתי. הכינוי נשמר רק במכשיר הזה. */
final class DeviceNames {
    private DeviceNames() {}

    static String display(Context c, BluetoothDevice d) {
        return resolve(c, d.getAddress(), MainActivity.nameOf(d));
    }

    static String resolve(Context c, String address, String realName) {
        String a = Prefs.alias(c, address);
        return a != null ? a : realName;
    }

    /** דיאלוג שינוי שם. onDone רץ אחרי שמירה או איפוס. */
    static void rename(final Activity act, final String address, String realName, final Runnable onDone) {
        final boolean hasAlias = Prefs.alias(act, address) != null;
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(resolve(act, address, realName));
        et.setSelection(et.getText().length());
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(R.string.rename_title)
                .setView(et)
                .setPositiveButton(R.string.rename_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Prefs.setAlias(act, address, et.getText().toString());
                        if (onDone != null) onDone.run();
                    }
                })
                .setNegativeButton(R.string.act_cancel, null);
        if (hasAlias) {
            b.setNeutralButton(R.string.rename_reset, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Prefs.setAlias(act, address, "");
                    if (onDone != null) onDone.run();
                }
            });
        }
        AlertDialog dlg = b.create();
        dlg.show();
        et.requestFocus();
    }
}
