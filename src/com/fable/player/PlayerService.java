package com.fable.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/** Фоновый плеер: музыка живёт, даже когда приложение закрыто. */
public class PlayerService extends Service {

    public interface Listener {
        void onTrackChanged(Track t);
        void onPlayState(boolean playing);
    }

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "playback";
    static final String ACT_PREV = "com.fable.player.PREV";
    static final String ACT_PLAY = "com.fable.player.PLAYPAUSE";
    static final String ACT_NEXT = "com.fable.player.NEXT";
    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    private static PlayerService instance;
    public static PlayerService get() { return instance; }
    public static Listener listener;

    private MediaPlayer player;
    private final ArrayList<Track> queue = new ArrayList<>();
    private int qIndex = -1;
    public int repeatMode = REPEAT_OFF;
    public boolean shuffle = false;
    private final Random random = new Random();

    private MediaSession session;
    private Bitmap art;
    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;
    private SharedPreferences prefs;
    private long listenedMs;
    private int saveTick;
    private boolean foreground;
    private final Handler h = new Handler(Looper.getMainLooper());

    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean pausedByFocus;

    private Runnable sleepRunnable;
    private long sleepAt;             // момент остановки (мс), 0 = выкл
    private boolean sleepEndOfTrack;  // остановиться по концу текущего трека

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACT_PLAY.equals(a)) togglePlay();
            else if (ACT_NEXT.equals(a)) next(true);
            else if (ACT_PREV.equals(a)) prev();
            // Наушники отключились (BT или провод) — не «орём в поезде»
            else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(a) && isPlaying()) {
                togglePlay();
            }
        }
    };

    /** Звонок, другое приложение или ассистент перехватили звук. */
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByFocus = false;
                if (isPlaying()) togglePlay();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) { pausedByFocus = true; togglePlay(); }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (pausedByFocus) { pausedByFocus = false; if (!isPlaying()) togglePlay(); }
                break;
        }
    };

    private boolean requestFocus() {
        if (am == null) am = getSystemService(AudioManager.class);
        if (am == null) return true;
        int res;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            res = am.requestAudioFocus(focusReq);
        } else {
            res = am.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq != null) am.abandonAudioFocusRequest(focusReq);
        } else {
            am.abandonAudioFocus(focusListener);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                if (player != null && player.isPlaying()) {
                    listenedMs += 1000;
                    if (++saveTick >= 15) { saveTick = 0; saveStats(); }
                }
            } catch (IllegalStateException ignored) { }
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        SharedPreferences p = base.getSharedPreferences("fable", Context.MODE_PRIVATE);
        Locale loc = new Locale(p.getString("lang", "ru"));
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("fable", MODE_PRIVATE);
        listenedMs = prefs.getLong("listened", 0);

        session = new MediaSession(this, "fable");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (player != null && !isPlaying()) togglePlay(); }
            @Override public void onPause() { if (isPlaying()) togglePlay(); }
            @Override public void onSkipToNext() { next(true); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        session.setActive(true);

        IntentFilter f = new IntentFilter();
        f.addAction(ACT_PREV);
        f.addAction(ACT_PLAY);
        f.addAction(ACT_NEXT);
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        h.post(tick);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saveStats();
        cancelSleep();
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        abandonFocus();
        releaseFx();
        if (player != null) { player.release(); player = null; }
        if (session != null) { session.setActive(false); session.release(); session = null; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
        instance = null;
    }

    // ---------- Состояние ----------

    public Track current() {
        return (qIndex >= 0 && qIndex < queue.size()) ? queue.get(qIndex) : null;
    }

    public long playingId() {
        Track t = current();
        return t == null ? -1 : t.id;
    }

    public boolean isPlaying() {
        try { return player != null && player.isPlaying(); }
        catch (IllegalStateException e) { return false; }
    }

    public int position() {
        try { return player != null ? player.getCurrentPosition() : 0; }
        catch (IllegalStateException e) { return 0; }
    }

    public long listened() { return listenedMs; }
    private void saveStats() { prefs.edit().putLong("listened", listenedMs).apply(); }

    // ---------- Управление ----------

    public void playQueue(ArrayList<Track> q, int index) {
        queue.clear();
        queue.addAll(q);
        startTrack(index);
    }

    private void startTrack(int index) {
        if (index < 0 || index >= queue.size()) return;
        Track t = queue.get(index);
        qIndex = index;

        requestFocus();
        if (player != null) { player.release(); player = null; }
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnCompletionListener(mp -> onFinished());
        try {
            player.setDataSource(this, trackUri(t));
            player.prepare();
            player.start();
        } catch (Exception e) {
            player.release();
            player = null;
            return;
        }

        setupFx();
        applySpeed();
        prefs.edit().putInt("pc_" + t.id, prefs.getInt("pc_" + t.id, 0) + 1).apply();
        art = null;
        updateMetadata();
        updateState();
        goForeground();
        updateWidget();
        if (listener != null) {
            listener.onTrackChanged(t);
            listener.onPlayState(true);
        }
    }

    public void togglePlay() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                requestFocus();
                player.start();
                applySpeed();
            }
        } catch (IllegalStateException ignored) { }
        updateState();
        updateNotification();
        updateWidget();
        if (listener != null) listener.onPlayState(isPlaying());
    }

    public void next(boolean byUser) {
        if (queue.isEmpty()) return;
        int n;
        if (shuffle && queue.size() > 1) {
            do { n = random.nextInt(queue.size()); } while (n == qIndex);
        } else {
            n = qIndex + 1;
            if (n >= queue.size()) {
                if (repeatMode == REPEAT_ALL || byUser) n = 0;
                else { stopAtEnd(); return; }
            }
        }
        startTrack(n);
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (player != null && position() > 4000) {
            seekTo(0);
            return;
        }
        int n = qIndex - 1;
        if (n < 0) n = queue.size() - 1;
        startTrack(n);
    }

    // ---------- Таймер сна ----------

    public void setSleepTimer(long delayMs) {
        cancelSleep();
        if (delayMs <= 0) return;
        sleepAt = System.currentTimeMillis() + delayMs;
        sleepRunnable = () -> {
            if (isPlaying()) togglePlay();
            sleepAt = 0;
            sleepRunnable = null;
        };
        h.postDelayed(sleepRunnable, delayMs);
    }

    public void setSleepEndOfTrack() {
        cancelSleep();
        sleepEndOfTrack = true;
    }

    public void cancelSleep() {
        if (sleepRunnable != null) h.removeCallbacks(sleepRunnable);
        sleepRunnable = null;
        sleepAt = 0;
        sleepEndOfTrack = false;
    }

    /** 0 — выкл; -1 — «конец трека»; иначе оставшиеся миллисекунды. */
    public long sleepRemaining() {
        if (sleepEndOfTrack) return -1;
        return sleepAt == 0 ? 0 : Math.max(0, sleepAt - System.currentTimeMillis());
    }

    private void onFinished() {
        if (sleepEndOfTrack) {
            sleepEndOfTrack = false;
            stopAtEnd();
            return;
        }
        if (repeatMode == REPEAT_ONE && player != null) {
            try {
                player.seekTo(0);
                player.start();
            } catch (IllegalStateException ignored) { }
            updateState();
        } else {
            next(false);
        }
    }

    private void stopAtEnd() {
        if (player != null) {
            try {
                player.pause();
                player.seekTo(0);
            } catch (IllegalStateException ignored) { }
        }
        updateState();
        updateNotification();
        updateWidget();
        if (listener != null) listener.onPlayState(false);
    }

    public void seekTo(int ms) {
        if (player != null) {
            try { player.seekTo(ms); } catch (IllegalStateException ignored) { }
        }
        updateState();
    }

    public void setArt(Bitmap b) {
        art = b;
        updateMetadata();
        updateNotification();
        updateWidget();
    }

    public Bitmap widgetArt() { return art; }

    private void updateWidget() {
        Track t = current();
        FableWidget.refresh(this,
                t != null ? t.title : getString(R.string.app_name),
                t != null ? t.artist : getString(R.string.nothing_playing),
                art, isPlaying());
    }

    private Uri trackUri(Track t) {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id);
    }

    // ---------- Эффекты ----------

    private void setupFx() {
        releaseFx();
        if (player == null) return;
        int sid;
        try { sid = player.getAudioSessionId(); } catch (Exception e) { return; }
        try {
            eq = new Equalizer(0, sid);
            eq.setEnabled(true);
            short[] r = eq.getBandLevelRange();
            short n = eq.getNumberOfBands();
            for (short i = 0; i < n; i++) {
                float v = prefs.getFloat("eq_b" + i, 0.5f);
                eq.setBandLevel(i, (short) (r[0] + (r[1] - r[0]) * v));
            }
        } catch (Exception e) { eq = null; }
        try {
            bass = new BassBoost(0, sid);
            bass.setEnabled(true);
            bass.setStrength((short) (prefs.getFloat("eq_bass", 0f) * 1000));
        } catch (Exception e) { bass = null; }
        try {
            virt = new Virtualizer(0, sid);
            virt.setEnabled(true);
            virt.setStrength((short) (prefs.getFloat("eq_virt", 0f) * 1000));
        } catch (Exception e) { virt = null; }
    }

    private void releaseFx() {
        if (eq != null) { try { eq.release(); } catch (Exception ignored) { } eq = null; }
        if (bass != null) { try { bass.release(); } catch (Exception ignored) { } bass = null; }
        if (virt != null) { try { virt.release(); } catch (Exception ignored) { } virt = null; }
    }

    public void refreshFx() {
        setupFx();
        applySpeed();
    }

    public void setBandLevel(short band, float v) {
        if (eq == null) return;
        try {
            short[] r = eq.getBandLevelRange();
            eq.setBandLevel(band, (short) (r[0] + (r[1] - r[0]) * v));
        } catch (Exception ignored) { }
    }

    public void setBassStrength(float v) {
        if (bass != null) { try { bass.setStrength((short) (v * 1000)); } catch (Exception ignored) { } }
    }

    public void setVirtStrength(float v) {
        if (virt != null) { try { virt.setStrength((short) (v * 1000)); } catch (Exception ignored) { } }
    }

    public int[] bandFreqs() {
        if (eq == null) return null;
        try {
            short n = eq.getNumberOfBands();
            int[] f = new int[n];
            for (short i = 0; i < n; i++) f[i] = eq.getCenterFreq(i) / 1000;
            return f;
        } catch (Exception e) { return null; }
    }

    public void applySpeed() {
        if (player == null) return;
        float sp = prefs.getFloat("speed", 1f);
        try {
            if (player.isPlaying()) {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(sp));
            }
        } catch (Exception ignored) { }
    }

    // ---------- Сессия и уведомление ----------

    private void updateMetadata() {
        Track t = current();
        if (session == null || t == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.duration);
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
    }

    private void updateState() {
        if (session == null) return;
        boolean playing = isPlaying();
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), playing ? 1f : 0f)
                .build());
    }

    private PendingIntent pi(String action, int req) {
        Intent i = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Track t = current();
        boolean playing = isPlaying();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        nb.setSmallIcon(R.drawable.ic_play)
                .setContentTitle(t != null ? t.title : "Fable Player")
                .setContentText(t != null ? t.artist : "")
                .setContentIntent(PendingIntent.getActivity(this, 10,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_prev),
                        getString(R.string.btn_prev), pi(ACT_PREV, 11)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        getString(R.string.btn_play), pi(ACT_PLAY, 12)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next),
                        getString(R.string.btn_next), pi(ACT_NEXT, 13)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) nb.setLargeIcon(art);
        return nb.build();
    }

    private void goForeground() {
        try {
            startForeground(NOTIF_ID, buildNotification());
            foreground = true;
        } catch (Exception ignored) { }
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        try { nm.notify(NOTIF_ID, buildNotification()); } catch (Exception ignored) { }
    }
}
