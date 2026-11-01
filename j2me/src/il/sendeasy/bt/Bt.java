package il.sendeasy.bt;

import java.io.IOException;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DeviceClass;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;

/** עזרי בלוטוס (JSR-82): מכשירים מצומדים, חיפוש השירות של "שליחה קלה" ופתיחת כתובת האזנה. */
final class Bt {
    private Bt() {}

    /** ה-UUID של הפרוטוקול (ללא מקפים), כמו ב-PROTOCOL.md. */
    static final String SERVICE_UUID = "7A3E1C5B2F4D4B8A9C6E5D1F0B2A8E47";

    /** כתובת האזנה בשביל חיבורים נכנסים מהמחשב. */
    static String serverUrl() {
        return "btspp://localhost:" + SERVICE_UUID + ";name=SendEasyBT;authenticate=true;encrypt=true";
    }

    static String myName() {
        try {
            String n = LocalDevice.getLocalDevice().getFriendlyName();
            if (n != null && n.length() > 0) return n;
        } catch (BluetoothStateException e) {
        } catch (RuntimeException e) { }
        return "Nokia";
    }

    /** המכשירים המצומדים (מערך ריק אם אין). */
    static RemoteDevice[] paired() throws BluetoothStateException {
        DiscoveryAgent a = LocalDevice.getLocalDevice().getDiscoveryAgent();
        RemoteDevice[] d = a.retrieveDevices(DiscoveryAgent.PREKNOWN);
        return d == null ? new RemoteDevice[0] : d;
    }

    static String nameOf(RemoteDevice d) {
        try {
            String n = d.getFriendlyName(false);
            if (n != null && n.length() > 0) return n;
        } catch (IOException e) { }
        return d.getBluetoothAddress();
    }

    /** מחפש במכשיר את שירות "שליחה קלה" ומחזיר כתובת חיבור, או null אם לא נמצא (התוכנה לא פתוחה). */
    static String findUrl(RemoteDevice dev) throws IOException {
        final Object lock = new Object();
        final String[] url = new String[1];
        final boolean[] done = new boolean[1];
        DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
        DiscoveryListener l = new DiscoveryListener() {
            public void deviceDiscovered(RemoteDevice d, DeviceClass c) { }
            public void inquiryCompleted(int t) { }
            public void servicesDiscovered(int trans, ServiceRecord[] recs) {
                if (recs != null && recs.length > 0 && url[0] == null) {
                    url[0] = recs[0].getConnectionURL(ServiceRecord.AUTHENTICATE_ENCRYPT, false);
                }
            }
            public void serviceSearchCompleted(int trans, int resp) {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        };
        synchronized (lock) {
            agent.searchServices(null, new UUID[]{new UUID(SERVICE_UUID, false)}, dev, l);
            long end = System.currentTimeMillis() + 30000;
            while (!done[0]) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { break; }
            }
        }
        return url[0];
    }
}
