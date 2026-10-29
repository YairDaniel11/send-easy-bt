package il.sendeasy.bt;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * הקלטת הודעה קולית עם השהיה והמשך. מ-API 18: AudioRecord (PCM 16 ביט מונו 16kHz) ->
 * MediaCodec (AAC-LC, 32kbps) -> MediaMuxer (MPEG-4), כלומר m4a רגיל שמתנגן גם ב-Windows.
 * השהיה = מפסיקים להזין את הקודק (בלי stop), וחותמות הזמן נקבעות לפי כמות המדגמים שהוזנה בפועל,
 * ולכן אין פער בקובץ, והמשך ההקלטה הוא בלי השהיות. מתחת ל-API 18: MediaRecorder רגיל בלי השהיה.
 */
final class VoiceRecorder {
    private static final int RATE = 16000;
    private static final int PCM_CHUNK = 2048; // 1024 מדגמים = פריים AAC אחד

    // מצב משותף
    private volatile boolean active;
    private volatile boolean paused;
    private volatile boolean stopping;
    private volatile boolean cancelled;
    private volatile boolean failed;
    private volatile long samples; // מדגמים שהוזנו בפועל
    private File file;
    private Thread worker;

    // נתיב ישן (API < 18)
    private MediaRecorder legacy;
    private long legacyStart;

    // משאבים שהשרשור העובד מחזיק (נוצרים ב-start ומשוחררים ב-worker)
    private AudioRecord ar;
    private MediaCodec codec;
    private MediaMuxer muxer;

    boolean isRecording() { return active; }

    boolean isPaused() { return paused; }

    boolean canPause() { return legacy == null; }

    /** true אם ההקלטה נכשלה באמצע (למשל המיקרופון נלקח). */
    boolean hasFailed() { return failed; }

    /** זמן הקלטה בפועל, בלי השהיות. */
    long elapsedMs() {
        if (legacy != null) return System.currentTimeMillis() - legacyStart;
        return samples * 1000L / RATE;
    }

    void pause() { if (active && legacy == null) paused = true; }

    void resume() { if (active && legacy == null) paused = false; }

    /** מתחיל להקליט. מחזיר false אם המיקרופון או הקודק לא זמינים. */
    boolean start(Context c) {
        if (active) return true;
        file = new File(Storage.voiceDir(c), "recording.tmp");
        file.delete();
        paused = false; stopping = false; cancelled = false; failed = false; samples = 0;
        if (Build.VERSION.SDK_INT < 18) return startLegacy();
        if (!setupPipeline()) {
            releaseAll();
            file.delete();
            return false;
        }
        active = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { work(); }
        }, "SE-Rec");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean setupPipeline() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return false;
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, PCM_CHUNK * 4));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) return false;

            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 1);
            fmt.setInteger("aac-profile", MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK * 2);
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return false;
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) { // כולל IllegalArgumentException, IllegalStateException, SecurityException
            return false;
        }
    }

    private void releaseAll() {
        AudioRecord a = ar; ar = null;
        if (a != null) {
            try { a.stop(); } catch (RuntimeException ignored) { }
            try { a.release(); } catch (RuntimeException ignored) { }
        }
        MediaCodec k = codec; codec = null;
        if (k != null) {
            try { k.stop(); } catch (RuntimeException ignored) { }
            try { k.release(); } catch (RuntimeException ignored) { }
        }
        MediaMuxer m = muxer; muxer = null;
        if (m != null) {
            try { m.release(); } catch (RuntimeException ignored) { }
        }
    }

    private long ptsUs() { return samples * 1000000L / RATE; }

    @SuppressWarnings("deprecation")
    private void work() {
        boolean muxStarted = false;
        try {
            AudioRecord a = ar;
            MediaCodec k = codec;
            MediaMuxer m = muxer;
            ByteBuffer[] inB = k.getInputBuffers();
            ByteBuffer[] outB = k.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcm = new byte[PCM_CHUNK];
            boolean arRunning = true;
            boolean inputDone = false;
            boolean done = false;
            int track = -1;
            while (!done && !cancelled) {
                boolean pausedNow = paused;
                if (!inputDone) {
                    if (stopping) {
                        int idx = k.dequeueInputBuffer(10000);
                        if (idx >= 0) {
                            k.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    } else if (pausedNow) {
                        if (arRunning) { try { a.stop(); } catch (RuntimeException ignored) { } arRunning = false; }
                        try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                    } else {
                        if (!arRunning) {
                            a.startRecording();
                            if (a.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("mic");
                            arRunning = true;
                        }
                        int idx = k.dequeueInputBuffer(10000);
                        if (idx >= 0) {
                            ByteBuffer ib = inB[idx];
                            ib.clear();
                            int want = Math.min(pcm.length, ib.capacity());
                            int n = a.read(pcm, 0, want);
                            if (n < 0) throw new IllegalStateException("read " + n);
                            if (n > 0) ib.put(pcm, 0, n);
                            long pts = ptsUs();
                            samples += n / 2;
                            k.queueInputBuffer(idx, 0, n, pts, 0);
                        }
                    }
                }
                // ריקון הפלט
                while (true) {
                    int oi = k.dequeueOutputBuffer(info, inputDone ? 10000 : 0);
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxStarted) {
                            track = m.addTrack(k.getOutputFormat());
                            m.start();
                            muxStarted = true;
                        }
                    } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outB = k.getOutputBuffers();
                    } else if (oi >= 0) {
                        ByteBuffer ob = outB[oi];
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0 && muxStarted) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            m.writeSampleData(track, ob, info);
                        }
                        k.releaseOutputBuffer(oi, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { done = true; break; }
                    }
                }
            }
            if (!done && !cancelled) failed = true;
        } catch (RuntimeException e) {
            failed = true;
        } finally {
            MediaMuxer m = muxer;
            if (m != null && muxStarted) {
                try { m.stop(); } catch (RuntimeException e) { failed = true; }
            }
            releaseAll();
        }
    }

    // ------------------------------------------------------------ סיום

    private void finishWorker() {
        Thread t = worker;
        worker = null;
        if (t != null) {
            try { t.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** עוצר ושומר. מחזיר את הקובץ הסופי (עם השם התקני), או null אם ההקלטה קצרה מדי או נכשלה. */
    File stop(Context c) {
        if (!active) return null;
        long ms;
        boolean ok;
        if (legacy != null) {
            MediaRecorder r = legacy;
            legacy = null;
            active = false;
            ms = System.currentTimeMillis() - legacyStart;
            ok = true;
            try { r.stop(); } catch (RuntimeException e) { ok = false; }
            try { r.release(); } catch (RuntimeException ignored) { }
        } else {
            ms = elapsedMs();
            stopping = true;
            paused = false;
            finishWorker();
            active = false;
            ok = !failed;
        }
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
        if (legacy != null) {
            MediaRecorder r = legacy;
            legacy = null;
            try { r.stop(); } catch (RuntimeException ignored) { }
            try { r.release(); } catch (RuntimeException ignored) { }
        } else if (worker != null) {
            cancelled = true;
            finishWorker();
        }
        active = false;
        if (file != null) file.delete();
    }

    // ------------------------------------------------------------ API ישן

    private boolean startLegacy() {
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
        legacy = r;
        legacyStart = System.currentTimeMillis();
        active = true;
        return true;
    }
}
