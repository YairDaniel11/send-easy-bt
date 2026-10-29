package il.sendeasy.bt;

import android.content.Context;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** הקלטת הודעה קולית: AAC בתוך MPEG-4 (נתמך מאנדרואיד 4.x, ומתנגן גם ב-Windows). */
final class VoiceRecorder {
    private MediaRecorder rec;
    private File file;
    private long startedAt;

    boolean isRecording() { return rec != null; }

    long elapsedMs() { return rec == null ? 0 : System.currentTimeMillis() - startedAt; }

    /** מתחיל להקליט. מחזיר false אם המיקרופון לא זמין. */
    boolean start(Context c) {
        if (rec != null) return true;
        File dir = Storage.voiceDir(c);
        file = new File(dir, "recording.tmp");
        MediaRecorder r = new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(16000);
            r.setAudioEncodingBitRate(32000);
            r.setMaxDuration(5 * 60 * 1000);
            r.setOutputFile(file.getAbsolutePath());
            r.prepare();
            r.start();
        } catch (IOException e) {
            try { r.release(); } catch (RuntimeException ignored) { }
            return false;
        } catch (RuntimeException e) {
            try { r.release(); } catch (RuntimeException ignored) { }
            return false;
        }
        rec = r;
        startedAt = System.currentTimeMillis();
        return true;
    }

    /** עוצר ושומר. מחזיר את הקובץ הסופי (עם השם התקני), או null אם ההקלטה קצרה מדי או נכשלה. */
    File stop(Context c) {
        MediaRecorder r = rec;
        rec = null;
        if (r == null) return null;
        long ms = System.currentTimeMillis() - startedAt;
        boolean ok = true;
        try { r.stop(); } catch (RuntimeException e) { ok = false; } // עצירה מהירה מדי זורקת חריגה
        try { r.release(); } catch (RuntimeException ignored) { }
        if (!ok || ms < 600 || file == null || !file.exists() || file.length() == 0) {
            if (file != null) file.delete();
            return null;
        }
        int secs = (int) Math.max(1, Math.round(ms / 1000.0));
        String name = "voice-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-d" + secs + ".m4a";
        File dest = Storage.uniqueFile(Storage.voiceDir(c), name);
        if (!file.renameTo(dest)) { file.delete(); return null; }
        return dest;
    }

    /** מבטל ומוחק. */
    void cancel() {
        MediaRecorder r = rec;
        rec = null;
        if (r != null) {
            try { r.stop(); } catch (RuntimeException ignored) { }
            try { r.release(); } catch (RuntimeException ignored) { }
        }
        if (file != null) file.delete();
    }
}