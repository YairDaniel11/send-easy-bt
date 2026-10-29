package il.sendeasy.bt;

import java.util.UUID;

/** קבועי הפרוטוקול הפרטי. המפרט המלא ב-PROTOCOL.md בשורש המאגר. */
final class Protocol {
    private Protocol() {}

    static final UUID SERVICE_UUID = UUID.fromString("7a3e1c5b-2f4d-4b8a-9c6e-5d1f0b2a8e47");
    static final String SERVICE_NAME = "SendEasyBT";

    static final int VERSION = 1;
    static final int MAX_FRAME = 65600;     // מסגרת גדולה מזה סוגרת את החיבור
    static final int CHUNK = 16 * 1024;
    static final int MAX_TEXT_BYTES = 16 * 1024;
    static final int MAX_NAME_BYTES = 255;
    static final long MAX_FILE_SIZE = 4L * 1024 * 1024 * 1024; // 4GB

    static final int T_HELLO = 0x01;
    static final int T_PING = 0x02;
    static final int T_PONG = 0x03;
    static final int T_TEXT = 0x10;
    static final int T_ACK = 0x11;
    static final int T_READ = 0x12;
    /** הצד השני פתח שיחה חדשה (צ'אט חדש) איתנו. */
    static final int T_NEWCHAT = 0x13;
    static final int T_FILE_OFFER = 0x20;
    static final int T_FILE_ACCEPT = 0x21;
    static final int T_FILE_REJECT = 0x22;
    static final int T_FILE_CHUNK = 0x23;
    static final int T_FILE_END = 0x24;
    static final int T_FILE_DONE = 0x25;
    static final int T_CANCEL = 0x26;
    static final int T_ERROR = 0xF0;

    static final int REJECT_USER = 1;
    static final int REJECT_STORAGE = 2;
    static final int REJECT_NAME = 3;
    static final int REJECT_BUSY = 4;

    static final int DONE_OK = 0;
    static final int DONE_HASH = 1;
    static final int DONE_IO = 2;
    static final int DONE_CANCELLED = 3;

    static final long PING_INTERVAL_MS = 8000;
    static final long DEAD_AFTER_MS = 30000;
}