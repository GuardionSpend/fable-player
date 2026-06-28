package com.fable.player;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements PlayerService.Listener {

    private static final int REQ_PERM = 1;
    private static final int REQ_PICK_COVER = 2;
    private static final int REQ_PICK_PL_COVER = 3;
    private static final int REQ_DELETE = 4;
    private static final int REQ_BACKUP_SAVE = 5;
    private static final int REQ_RESTORE_OPEN = 6;
    private static final int NOTIF_BACKUP = 2;
    private static final String CH_BACKUP = "backup";
    private static final int BG_BOTTOM = 0xFF0E0E14;

    private static class Playlist {
        long id;
        String name;
        final ArrayList<Long> ids = new ArrayList<>();
    }

    // Библиотека
    private final ArrayList<Track> allTracks = new ArrayList<>();
    private final ArrayList<Track> shownTracks = new ArrayList<>();
    private long playingId = -1;
    private final ArrayList<Playlist> playlists = new ArrayList<>();
    private Playlist activePlaylist; // null = вся музыка
    private boolean favMode = false;
    private Set<String> favs = new HashSet<>();

    private TrackAdapter adapter;
    private int repeatMode = PlayerService.REPEAT_OFF;
    private boolean shuffle = false;
    private boolean userSeeking = false;
    private boolean expanded = false;
    private boolean drawerOpen = false;
    private boolean bgToggle = false;
    private boolean onboardOpen = false;
    private long pendingCoverId = -1;
    private long pendingPlCoverId = -1;
    private long pendingDeleteId = -1;
    private boolean backupWithAudio = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final LruCache<Long, Bitmap> thumbCache = new LruCache<>(120);
    private final ArgbEvaluator argb = new ArgbEvaluator();

    private SharedPreferences prefs;
    private String lang;

    // Тема
    private int accent, curDark;
    private int defaultAccent, textColor, dimColor;
    private ValueAnimator themeAnim;
    private GradientDrawable mainBg;
    private GradientDrawable npBg;
    private float blurAmt = 0f;
    private ValueAnimator blurAnim;
    private boolean powerSave;
    private int sortMode;       // 0 — система, 1 — название, 2 — дата
    private boolean frameless;  // тема: false=классика, true=безрамочная

    // Вьюхи
    private View rootFrame, mainScreen, nowPlaying, miniBar, miniProgress;
    private View drawerLayer, drawer, drawerDim;
    private LinearLayout playlistContainer;
    private TextView headerTitle, listLabel, statTime, statFav, statCount;
    private EditText searchBox;
    private ImageView npCover, miniCover, bgA, bgB, npGlow;
    private ImageButton btnFav, btnLyrics;
    private View lyricsPanel;
    private TextView lyricsText;
    private boolean lyricsOpen;
    private ParticleView particles;
    private TextView npTitle, npArtist, miniTitle, miniArtist, timeNow, timeTotal;
    private SeekBar seek;
    private ImageButton btnPlay, btnPrev, btnNext, btnRepeat, btnShuffle, miniPlay, miniNext;
    private ListView list;
    private TextView empty;

    // Свайп
    private float swX, swY;
    private boolean swTracking;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            PlayerService s = svc();
            if (s != null && s.current() != null) {
                int pos = s.position();
                if (!userSeeking) {
                    seek.setProgress(pos, true);
                    timeNow.setText(formatTime(pos));
                }
                updateMiniProgress(pos, seek.getMax());
            }
            ui.postDelayed(this, 300);
        }
    };

    private PlayerService svc() { return PlayerService.get(); }

    @Override
    protected void attachBaseContext(Context base) {
        SharedPreferences p = base.getSharedPreferences("fable", Context.MODE_PRIVATE);
        Locale loc = new Locale(p.getString("lang", "ru"));
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    // ---------- Создание ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("fable", MODE_PRIVATE);
        powerSave = prefs.getBoolean("powersave", false);
        lang = prefs.getString("lang", "ru");
        sortMode = prefs.getInt("sort", 0);
        frameless = prefs.getBoolean("frameless", false);
        favs = new HashSet<>(prefs.getStringSet("favs", new HashSet<>()));

        defaultAccent = getColor(R.color.accent);
        textColor = getColor(R.color.text);
        dimColor = getColor(R.color.text_dim);
        accent = defaultAccent;
        curDark = blend(defaultAccent, BG_BOTTOM, 0.84f);

        bindViews();
        setupList();
        setupControls();
        setupDrawer();
        squareCover();
        loadPlaylists();

        mainBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{curDark, BG_BOTTOM});
        rootFrame.setBackground(mainBg);
        // Фон экрана плеера — чёткий вертикальный градиент в цвет обложки
        npBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{curDark, BG_BOTTOM});
        nowPlaying.setBackground(npBg);
        applyFramelessTheme();
        applyThemeColors(accent, curDark);

        startService(new Intent(this, PlayerService.class));
        PlayerService.listener = this;
        ui.post(progressTick);

        if (!prefs.getBoolean("setup_done", false)) showOnboarding();
        requestStartPermissions();
        syncFromService();
    }

    private void requestStartPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (!hasPermission()) need.add(permissionName());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (hasPermission()) { loadTracks(); tryResume(); }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    /** Если ничего не играет — восстановить последний трек на сохранённой позиции (на паузе). */
    private void tryResume() {
        PlayerService s = svc();
        if (s == null || s.current() != null) return;
        long rid = prefs.getLong("resume_id", -1);
        String rfile = prefs.getString("resume_file", "");
        int rpos = prefs.getInt("resume_pos", 0);
        if (rid < 0 && rfile.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < allTracks.size(); i++) {
            Track t = allTracks.get(i);
            if (t.id == rid || (!rfile.isEmpty() && rfile.equals(t.file))) { idx = i; break; }
        }
        if (idx < 0) return;
        s.prepareResume(new ArrayList<>(allTracks), idx, rpos);
    }

    /** После пересоздания (смена языка) подхватываем играющий трек из сервиса. */
    private void syncFromService() {
        PlayerService s = svc();
        if (s == null) return;
        repeatMode = s.repeatMode;
        shuffle = s.shuffle;
        updateToggleButtons();
        Track t = s.current();
        if (t != null) {
            playingId = t.id;
            npTitle.setText(t.title);
            npArtist.setText(t.artist);
            miniTitle.setText(t.title);
            miniArtist.setText(t.artist);
            seek.setMax((int) t.duration);
            timeTotal.setText(formatTime((int) t.duration));
            miniBar.setVisibility(View.VISIBLE);
            setPlayingState(s.isPlaying());
            updateFavButton();
            loadCoverAndTheme(t);
        }
    }

    private void bindViews() {
        rootFrame = findViewById(R.id.root_frame);
        mainScreen = findViewById(R.id.main_screen);
        nowPlaying = findViewById(R.id.now_playing);
        miniBar = findViewById(R.id.mini);
        miniProgress = findViewById(R.id.mini_progress);
        drawerLayer = findViewById(R.id.drawer_layer);
        drawer = findViewById(R.id.drawer);
        drawerDim = findViewById(R.id.drawer_dim);
        playlistContainer = findViewById(R.id.playlist_container);
        headerTitle = findViewById(R.id.header_title);
        listLabel = findViewById(R.id.list_label);
        statTime = findViewById(R.id.stat_time);
        statFav = findViewById(R.id.stat_fav);
        statCount = findViewById(R.id.stat_count);
        searchBox = findViewById(R.id.search);
        npCover = findViewById(R.id.np_cover);
        npGlow = findViewById(R.id.np_glow);
        lyricsPanel = findViewById(R.id.lyrics_panel);
        lyricsText = findViewById(R.id.lyrics_text);
        miniCover = findViewById(R.id.mini_cover);
        bgA = findViewById(R.id.bg_a);
        bgB = findViewById(R.id.bg_b);
        btnFav = findViewById(R.id.btn_fav);
        btnLyrics = findViewById(R.id.btn_lyrics);
        particles = findViewById(R.id.particles);
        npTitle = findViewById(R.id.np_title);
        npArtist = findViewById(R.id.np_artist);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        timeNow = findViewById(R.id.time_now);
        timeTotal = findViewById(R.id.time_total);
        seek = findViewById(R.id.seek);
        btnPlay = findViewById(R.id.btn_play);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnShuffle = findViewById(R.id.btn_shuffle);
        miniPlay = findViewById(R.id.mini_play);
        miniNext = findViewById(R.id.mini_next);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);

        npTitle.setSelected(true);
        miniTitle.setSelected(true);
        roundCorners(npCover, dp(22));
        roundCorners(miniCover, dp(10));
    }

    private void setupList() {
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((p, v, pos, id) -> playFromShown(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showTrackMenu(v, shownTracks.get(pos));
            return true;
        });
        if (!powerSave) setupListAnimation();

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshShown(false); }
        });
    }

    private void setupListAnimation() {
        AnimationSet in = new AnimationSet(true);
        in.setInterpolator(new DecelerateInterpolator(2f));
        in.setDuration(380);
        in.addAnimation(new AlphaAnimation(0f, 1f));
        in.addAnimation(new TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_SELF, 0f, TranslateAnimation.RELATIVE_TO_SELF, 0f,
                TranslateAnimation.RELATIVE_TO_SELF, 0.35f, TranslateAnimation.RELATIVE_TO_SELF, 0f));
        list.setLayoutAnimation(new LayoutAnimationController(in, 0.045f));
    }

    private void setupControls() {
        miniBar.setOnClickListener(v -> expandPlayer());
        findViewById(R.id.btn_collapse).setOnClickListener(v -> collapsePlayer());
        miniPlay.setOnClickListener(v -> togglePlay());
        miniNext.setOnClickListener(v -> { PlayerService s = svc(); if (s != null) s.next(true); });
        btnPlay.setOnClickListener(v -> togglePlay());
        btnNext.setOnClickListener(v -> { PlayerService s = svc(); if (s != null) s.next(true); });
        btnPrev.setOnClickListener(v -> { PlayerService s = svc(); if (s != null) s.prev(); });
        npCover.setOnClickListener(v -> togglePlay());
        btnRepeat.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            PlayerService s = svc();
            if (s != null) s.repeatMode = repeatMode;
            pulse(btnRepeat);
            updateToggleButtons();
        });
        btnShuffle.setOnClickListener(v -> {
            shuffle = !shuffle;
            PlayerService s = svc();
            if (s != null) s.shuffle = shuffle;
            pulse(btnShuffle);
            updateToggleButtons();
        });
        btnFav.setOnClickListener(v -> {
            if (playingId >= 0) {
                toggleFav(playingId);
                pulse(btnFav);
            }
        });

        btnLyrics.setOnClickListener(v -> {
            Track t = currentTrack();
            if (t == null) return;
            pulse(btnLyrics);
            if (lyricsOpen) closeLyrics();
            else openLyrics(t);
        });
        View.OnClickListener editLyrics = v -> {
            Track t = currentTrack();
            if (t != null) showLyricsDialog(t);
        };
        lyricsPanel.setOnClickListener(editLyrics);
        lyricsText.setOnClickListener(editLyrics);

        findViewById(R.id.btn_menu).setOnClickListener(this::showMainMenu);
        findViewById(R.id.btn_eq).setOnClickListener(v -> showEqDialog());
        findViewById(R.id.btn_np_menu).setOnClickListener(v -> {
            Track t = currentTrack();
            if (t != null) showTrackMenu(v, t);
            else showMainMenu(v);
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) timeNow.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                PlayerService sv = svc();
                if (sv != null) sv.seekTo(s.getProgress());
            }
        });
    }

    private void setupDrawer() {
        drawerDim.setOnClickListener(v -> closeDrawer());
        findViewById(R.id.btn_new_playlist).setOnClickListener(v ->
                showNameDialog(getString(R.string.new_playlist), null, name -> {
                    createPlaylist(name);
                    buildDrawer();
                }));
        findViewById(R.id.credit_dev).setOnClickListener(v ->
                openUrl("https://github.com/GuardionSpend"));
        findViewById(R.id.credit_contrib).setOnClickListener(v ->
                openUrl("https://github.com/NickIBrody"));
    }

    private Track currentTrack() {
        PlayerService s = svc();
        return s != null ? s.current() : null;
    }

    private void togglePlay() {
        PlayerService s = svc();
        if (s == null) return;
        if (s.current() == null) {
            if (!shownTracks.isEmpty()) playFromShown(0);
            return;
        }
        s.togglePlay();
    }

    private void playFromShown(int pos) {
        PlayerService s = svc();
        if (s == null || pos < 0 || pos >= shownTracks.size()) return;
        s.repeatMode = repeatMode;
        s.shuffle = shuffle;
        s.playQueue(new ArrayList<>(shownTracks), pos);
    }

    // ---------- Колбэки сервиса ----------

    @Override
    public void onTrackChanged(Track t) {
        playingId = t.id;
        npTitle.setText(t.title);
        npArtist.setText(t.artist);
        miniTitle.setText(t.title);
        miniArtist.setText(t.artist);
        seek.setMax((int) t.duration);
        seek.setProgress(0);
        timeNow.setText("0:00");
        timeTotal.setText(formatTime((int) t.duration));
        if (!expanded) showMiniBar();
        if (lyricsOpen) {  // показать текст нового трека, не закрывая панель
            String text = readLyrics(t.id);
            lyricsText.setText(text.isEmpty() ? getString(R.string.lyrics_empty) : text);
            lyricsText.setTextColor(text.isEmpty() ? dimColor : textColor);
        }
        updateFavButton();
        adapter.notifyDataSetChanged();
        loadCoverAndTheme(t);
    }

    @Override
    public void onPlayState(boolean playing) {
        setPlayingState(playing);
    }

    // ---------- Избранное ----------

    private boolean isFav(long id) {
        return favs.contains(String.valueOf(id));
    }

    private void toggleFav(long id) {
        String k = String.valueOf(id);
        if (!favs.remove(k)) favs.add(k);
        prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        updateFavButton();
        if (favMode) refreshShown(false);
    }

    private void updateFavButton() {
        boolean f = playingId >= 0 && isFav(playingId);
        btnFav.setImageResource(f ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
        btnFav.setImageTintList(ColorStateList.valueOf(f ? accent : textColor));
    }

    // ---------- Текст песни ----------

    private File lyricsFile(long id) {
        return new File(getFilesDir(), "lyrics/" + id + ".txt");
    }

    private String readLyrics(long id) {
        File f = lyricsFile(id);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private void openLyrics(Track t) {
        if (lyricsOpen) return;
        lyricsOpen = true;
        String text = readLyrics(t.id);
        if (text.isEmpty()) {
            lyricsText.setText(R.string.lyrics_empty);
            lyricsText.setTextColor(dimColor);
        } else {
            lyricsText.setText(text);
            lyricsText.setTextColor(textColor);
        }
        lyricsPanel.setVisibility(View.VISIBLE);
        int w = lyricsPanel.getWidth() > 0 ? lyricsPanel.getWidth() : rootFrame.getWidth();
        lyricsPanel.setTranslationX(w);
        lyricsPanel.animate().translationX(0)
                .setDuration(340)
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .start();
        btnLyrics.setImageTintList(ColorStateList.valueOf(accent));
    }

    private void closeLyrics() {
        if (!lyricsOpen) return;
        lyricsOpen = false;
        int w = lyricsPanel.getWidth() > 0 ? lyricsPanel.getWidth() : rootFrame.getWidth();
        lyricsPanel.animate().translationX(w)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(() -> lyricsPanel.setVisibility(View.GONE))
                .start();
        btnLyrics.setImageTintList(ColorStateList.valueOf(textColor));
    }

    private void showLyricsDialog(final Track t) {
        final String existing = readLyrics(t.id);

        final EditText et = new EditText(this);
        et.setHint(R.string.lyrics_hint);
        et.setText(existing);
        et.setTextColor(textColor);
        et.setHintTextColor(dimColor);
        et.setTextSize(14.5f);
        et.setGravity(Gravity.TOP);
        et.setBackgroundResource(R.drawable.bg_search);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        et.setLineSpacing(dp(3), 1f);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(et);
        final int maxH = getResources().getDisplayMetrics().heightPixels * 50 / 100;
        sc.post(() -> {
            if (sc.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = sc.getLayoutParams();
                lp.height = maxH;
                sc.setLayoutParams(lp);
            }
        });

        showStyledDialog(getString(R.string.lyrics), sc,
                getString(R.string.save), () -> {
                    String text = et.getText().toString().trim();
                    File f = lyricsFile(t.id);
                    File dir = f.getParentFile();
                    if (dir != null) dir.mkdirs();
                    try {
                        if (text.isEmpty()) {
                            f.delete();
                        } else {
                            try (FileOutputStream out = new FileOutputStream(f)) {
                                out.write(text.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        Toast.makeText(this, R.string.lyrics_saved, Toast.LENGTH_SHORT).show();
                        if (lyricsOpen) {
                            if (text.isEmpty()) {
                                lyricsText.setText(R.string.lyrics_empty);
                                lyricsText.setTextColor(dimColor);
                            } else {
                                lyricsText.setText(text);
                                lyricsText.setTextColor(textColor);
                            }
                        }
                    } catch (Exception ignored) { }
                }, getString(R.string.close), null);
    }

    /** Большая обложка всегда квадратная и вписана в доступное место. */
    private void squareCover() {
        final View frame = findViewById(R.id.cover_frame);
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // в безрамочной обложка крупнее (края растворяются, запас не нужен)
            int pad = frameless ? dp(0) : dp(16);
            int size = Math.min(r - l - pad, b - t - pad);
            if (size <= 0) return;
            setSquare(npCover, size);
            setSquare(npGlow, size);
        });
    }

    private void setSquare(View v, int size) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != size) {
            lp.width = size;
            lp.height = size;
            v.setLayoutParams(lp);
        }
    }

    // ---------- Приветственный экран ----------

    private void showOnboarding() {
        onboardOpen = true;
        final View ob = findViewById(R.id.onboard);
        final TextView hi = findViewById(R.id.ob_hi);
        final View card = findViewById(R.id.ob_card);
        final TextView ru = findViewById(R.id.ob_lang_ru);
        final TextView en = findViewById(R.id.ob_lang_en);
        final Switch ps = findViewById(R.id.ob_ps);
        final TextView start = findViewById(R.id.ob_start);
        ob.setVisibility(View.VISIBLE);

        // привет на разных языках, как при первом включении телефона
        final String[] words = {"Привет", "Hello", "Hola", "Bonjour", "Ciao", "Olá", "Hallo", "こんにちは"};
        final int[] idx = {0};
        final Runnable[] cyc = new Runnable[1];
        cyc[0] = () -> {
            hi.animate().alpha(0f).setDuration(450).withEndAction(() -> {
                idx[0] = (idx[0] + 1) % words.length;
                hi.setText(words[idx[0]]);
                hi.animate().alpha(1f).setDuration(450).start();
            }).start();
            ui.postDelayed(cyc[0], 2300);
        };
        ui.postDelayed(cyc[0], 1700);

        card.setAlpha(0f);
        card.setTranslationY(dp(90));
        card.animate().alpha(1f).translationY(0)
                .setDuration(750).setStartDelay(900)
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .start();

        final String[] chosen = {lang};
        styleChip(ru, chosen[0].equals("ru"));
        styleChip(en, chosen[0].equals("en"));
        ru.setOnClickListener(v -> { chosen[0] = "ru"; styleChip(ru, true); styleChip(en, false); });
        en.setOnClickListener(v -> { chosen[0] = "en"; styleChip(ru, false); styleChip(en, true); });
        ps.setChecked(powerSave);
        ps.setThumbTintList(ColorStateList.valueOf(accent));

        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(accent);
        start.setBackground(g);

        start.setOnClickListener(v -> {
            ui.removeCallbacks(cyc[0]);
            prefs.edit().putBoolean("setup_done", true)
                    .putString("lang", chosen[0]).apply();
            setPowerSave(ps.isChecked());
            if (!chosen[0].equals(lang)) {
                recreate();
            } else {
                onboardOpen = false;
                ob.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f)
                        .setDuration(450)
                        .withEndAction(() -> ob.setVisibility(View.GONE))
                        .start();
            }
        });
    }

    private void styleChip(TextView c, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(on ? accent : 0x16FFFFFF);
        c.setBackground(g);
        c.setTextColor(on ? BG_BOTTOM : textColor);
    }

    // ---------- Размытие переходов (Android 12+) ----------

    private void blurMain(float to) {
        if (Build.VERSION.SDK_INT < 31 || powerSave) return;
        if (blurAnim != null) blurAnim.cancel();
        blurAnim = ValueAnimator.ofFloat(blurAmt, to);
        blurAnim.setDuration(400);
        blurAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        blurAnim.addUpdateListener(a -> {
            blurAmt = (float) a.getAnimatedValue();
            float r = 26f * blurAmt;
            mainScreen.setRenderEffect(r < 0.5f ? null
                    : RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        });
        blurAnim.start();
    }

    // ---------- Красивые меню и диалоги ----------

    private TextView menuRow(String title, int color) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(color);
        tv.setTextSize(14.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(18), dp(13), dp(18), dp(13));
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        tv.setBackgroundResource(out.resourceId);
        return tv;
    }

    private void showMenuPopup(View anchor, Object[][] items) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_menu);
        box.setPadding(0, dp(8), 0, dp(8));
        box.setClipToOutline(true);

        int width = dp(236);
        final PopupWindow pw = new PopupWindow(box, width,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(18));

        for (Object[] it : items) {
            boolean accentItem = it.length > 2 && Boolean.TRUE.equals(it[2]);
            TextView tv = menuRow((String) it[0], accentItem ? accent : textColor);
            final Runnable action = (Runnable) it[1];
            tv.setOnClickListener(v -> {
                pw.dismiss();
                action.run();
            });
            box.addView(tv);
        }

        box.setScaleX(0.88f);
        box.setScaleY(0.88f);
        box.setAlpha(0f);
        box.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();

        // Если под якорем не хватает места — открываем меню вверх, а не за экран
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int menuH = box.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int xoff = anchor.getWidth() - width;
        if (loc[1] + anchor.getHeight() + menuH < screenH - dp(24)) {
            pw.showAsDropDown(anchor, xoff, -dp(6));
        } else {
            pw.showAsDropDown(anchor, xoff, -(menuH + anchor.getHeight() + dp(6)));
        }
    }

    private TextView dialogButton(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(14.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true);
        tv.setBackgroundResource(out.resourceId);
        return tv;
    }

    private Dialog showStyledDialog(String title, View content,
                                    String posText, Runnable onPos,
                                    String negText, Runnable onNeg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_menu);
        box.setPadding(dp(22), dp(20), dp(22), dp(12));

        TextView tt = new TextView(this);
        tt.setText(title);
        tt.setTextColor(textColor);
        tt.setTextSize(17f);
        tt.setTypeface(null, Typeface.BOLD);
        box.addView(tt);

        if (content != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            box.addView(content, lp);
        }

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        TextView neg = dialogButton(negText != null ? negText : getString(R.string.cancel), dimColor);
        neg.setOnClickListener(v -> {
            d.dismiss();
            if (onNeg != null) onNeg.run();
        });
        row.addView(neg);
        if (posText != null) {
            TextView pos = dialogButton(posText, accent);
            pos.setOnClickListener(v -> {
                d.dismiss();
                if (onPos != null) onPos.run();
            });
            row.addView(pos);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        box.addView(row, rlp);

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(318), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        box.setScaleX(0.92f);
        box.setScaleY(0.92f);
        box.setAlpha(0f);
        d.setOnShowListener(di -> box.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .start());
        d.show();
        return d;
    }

    // ---------- Свайп вправо: боковое меню ----------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!onboardOpen) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swX = ev.getX();
                    swY = ev.getY();
                    swTracking = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (swTracking) {
                        float dx = ev.getX() - swX;
                        float dy = ev.getY() - swY;
                        if (Math.abs(dy) > dp(48) && Math.abs(dy) > Math.abs(dx)) {
                            swTracking = false;
                        } else if (lyricsOpen
                                && dx > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            swTracking = false;
                            closeLyrics();
                        } else if (!expanded && !drawerOpen && !lyricsOpen
                                && dx > dp(72) && Math.abs(dx) > Math.abs(dy) * 1.7f) {
                            swTracking = false;
                            openDrawer();
                        } else if (drawerOpen
                                && dx < -dp(72) && Math.abs(dx) > Math.abs(dy) * 1.7f) {
                            swTracking = false;
                            closeDrawer();
                        }
                    }
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        buildDrawer();
        drawerLayer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-dp(300));
        drawer.animate().translationX(0)
                .setDuration(330)
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .start();
        drawerDim.animate().alpha(0.6f).setDuration(330).start();
        blurMain(0.6f);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().translationX(-dp(300))
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(() -> drawerLayer.setVisibility(View.GONE))
                .start();
        drawerDim.animate().alpha(0f).setDuration(280).start();
        if (!expanded) blurMain(0f);
    }

    private void buildDrawer() {
        PlayerService s = svc();
        long listened = s != null ? s.listened() : prefs.getLong("listened", 0);
        statTime.setText(formatListened(listened));
        statFav.setText(favoriteTitle());
        statCount.setText(allTracks.size() + " " + plural(allTracks.size()));

        playlistContainer.removeAllViews();
        addLibraryRow(getString(R.string.all_music), allTracks.size(),
                !favMode && activePlaylist == null, null, () -> {
                    favMode = false;
                    activePlaylist = null;
                    refreshShown(true);
                    closeDrawer();
                });
        addLibraryRow(getString(R.string.favorites), favs.size(),
                favMode, "fav", () -> {
                    favMode = true;
                    activePlaylist = null;
                    refreshShown(true);
                    closeDrawer();
                });
        for (Playlist p : playlists) addPlaylistRow(p);
    }

    private void addLibraryRow(String title, int count, boolean active,
                               String icon, Runnable onClick) {
        View row = getLayoutInflater().inflate(R.layout.item_playlist, playlistContainer, false);
        TextView name = row.findViewById(R.id.pl_name);
        TextView cnt = row.findViewById(R.id.pl_count);
        ImageView img = row.findViewById(R.id.pl_cover);
        roundCorners(img, dp(10));
        name.setText(title);
        name.setTextColor(active ? accent : textColor);
        name.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        cnt.setText(count + " " + plural(count));
        if ("fav".equals(icon)) {
            img.setImageResource(R.drawable.ic_heart_fill);
            img.setImageTintList(ColorStateList.valueOf(accent));
            img.setPadding(dp(10), dp(10), dp(10), dp(10));
            img.setBackgroundColor(0x14FFFFFF);
        }
        row.setOnClickListener(v -> onClick.run());
        playlistContainer.addView(row);
    }

    private void addPlaylistRow(final Playlist p) {
        View row = getLayoutInflater().inflate(R.layout.item_playlist, playlistContainer, false);
        TextView name = row.findViewById(R.id.pl_name);
        TextView count = row.findViewById(R.id.pl_count);
        ImageView img = row.findViewById(R.id.pl_cover);
        roundCorners(img, dp(10));

        boolean isActive = !favMode && activePlaylist != null && p.id == activePlaylist.id;
        name.setText(p.name);
        name.setTextColor(isActive ? accent : textColor);
        name.setTypeface(null, isActive ? Typeface.BOLD : Typeface.NORMAL);
        count.setText(p.ids.size() + " " + plural(p.ids.size()));

        File cf = playlistCoverFile(p.id);
        if (cf.exists()) {
            Bitmap bmp = decodeFileScaled(cf, 96);
            if (bmp != null) img.setImageBitmap(bmp);
        }
        row.setOnLongClickListener(v -> {
            showPlaylistMenu(v, p);
            return true;
        });
        row.setOnClickListener(v -> {
            favMode = false;
            activePlaylist = p;
            refreshShown(true);
            closeDrawer();
        });
        playlistContainer.addView(row);
    }

    private void showPlaylistMenu(View anchor, final Playlist p) {
        showMenuPopup(anchor, new Object[][]{
                {getString(R.string.rename), (Runnable) () ->
                        showNameDialog(getString(R.string.rename), p.name, name -> {
                            p.name = name;
                            savePlaylists();
                            buildDrawer();
                            if (activePlaylist == p) refreshShown(false);
                        })},
                {getString(R.string.pick_cover), (Runnable) () -> {
                    pendingPlCoverId = p.id;
                    pickImage(REQ_PICK_PL_COVER);
                }},
                {getString(R.string.delete), (Runnable) () -> {
                    playlists.remove(p);
                    playlistCoverFile(p.id).delete();
                    savePlaylists();
                    if (activePlaylist == p) {
                        activePlaylist = null;
                        refreshShown(true);
                    }
                    buildDrawer();
                }},
        });
    }

    // ---------- Плейлисты: данные ----------

    private File playlistsFile() {
        return new File(getFilesDir(), "playlists.json");
    }

    private File playlistCoverFile(long id) {
        return new File(getFilesDir(), "covers/pl_" + id + ".jpg");
    }

    private Playlist createPlaylist(String name) {
        Playlist p = new Playlist();
        p.id = System.currentTimeMillis();
        p.name = name;
        playlists.add(p);
        savePlaylists();
        return p;
    }

    private void loadPlaylists() {
        playlists.clear();
        File f = playlistsFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            JSONArray arr = new JSONArray(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Playlist p = new Playlist();
                p.id = o.getLong("id");
                p.name = o.getString("name");
                JSONArray ids = o.getJSONArray("ids");
                for (int j = 0; j < ids.length(); j++) p.ids.add(ids.getLong(j));
                playlists.add(p);
            }
        } catch (Exception ignored) { }
    }

    private void savePlaylists() {
        try {
            JSONArray arr = new JSONArray();
            for (Playlist p : playlists) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                JSONArray ids = new JSONArray();
                for (Long id : p.ids) ids.put((long) id);
                o.put("ids", ids);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(playlistsFile())) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    private void showNameDialog(String title, String initial, NameCallback cb) {
        final EditText et = new EditText(this);
        et.setHint(R.string.playlist_name_hint);
        et.setSingleLine(true);
        et.setTextColor(textColor);
        et.setHintTextColor(dimColor);
        et.setTextSize(15f);
        et.setBackgroundResource(R.drawable.bg_search);
        et.setPadding(dp(16), dp(11), dp(16), dp(11));
        if (initial != null) {
            et.setText(initial);
            et.setSelection(initial.length());
        }
        showStyledDialog(title, et, getString(R.string.done), () -> {
            String name = et.getText().toString().trim();
            if (!name.isEmpty()) cb.onName(name);
        }, null, null);
        et.requestFocus();
    }

    private interface NameCallback { void onName(String name); }

    private void addToPlaylistDialog(final Track t) {
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] ref = new Dialog[1];
        for (final Playlist p : playlists) {
            TextView row = menuRow(p.name, textColor);
            row.setOnClickListener(v -> {
                ref[0].dismiss();
                addTrackTo(p, t);
            });
            listBox.addView(row);
        }
        TextView create = menuRow("+  " + getString(R.string.new_playlist), accent);
        create.setOnClickListener(v -> {
            ref[0].dismiss();
            showNameDialog(getString(R.string.new_playlist), null, name ->
                    addTrackTo(createPlaylist(name), t));
        });
        listBox.addView(create);
        ref[0] = showStyledDialog(getString(R.string.add_to_playlist), listBox, null, null, null, null);
    }

    private void addTrackTo(Playlist p, Track t) {
        if (!p.ids.contains(t.id)) {
            p.ids.add(t.id);
            savePlaylists();
        }
        Toast.makeText(this, getString(R.string.toast_added) + " · " + p.name,
                Toast.LENGTH_SHORT).show();
        if (activePlaylist == p) refreshShown(false);
    }

    // ---------- Меню ----------

    private void showMainMenu(View anchor) {
        showMenuPopup(anchor, new Object[][]{
                {getString(R.string.menu_refresh), (Runnable) () -> {
                    if (hasPermission()) {
                        loadTracks();
                        list.scheduleLayoutAnimation();
                    }
                }},
                {getString(R.string.new_playlist), (Runnable) () ->
                        showNameDialog(getString(R.string.new_playlist), null, name -> {
                            createPlaylist(name);
                            Toast.makeText(this, getString(R.string.toast_added) + " · " + name,
                                    Toast.LENGTH_SHORT).show();
                        })},
                {getString(R.string.eq_title), (Runnable) this::showEqDialog},
                {getString(R.string.sleep_timer), (Runnable) this::showSleepDialog},
                {getString(R.string.settings), (Runnable) this::showSettingsDialog},
                {getString(R.string.menu_author), (Runnable) this::openTelegram, true},
        });
    }

    private void showTrackMenu(View anchor, final Track t) {
        ArrayList<Object[]> items = new ArrayList<>();
        items.add(new Object[]{getString(isFav(t.id) ? R.string.fav_remove : R.string.fav_add),
                (Runnable) () -> toggleFav(t.id)});
        items.add(new Object[]{getString(R.string.menu_set_cover), (Runnable) () -> {
            pendingCoverId = t.id;
            pickImage(REQ_PICK_COVER);
        }});
        if (customCoverFile(t.id).exists()) {
            items.add(new Object[]{getString(R.string.menu_remove_cover),
                    (Runnable) () -> removeCustomCover(t.id)});
        }
        items.add(new Object[]{getString(R.string.add_to_playlist),
                (Runnable) () -> addToPlaylistDialog(t)});
        if (activePlaylist != null && activePlaylist.ids.contains(t.id)) {
            items.add(new Object[]{getString(R.string.remove_from_playlist), (Runnable) () -> {
                activePlaylist.ids.remove(t.id);
                savePlaylists();
                refreshShown(false);
            }});
        }
        items.add(new Object[]{getString(R.string.delete_device),
                (Runnable) () -> confirmDelete(t)});
        showMenuPopup(anchor, items.toArray(new Object[0][]));
    }

    // ---------- Удаление трека с устройства ----------

    private void confirmDelete(final Track t) {
        showStyledDialog(getString(R.string.delete_q), null,
                getString(R.string.delete_device), () -> deleteTrack(t),
                getString(R.string.cancel), null);
    }

    private void deleteTrack(Track t) {
        Uri uri = trackUri(t);
        if (Build.VERSION.SDK_INT >= 30) {
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(uri);
            try {
                android.app.PendingIntent pi =
                        MediaStore.createDeleteRequest(getContentResolver(), uris);
                pendingDeleteId = t.id;
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE,
                        null, 0, 0, 0);
            } catch (Exception e) {
                pendingDeleteId = -1;
            }
        } else {
            try {
                int n = getContentResolver().delete(uri, null, null);
                if (n > 0) finalizeDelete(t.id);
            } catch (Exception ignored) { }
        }
    }

    private void finalizeDelete(long id) {
        for (int i = allTracks.size() - 1; i >= 0; i--) {
            if (allTracks.get(i).id == id) { allTracks.remove(i); break; }
        }
        for (Playlist p : playlists) p.ids.remove(id);
        savePlaylists();
        favs.remove(String.valueOf(id));
        prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        thumbCache.remove(id);
        customCoverFile(id).delete();
        refreshShown(false);
        Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show();
    }

    private void openTelegram() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=GuardionSpend")));
        } catch (Exception e) {
            openUrl("https://t.me/GuardionSpend");
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }

    // ---------- Свои обложки ----------

    private File customCoverFile(long id) {
        return new File(getFilesDir(), "covers/" + id + ".jpg");
    }

    private void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.pick_cover)), req);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_DELETE) {
            if (res == RESULT_OK && pendingDeleteId >= 0) finalizeDelete(pendingDeleteId);
            pendingDeleteId = -1;
            return;
        }

        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (req == REQ_BACKUP_SAVE) {
            doBackup(uri, backupWithAudio);
            return;
        }
        if (req == REQ_RESTORE_OPEN) {
            doRestore(uri);
            return;
        }

        if (req == REQ_PICK_COVER && pendingCoverId >= 0) {
            final long id = pendingCoverId;
            pendingCoverId = -1;
            exec.execute(() -> {
                boolean ok = saveImageTo(customCoverFile(id), uri);
                ui.post(() -> {
                    if (!ok) return;
                    thumbCache.remove(id);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, R.string.toast_cover_set, Toast.LENGTH_SHORT).show();
                    Track cur = currentTrack();
                    if (cur != null && cur.id == id) loadCoverAndTheme(cur);
                });
            });
        } else if (req == REQ_PICK_PL_COVER && pendingPlCoverId >= 0) {
            final long id = pendingPlCoverId;
            pendingPlCoverId = -1;
            exec.execute(() -> {
                boolean ok = saveImageTo(playlistCoverFile(id), uri);
                ui.post(() -> {
                    if (!ok) return;
                    Toast.makeText(this, R.string.toast_cover_set, Toast.LENGTH_SHORT).show();
                    if (drawerOpen) buildDrawer();
                });
            });
        }
    }

    private boolean saveImageTo(File f, Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            byte[] bytes = buf.toByteArray();

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (bmp == null) return false;
            if (Math.max(bmp.getWidth(), bmp.getHeight()) > 1280) {
                float k = 1280f / Math.max(bmp.getWidth(), bmp.getHeight());
                bmp = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, (int) (bmp.getWidth() * k)),
                        Math.max(1, (int) (bmp.getHeight() * k)), true);
            }
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void removeCustomCover(long id) {
        customCoverFile(id).delete();
        thumbCache.remove(id);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, R.string.toast_cover_removed, Toast.LENGTH_SHORT).show();
        Track cur = currentTrack();
        if (cur != null && cur.id == id) loadCoverAndTheme(cur);
    }

    // ---------- Разрешения и библиотека ----------

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        for (int i = 0; i < perms.length; i++) {
            if (perms[i].equals(permissionName())) {
                if (results[i] == PackageManager.PERMISSION_GRANTED) {
                    loadTracks();
                    tryResume();
                } else {
                    empty.setText(R.string.no_permission);
                    empty.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private String permissionName() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadTracks() {
        allTracks.clear();
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME
        };
        // Порядок выборки: 1 — название, 2 — дата добавления (новые сверху), 0 — как в системе
        String order;
        if (sortMode == 1) order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";
        else if (sortMode == 2) order = MediaStore.Audio.Media.DATE_ADDED + " DESC";
        else order = null;
        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Audio.Media.IS_MUSIC + "!=0", null, order)) {
            if (c != null) {
                while (c.moveToNext()) {
                    Track t = new Track();
                    t.id = c.getLong(0);
                    t.title = c.getString(1);
                    t.artist = c.getString(2);
                    if (t.artist == null || t.artist.equals("<unknown>")) {
                        t.artist = getString(R.string.unknown_artist);
                    }
                    t.duration = c.getLong(3);
                    t.file = c.getString(4);
                    allTracks.add(t);
                }
            }
        }
        refreshShown(false);
    }

    private void refreshShown(boolean animate) {
        shownTracks.clear();
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.getDefault());
        ArrayList<Track> base;
        if (favMode) {
            base = new ArrayList<>();
            for (Track t : allTracks) {
                if (isFav(t.id)) base.add(t);
            }
        } else if (activePlaylist == null) {
            base = allTracks;
        } else {
            base = new ArrayList<>();
            for (Long id : activePlaylist.ids) {
                for (Track t : allTracks) {
                    if (t.id == id) { base.add(t); break; }
                }
            }
        }
        for (Track t : base) {
            if (q.isEmpty()
                    || t.title.toLowerCase(Locale.getDefault()).contains(q)
                    || t.artist.toLowerCase(Locale.getDefault()).contains(q)) {
                shownTracks.add(t);
            }
        }

        listLabel.setText(favMode ? getString(R.string.favorites)
                : activePlaylist == null ? getString(R.string.my_music) : activePlaylist.name);
        if (!q.isEmpty()) empty.setText(R.string.no_results);
        else if (favMode) empty.setText(R.string.empty_favs);
        else if (activePlaylist != null) empty.setText(R.string.empty_playlist);
        else empty.setText(R.string.no_tracks);

        adapter.notifyDataSetChanged();
        if (animate) list.scheduleLayoutAnimation();
    }

    private Uri trackUri(Track t) {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id);
    }

    // ---------- Экран «Сейчас играет» ----------

    private void expandPlayer() {
        if (expanded) return;
        expanded = true;
        if (drawerOpen) closeDrawer();
        nowPlaying.setVisibility(View.VISIBLE);
        nowPlaying.setTranslationY(rootFrame.getHeight());
        nowPlaying.animate().translationY(0)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .withEndAction(() -> npTitle.setSelected(true))
                .start();
        mainScreen.animate().alpha(0.4f).scaleX(0.96f).scaleY(0.96f).setDuration(420).start();
        blurMain(1f);
        PlayerService s = svc();
        particles.setRunning(!powerSave && s != null && s.isPlaying());
        miniBar.animate().translationY(dp(140)).alpha(0f)
                .setDuration(260)
                .withEndAction(() -> miniBar.setVisibility(View.INVISIBLE))
                .start();
    }

    private void collapsePlayer() {
        if (!expanded) return;
        if (lyricsOpen) { lyricsOpen = false; lyricsPanel.setVisibility(View.GONE);
            btnLyrics.setImageTintList(ColorStateList.valueOf(textColor)); }
        expanded = false;
        nowPlaying.animate().translationY(rootFrame.getHeight())
                .setDuration(360)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(() -> nowPlaying.setVisibility(View.INVISIBLE))
                .start();
        mainScreen.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).start();
        blurMain(0f);
        particles.setRunning(false);
        if (playingId >= 0) {
            miniBar.setVisibility(View.VISIBLE);
            miniBar.animate().translationY(0).alpha(1f).setDuration(320).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (onboardOpen) return;
        if (lyricsOpen) {
            closeLyrics();
        } else if (drawerOpen) {
            closeDrawer();
        } else if (expanded) {
            collapsePlayer();
        } else {
            showStyledDialog(getString(R.string.exit_q), null,
                    getString(R.string.exit_yes), () -> {
                        stopService(new Intent(this, PlayerService.class));
                        finish();
                    },
                    getString(R.string.exit_no), null);
        }
    }

    private void showMiniBar() {
        if (miniBar.getVisibility() != View.VISIBLE) {
            miniBar.setVisibility(View.VISIBLE);
            miniBar.setAlpha(1f);
            miniBar.setTranslationY(dp(120));
            miniBar.animate().translationY(0)
                    .setDuration(450)
                    .setInterpolator(new OvershootInterpolator(0.9f))
                    .start();
        }
    }

    /** Иконки play/pause с «пружинкой», обложка дышит, огоньки включаются. */
    private void setPlayingState(boolean playing) {
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        miniPlay.setImageResource(icon);
        btnPlay.setImageResource(icon);
        btnPlay.setScaleX(0.75f);
        btnPlay.setScaleY(0.75f);
        btnPlay.animate().scaleX(1f).scaleY(1f)
                .setDuration(320)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .start();
        float coverScale = playing ? 1f : 0.93f;
        npCover.animate().scaleX(coverScale).scaleY(coverScale)
                .setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
        if (expanded) particles.setRunning(playing && !powerSave);
    }

    private void updateMiniProgress(int pos, int max) {
        View parent = (View) miniProgress.getParent();
        if (parent == null || max <= 0) return;
        ViewGroup.LayoutParams lp = miniProgress.getLayoutParams();
        lp.width = (int) (parent.getWidth() * (pos / (float) max));
        miniProgress.setLayoutParams(lp);
    }

    // ---------- Обложка, фон и адаптивная тема ----------

    private void loadCoverAndTheme(Track t) {
        final long id = t.id;
        exec.execute(() -> {
            final Bitmap art = loadArt(t, 1024);
            final int color = art != null ? extractAccent(art) : defaultAccent;
            final Bitmap displayArt = (frameless && art != null) ? featherBitmap(art) : art;
            ui.post(() -> {
                if (playingId != id) return;
                swapCover(displayArt, art);
                updateGlow(art, color);
                animateTheme(color);
                PlayerService s = svc();
                if (s != null) s.setArt(art);
            });
        });
    }

    /** Применяет внешний вид выбранной темы (классика / безрамочная). */
    private void applyFramelessTheme() {
        if (frameless) {
            npCover.setClipToOutline(false);
            npCover.setElevation(0f);
            btnPlay.setBackground(null);
            btnPlay.setElevation(0f);
        } else {
            roundCorners(npCover, dp(22));
            npCover.setElevation(dp(18));
            btnPlay.setBackgroundResource(R.drawable.bg_play_button);
            btnPlay.setElevation(dp(8));
            npGlow.setVisibility(View.GONE);
        }
    }

    /** Обложка с мягко растворёнными (размытыми) краями — для безрамочной темы. */
    private Bitmap featherBitmap(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, 0, 0, null);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        float cx = w / 2f, cy = h / 2f;
        // прозрачность достигается ДО самого края (0.9r) — у сторон остаётся запас,
        // поэтому на границе картинки не видно тонкой линии
        float r = Math.min(w, h) / 2f;
        p.setShader(new RadialGradient(cx, cy, r,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 0.9f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return out;
    }

    /** Неоновое свечение вокруг кнопки play (мягкое радиальное пятно, без чёткого круга). */
    private void setupPlayGlow(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(46));
        int mid = (color & 0x00FFFFFF) | 0x99000000;  // accent, полупрозрачный
        g.setColors(new int[]{mid, color & 0x00FFFFFF});
        btnPlay.setBackground(g);
    }

    /** Неоновое свечение под обложкой для безрамочной темы. */
    private void updateGlow(Bitmap art, int color) {
        if (!frameless || art == null) {
            npGlow.setVisibility(View.GONE);
            return;
        }
        npGlow.setVisibility(View.VISIBLE);
        // края растворяем маской (работает на любой версии Android, не только с блюром)
        npGlow.setImageBitmap(featherBitmap(art));
        npGlow.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        npGlow.setScaleX(1.12f);
        npGlow.setScaleY(1.12f);
        if (Build.VERSION.SDK_INT >= 31) {
            npGlow.setRenderEffect(RenderEffect.createBlurEffect(
                    dp(24), dp(24), Shader.TileMode.DECAL));
        }
        // мягкое неяркое свечение, чтобы не перебивало обложку
        npGlow.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> npGlow.animate().alpha(0.4f).setDuration(500).start())
                .start();
    }

    private void swapCover(Bitmap npImg, Bitmap miniImg) {
        npCover.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                .setDuration(150)
                .withEndAction(() -> {
                    if (npImg != null) {
                        npCover.setImageBitmap(npImg);
                        miniCover.setImageBitmap(miniImg);
                    } else {
                        npCover.setImageResource(R.drawable.ic_track_placeholder);
                        miniCover.setImageResource(R.drawable.ic_track_placeholder);
                    }
                    PlayerService s = svc();
                    boolean playing = s != null && s.isPlaying();
                    float sc = playing ? 1f : 0.93f;
                    npCover.animate().alpha(1f).scaleX(sc).scaleY(sc)
                            .setDuration(420)
                            .setInterpolator(new OvershootInterpolator(1.1f))
                            .start();
                })
                .start();
    }

    private Bitmap decodeFileScaled(File f, int maxSize) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / sample > maxSize * 2 || o.outHeight / sample > maxSize * 2) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private Bitmap loadArt(Track t, int maxSize) {
        File custom = customCoverFile(t.id);
        if (custom.exists()) {
            Bitmap b = decodeFileScaled(custom, maxSize);
            if (b != null) return b;
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, trackUri(t));
            byte[] data = mmr.getEmbeddedPicture();
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / sample > maxSize * 2 || o.outHeight / sample > maxSize * 2) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    private int extractAccent(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 24, 24, true);
        float[] hsv = new float[3];
        int buckets = 12;
        float[] score = new float[buckets];
        float[] sumH = new float[buckets], sumS = new float[buckets], sumV = new float[buckets];
        int[] count = new int[buckets];
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                Color.colorToHSV(b.getPixel(x, y), hsv);
                float s = hsv[1], v = hsv[2];
                if (s < 0.15f || v < 0.12f || v > 0.97f) continue;
                int k = (int) (hsv[0] / 360f * buckets) % buckets;
                float w = s * (1f - Math.abs(v - 0.6f));
                score[k] += w;
                sumH[k] += hsv[0]; sumS[k] += s; sumV[k] += v;
                count[k]++;
            }
        }
        if (b != src) b.recycle();
        int best = -1;
        for (int i = 0; i < buckets; i++) {
            if (count[i] > 0 && (best < 0 || score[i] > score[best])) best = i;
        }
        if (best < 0) return defaultAccent;
        hsv[0] = sumH[best] / count[best];
        hsv[1] = Math.max(0.45f, Math.min(0.85f, sumS[best] / count[best]));
        hsv[2] = Math.max(0.62f, Math.min(0.9f, sumV[best] / count[best]));
        return Color.HSVToColor(hsv);
    }

    private void animateTheme(int toAccent) {
        if (powerSave) {
            applyThemeColors(toAccent, blend(toAccent, BG_BOTTOM, 0.80f));
            adapter.notifyDataSetChanged();
            return;
        }
        final int fa = accent, fd = curDark;
        final int ta = toAccent, td = blend(toAccent, BG_BOTTOM, 0.80f);
        if (themeAnim != null) themeAnim.cancel();
        themeAnim = ValueAnimator.ofFloat(0f, 1f);
        themeAnim.setDuration(750);
        themeAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        themeAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            applyThemeColors(
                    (int) argb.evaluate(f, fa, ta),
                    (int) argb.evaluate(f, fd, td));
        });
        themeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                adapter.notifyDataSetChanged();
            }
        });
        themeAnim.start();
    }

    private void applyThemeColors(int acc, int dark) {
        accent = acc;
        curDark = dark;

        mainBg.setColors(new int[]{dark, BG_BOTTOM});
        // Насыщенный градиент экрана плеера под цвет обложки (цвет держится дольше → тёмный низ)
        if (npBg != null) {
            npBg.setColors(new int[]{
                    blend(acc, BG_BOTTOM, 0.12f),
                    blend(acc, BG_BOTTOM, 0.46f),
                    BG_BOTTOM});
        }
        getWindow().setStatusBarColor(dark);
        getWindow().setNavigationBarColor(BG_BOTTOM);

        ColorStateList accentTint = ColorStateList.valueOf(acc);
        if (frameless) {
            btnPlay.setBackgroundTintList(null);
            setupPlayGlow(acc);
            btnPlay.setImageTintList(ColorStateList.valueOf(textColor));
        } else {
            btnPlay.setBackgroundTintList(accentTint);
            btnPlay.setImageTintList(ColorStateList.valueOf(BG_BOTTOM));
        }
        if (npGlow != null && frameless && npGlow.getVisibility() == View.VISIBLE) {
            npGlow.setColorFilter(acc, PorterDuff.Mode.SRC_ATOP);
        }
        seek.setProgressTintList(accentTint);
        seek.setThumbTintList(accentTint);
        miniProgress.setBackgroundColor(acc);
        btnPrev.setImageTintList(ColorStateList.valueOf(textColor));
        btnNext.setImageTintList(ColorStateList.valueOf(textColor));
        miniPlay.setImageTintList(ColorStateList.valueOf(textColor));
        miniNext.setImageTintList(ColorStateList.valueOf(textColor));
        particles.setColorTint(acc);

        String text = headerTitle.getText().toString();
        headerTitle.getPaint().setShader(new LinearGradient(
                0, 0, Math.max(1f, headerTitle.getPaint().measureText(text)), 0,
                acc, textColor, Shader.TileMode.CLAMP));
        headerTitle.invalidate();

        updateToggleButtons();
        updateFavButton();
    }

    private void updateToggleButtons() {
        btnRepeat.setImageResource(repeatMode == PlayerService.REPEAT_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        btnRepeat.setImageTintList(ColorStateList.valueOf(
                repeatMode != PlayerService.REPEAT_OFF ? accent : dimColor));
        btnShuffle.setImageTintList(ColorStateList.valueOf(shuffle ? accent : dimColor));
    }

    private void pulse(View v) {
        v.setScaleX(0.7f);
        v.setScaleY(0.7f);
        v.animate().scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator(2.5f))
                .start();
    }

    private static int blend(int c1, int c2, float ratio) {
        float r = 1f - ratio;
        return Color.rgb(
                (int) (Color.red(c1) * r + Color.red(c2) * ratio),
                (int) (Color.green(c1) * r + Color.green(c2) * ratio),
                (int) (Color.blue(c1) * r + Color.blue(c2) * ratio));
    }

    // ---------- Эквалайзер, скорость, настройки ----------

    private interface FloatSetter { void set(float v); }

    private View fxRow(String label, float init, final FloatSetter cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(dimColor);
        l.setTextSize(12.5f);
        l.setWidth(dp(70));
        row.addView(l);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(init * 100));
        sb.setProgressTintList(ColorStateList.valueOf(accent));
        sb.setThumbTintList(ColorStateList.valueOf(accent));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) cb.set(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(sb, lp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(2);
        row.setLayoutParams(rlp);
        return row;
    }

    private String freqLabel(int hz) {
        return hz < 1000 ? hz + " " + (lang.equals("en") ? "Hz" : "Гц")
                : String.format(Locale.US, "%.1f %s", hz / 1000f,
                        lang.equals("en") ? "kHz" : "кГц").replace(".0 ", " ");
    }

    private void showEqDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final PlayerService s = svc();

        int[] freqs = s != null ? s.bandFreqs() : null;
        if (freqs == null) freqs = new int[]{60, 230, 910, 3600, 14000};
        int nBands = Math.min(freqs.length, 8);
        for (int i = 0; i < nBands; i++) {
            final short band = (short) i;
            content.addView(fxRow(freqLabel(freqs[i]),
                    prefs.getFloat("eq_b" + i, 0.5f), v -> {
                        prefs.edit().putFloat("eq_b" + band, v).apply();
                        if (s != null) s.setBandLevel(band, v);
                    }));
        }
        content.addView(fxRow(getString(R.string.bass),
                prefs.getFloat("eq_bass", 0f), v -> {
                    prefs.edit().putFloat("eq_bass", v).apply();
                    if (s != null) s.setBassStrength(v);
                }));
        content.addView(fxRow(getString(R.string.surround),
                prefs.getFloat("eq_virt", 0f), v -> {
                    prefs.edit().putFloat("eq_virt", v).apply();
                    if (s != null) s.setVirtStrength(v);
                }));

        LinearLayout spHead = new LinearLayout(this);
        spHead.setOrientation(LinearLayout.HORIZONTAL);
        TextView spLabel = new TextView(this);
        spLabel.setText(getString(R.string.speed));
        spLabel.setTextColor(dimColor);
        spLabel.setTextSize(12.5f);
        final TextView spVal = new TextView(this);
        float spInit = prefs.getFloat("speed", 1f);
        spVal.setText(String.format(Locale.US, "  %.2fx", spInit));
        spVal.setTextColor(accent);
        spVal.setTextSize(12.5f);
        spVal.setTypeface(null, Typeface.BOLD);
        spHead.addView(spLabel);
        spHead.addView(spVal);
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shlp.topMargin = dp(10);
        content.addView(spHead, shlp);
        SeekBar spBar = new SeekBar(this);
        spBar.setMax(30);
        spBar.setProgress(Math.round((spInit - 0.5f) / 0.05f));
        spBar.setProgressTintList(ColorStateList.valueOf(accent));
        spBar.setThumbTintList(ColorStateList.valueOf(accent));
        spBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float sp = 0.5f + p * 0.05f;
                spVal.setText(String.format(Locale.US, "  %.2fx", sp));
                prefs.edit().putFloat("speed", sp).apply();
                if (s != null) s.applySpeed();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        content.addView(spBar);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(content);
        final int maxH = getResources().getDisplayMetrics().heightPixels * 55 / 100;
        sc.post(() -> {
            if (sc.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = sc.getLayoutParams();
                lp.height = maxH;
                sc.setLayoutParams(lp);
            }
        });

        showStyledDialog(getString(R.string.eq_title), sc,
                getString(R.string.done), null,
                getString(R.string.reset), () -> {
                    SharedPreferences.Editor e = prefs.edit();
                    for (int i = 0; i < 8; i++) e.putFloat("eq_b" + i, 0.5f);
                    e.putFloat("eq_bass", 0f).putFloat("eq_virt", 0f)
                            .putFloat("speed", 1f).apply();
                    if (s != null) s.refreshFx();
                });
    }

    private void showSettingsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout psRow = new LinearLayout(this);
        psRow.setOrientation(LinearLayout.HORIZONTAL);
        psRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout psText = new LinearLayout(this);
        psText.setOrientation(LinearLayout.VERTICAL);
        TextView psTitle = new TextView(this);
        psTitle.setText(R.string.power_save);
        psTitle.setTextColor(textColor);
        psTitle.setTextSize(15f);
        TextView psSub = new TextView(this);
        psSub.setText(R.string.power_save_sub);
        psSub.setTextColor(dimColor);
        psSub.setTextSize(11.5f);
        psText.addView(psTitle);
        psText.addView(psSub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        psRow.addView(psText, tlp);
        Switch sw = new Switch(this);
        sw.setChecked(powerSave);
        sw.setThumbTintList(ColorStateList.valueOf(accent));
        sw.setTrackTintList(ColorStateList.valueOf(dimColor));
        sw.setOnCheckedChangeListener((b, checked) -> setPowerSave(checked));
        psRow.addView(sw);
        content.addView(psRow);

        // язык
        TextView langLabel = new TextView(this);
        langLabel.setText(R.string.language);
        langLabel.setTextColor(dimColor);
        langLabel.setTextSize(11.5f);
        langLabel.setAllCaps(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(18);
        content.addView(langLabel, llp);
        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView ru = new TextView(this);
        final TextView en = new TextView(this);
        for (TextView c : new TextView[]{ru, en}) {
            c.setTextSize(14f);
            c.setTypeface(null, Typeface.BOLD);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(10), 0, dp(10));
        }
        ru.setText(R.string.lang_ru);
        en.setText(R.string.lang_en);
        styleChip(ru, lang.equals("ru"));
        styleChip(en, lang.equals("en"));
        View.OnClickListener pick = v -> {
            String chosen = v == ru ? "ru" : "en";
            if (!chosen.equals(lang)) {
                prefs.edit().putString("lang", chosen).apply();
                recreate();
            }
        };
        ru.setOnClickListener(pick);
        en.setOnClickListener(pick);
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c1.rightMargin = dp(8);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        langRow.addView(ru, c1);
        langRow.addView(en, c2);
        LinearLayout.LayoutParams lrl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrl.topMargin = dp(8);
        content.addView(langRow, lrl);

        final Dialog[] dref = new Dialog[1];

        PlayerService s0 = svc();
        long rem = s0 != null ? s0.sleepRemaining() : 0;
        String sleepState = rem == -1 ? getString(R.string.sleep_end)
                : rem > 0 ? formatListened((rem / 60000 + 1) * 60000)
                : getString(R.string.sleep_off);
        String[] sortNames = {getString(R.string.sort_off),
                getString(R.string.sort_title), getString(R.string.sort_date)};
        content.addView(settingButton(getString(R.string.sort), sortNames[sortMode], () -> {
            if (dref[0] != null) dref[0].dismiss();
            showSortDialog();
        }), settingLp());

        content.addView(settingButton(getString(R.string.theme),
                getString(frameless ? R.string.theme_frameless : R.string.theme_classic), () -> {
            if (dref[0] != null) dref[0].dismiss();
            showThemeDialog();
        }), settingLp());

        content.addView(settingButton(getString(R.string.sleep_timer), sleepState, () -> {
            if (dref[0] != null) dref[0].dismiss();
            showSleepDialog();
        }), settingLp());

        content.addView(settingButton(getString(R.string.backup), null, () -> {
            if (dref[0] != null) dref[0].dismiss();
            showBackupDialog();
        }), settingLp());

        TextView ver = new TextView(this);
        ver.setText(R.string.version_link);
        ver.setTextColor(dimColor);
        ver.setTextSize(12f);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(dp(8), dp(22), dp(8), dp(4));
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        ver.setBackgroundResource(out.resourceId);
        ver.setOnClickListener(v ->
                openUrl("https://github.com/GuardionSpend/fable-player"));
        content.addView(ver);

        dref[0] = showStyledDialog(getString(R.string.settings), content,
                null, null, getString(R.string.close), null);
    }

    private LinearLayout.LayoutParams settingLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    /** Кликабельный пункт настроек: заголовок + необязательная подпись. */
    private View settingButton(String title, String sub, Runnable onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), dp(12), dp(4), dp(12));
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        col.setBackgroundResource(out.resourceId);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(textColor);
        t.setTextSize(15f);
        col.addView(t);
        if (sub != null) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextColor(accent);
            st.setTextSize(11.5f);
            col.addView(st);
        }
        col.setOnClickListener(v -> onClick.run());
        return col;
    }

    // ---------- Таймер сна ----------

    private void showSortDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] ref = new Dialog[1];
        String[] names = {getString(R.string.sort_off),
                getString(R.string.sort_title), getString(R.string.sort_date)};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            TextView row = menuRow(names[i], mode == sortMode ? accent : textColor);
            row.setOnClickListener(v -> {
                ref[0].dismiss();
                if (mode != sortMode) {
                    sortMode = mode;
                    prefs.edit().putInt("sort", mode).apply();
                    if (hasPermission()) { loadTracks(); list.scheduleLayoutAnimation(); }
                }
            });
            box.addView(row);
        }
        ref[0] = showStyledDialog(getString(R.string.sort), box, null, null,
                getString(R.string.close), null);
    }

    private void showThemeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] ref = new Dialog[1];
        box.addView(themeRow(getString(R.string.theme_classic),
                getString(R.string.theme_classic_sub), !frameless, ref, false));
        box.addView(themeRow(getString(R.string.theme_frameless),
                getString(R.string.theme_frameless_sub), frameless, ref, true));
        ref[0] = showStyledDialog(getString(R.string.theme), box, null, null,
                getString(R.string.close), null);
    }

    private View themeRow(String title, String sub, boolean active, Dialog[] ref, boolean wantFrameless) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        col.setBackgroundResource(out.resourceId);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(active ? accent : textColor);
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        col.addView(t);
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextColor(dimColor);
        st.setTextSize(11.5f);
        col.addView(st);
        col.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            if (wantFrameless != frameless) {
                frameless = wantFrameless;
                prefs.edit().putBoolean("frameless", frameless).apply();
                recreate();
            }
        });
        return col;
    }

    private void showSleepDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] ref = new Dialog[1];
        int[] mins = {15, 30, 45, 60, 90};
        for (final int m : mins) {
            TextView row = menuRow(m + " " + getString(R.string.m_short), textColor);
            row.setOnClickListener(v -> {
                ref[0].dismiss();
                PlayerService s = svc();
                if (s != null) s.setSleepTimer(m * 60000L);
                Toast.makeText(this, R.string.sleep_set, Toast.LENGTH_SHORT).show();
            });
            box.addView(row);
        }
        TextView end = menuRow(getString(R.string.sleep_end), textColor);
        end.setOnClickListener(v -> {
            ref[0].dismiss();
            PlayerService s = svc();
            if (s != null) s.setSleepEndOfTrack();
            Toast.makeText(this, R.string.sleep_set, Toast.LENGTH_SHORT).show();
        });
        box.addView(end);
        TextView off = menuRow(getString(R.string.sleep_off), dimColor);
        off.setOnClickListener(v -> {
            ref[0].dismiss();
            PlayerService s = svc();
            if (s != null) s.cancelSleep();
        });
        box.addView(off);
        ref[0] = showStyledDialog(getString(R.string.sleep_timer), box, null, null, null, null);
    }

    // ---------- Резервная копия ----------

    private void showBackupDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] ref = new Dialog[1];

        box.addView(backupRow(getString(R.string.backup_with_audio),
                getString(R.string.backup_with_audio_sub), () -> {
                    ref[0].dismiss();
                    startBackup(true);
                }));
        box.addView(backupRow(getString(R.string.backup_data_only),
                getString(R.string.backup_data_only_sub), () -> {
                    ref[0].dismiss();
                    startBackup(false);
                }));
        box.addView(backupRow(getString(R.string.backup_restore), null, () -> {
            ref[0].dismiss();
            startRestore();
        }));
        ref[0] = showStyledDialog(getString(R.string.backup), box, null, null,
                getString(R.string.close), null);
    }

    private View backupRow(String title, String sub, Runnable onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        col.setBackgroundResource(out.resourceId);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(textColor);
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        col.addView(t);
        if (sub != null) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextColor(dimColor);
            st.setTextSize(11.5f);
            col.addView(st);
        }
        col.setOnClickListener(v -> onClick.run());
        return col;
    }

    private void startBackup(boolean withAudio) {
        backupWithAudio = withAudio;
        String name = "fable_backup_"
                + new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date())
                + ".zip";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, REQ_BACKUP_SAVE); } catch (Exception ignored) { }
    }

    private void startRestore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, REQ_RESTORE_OPEN); } catch (Exception ignored) { }
    }

    /** Уведомление с прогресс-баром. cur<0 — «крутилка», max<=0 — завершено. */
    private void progressNotif(String title, String text, int max, int cur) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_BACKUP) == null) {
            NotificationChannel ch = new NotificationChannel(CH_BACKUP,
                    getString(R.string.backup), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_BACKUP)
                : new Notification.Builder(this);
        nb.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true);
        boolean done = max <= 0;
        nb.setOngoing(!done);
        if (cur < 0) nb.setProgress(0, 0, true);          // неопределённый
        else if (!done) nb.setProgress(max, cur, false);   // процент
        try { nm.notify(NOTIF_BACKUP, nb.build()); } catch (Exception ignored) { }
    }

    private void clearBackupNotif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_BACKUP);
    }

    private void doBackup(Uri dest, boolean withAudio) {
        Toast.makeText(this, R.string.backup_working, Toast.LENGTH_SHORT).show();
        final ArrayList<Track> snapshot = new ArrayList<>(allTracks);
        exec.execute(() -> {
            boolean ok = false;
            int total = withAudio ? snapshot.size() : 1;
            progressNotif(getString(R.string.backup_working), "0%", total, 0);
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                    getContentResolver().openOutputStream(dest))) {
                java.util.HashMap<Long, String> idToFile = new java.util.HashMap<>();
                for (Track t : snapshot) idToFile.put(t.id, t.file != null ? t.file : (t.id + ""));

                JSONObject m = new JSONObject();
                m.put("version", 1);
                m.put("hasAudio", withAudio);
                m.put("lang", prefs.getString("lang", "ru"));
                m.put("powersave", prefs.getBoolean("powersave", false));
                JSONObject eq = new JSONObject();
                for (int i = 0; i < 8; i++) eq.put("b" + i, prefs.getFloat("eq_b" + i, 0.5f));
                eq.put("bass", prefs.getFloat("eq_bass", 0f));
                eq.put("virt", prefs.getFloat("eq_virt", 0f));
                eq.put("speed", prefs.getFloat("speed", 1f));
                m.put("eq", eq);
                JSONArray favArr = new JSONArray();
                for (Track t : snapshot) if (isFav(t.id) && t.file != null) favArr.put(t.file);
                m.put("favs", favArr);
                JSONArray plArr = new JSONArray();
                for (Playlist p : playlists) {
                    JSONObject po = new JSONObject();
                    po.put("name", p.name);
                    JSONArray files = new JSONArray();
                    for (Long id : p.ids) {
                        String f = idToFile.get(id);
                        if (f != null) files.put(f);
                    }
                    po.put("tracks", files);
                    po.put("hasCover", playlistCoverFile(p.id).exists());
                    po.put("plid", p.id);
                    plArr.put(po);
                }
                m.put("playlists", plArr);

                zip.putNextEntry(new java.util.zip.ZipEntry("fable_backup.json"));
                zip.write(m.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                // обложки треков (ключ — имя файла трека)
                for (Track t : snapshot) {
                    File cf = customCoverFile(t.id);
                    if (cf.exists() && t.file != null) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("covers/" + t.file + ".jpg"));
                        copyFileToZip(cf, zip);
                        zip.closeEntry();
                    }
                }
                // обложки плейлистов
                for (Playlist p : playlists) {
                    File cf = playlistCoverFile(p.id);
                    if (cf.exists()) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("plcovers/" + p.id + ".jpg"));
                        copyFileToZip(cf, zip);
                        zip.closeEntry();
                    }
                }
                // сами треки
                if (withAudio) {
                    int idx = 0;
                    for (Track t : snapshot) {
                        idx++;
                        if (t.file == null) continue;
                        try (InputStream in = getContentResolver().openInputStream(trackUri(t))) {
                            if (in == null) continue;
                            zip.putNextEntry(new java.util.zip.ZipEntry("audio/" + t.file));
                            byte[] b = new byte[65536];
                            int n;
                            while ((n = in.read(b)) > 0) zip.write(b, 0, n);
                            zip.closeEntry();
                        } catch (Exception ignored) { }
                        int pct = idx * 100 / total;
                        progressNotif(getString(R.string.backup_working),
                                pct + "%  ·  " + idx + "/" + total, total, idx);
                    }
                }
                ok = true;
            } catch (Exception ignored) { }
            final boolean done = ok;
            ui.post(() -> {
                clearBackupNotif();
                if (done) progressNotif(getString(R.string.backup_done), null, 0, 0);
                Toast.makeText(this, done ? R.string.backup_done : R.string.restore_fail,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void copyFileToZip(File f, java.util.zip.ZipOutputStream zip) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) zip.write(b, 0, n);
        }
    }

    private void doRestore(Uri src) {
        Toast.makeText(this, R.string.restore_working, Toast.LENGTH_SHORT).show();
        progressNotif(getString(R.string.restore_working), null, 1, -1);
        exec.execute(() -> {
            File tmp = new File(getCacheDir(), "restore");
            deleteDir(tmp);
            tmp.mkdirs();
            String manifestStr = null;
            int audioRestored = 0;
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    getContentResolver().openInputStream(src))) {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[65536];
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.equals("fable_backup.json")) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        int n;
                        while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                        manifestStr = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                    } else if (name.startsWith("covers/") || name.startsWith("plcovers/")) {
                        File out = new File(tmp, name);
                        out.getParentFile().mkdirs();
                        try (FileOutputStream fo = new FileOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                        }
                    } else if (name.startsWith("audio/")) {
                        String fn = name.substring("audio/".length());
                        if (restoreAudio(fn, zin, buf)) {
                            audioRestored++;
                            progressNotif(getString(R.string.restore_working),
                                    audioRestored + "", 1, -1);
                        }
                    }
                    zin.closeEntry();
                }
            } catch (Exception ex) {
                ui.post(() -> {
                    clearBackupNotif();
                    Toast.makeText(this, R.string.restore_fail, Toast.LENGTH_LONG).show();
                });
                return;
            }
            final String manifest = manifestStr;
            final int restored = audioRestored;
            ui.post(() -> finalizeRestore(manifest, tmp, restored));
        });
    }

    /** Возвращает true, если трек реально добавлен (а не уже был). */
    private boolean restoreAudio(String fileName, InputStream in, byte[] buf) {
        for (Track t : allTracks) {
            if (fileName.equals(t.file)) return false; // уже есть — не дублируем
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/FablePlayer");
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri item = getContentResolver().insert(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (item == null) return false;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(item)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(b(buf, n));
                }
                v.clear();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                getContentResolver().update(item, v, null, null);
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC), "FablePlayer");
                dir.mkdirs();
                File out = new File(dir, fileName);
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] b(byte[] buf, int n) {
        if (n == buf.length) return buf;
        byte[] r = new byte[n];
        System.arraycopy(buf, 0, r, 0, n);
        return r;
    }

    private void finalizeRestore(String manifestStr, File tmp, int audioRestored) {
        clearBackupNotif();
        if (manifestStr == null) {
            Toast.makeText(this, R.string.restore_fail, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject m = new JSONObject(manifestStr);
            loadTracks(); // подхватить восстановленные треки и новые id

            java.util.HashMap<String, Long> fileToId = new java.util.HashMap<>();
            for (Track t : allTracks) if (t.file != null) fileToId.put(t.file, t.id);

            // настройки
            SharedPreferences.Editor e = prefs.edit();
            if (m.has("eq")) {
                JSONObject eq = m.getJSONObject("eq");
                for (int i = 0; i < 8; i++) if (eq.has("b" + i))
                    e.putFloat("eq_b" + i, (float) eq.getDouble("b" + i));
                if (eq.has("bass")) e.putFloat("eq_bass", (float) eq.getDouble("bass"));
                if (eq.has("virt")) e.putFloat("eq_virt", (float) eq.getDouble("virt"));
                if (eq.has("speed")) e.putFloat("speed", (float) eq.getDouble("speed"));
            }
            e.apply();
            PlayerService s = svc();
            if (s != null) s.refreshFx();

            // избранное
            if (m.has("favs")) {
                JSONArray fa = m.getJSONArray("favs");
                for (int i = 0; i < fa.length(); i++) {
                    Long id = fileToId.get(fa.getString(i));
                    if (id != null) favs.add(String.valueOf(id));
                }
                prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
            }

            // плейлисты
            if (m.has("playlists")) {
                JSONArray pa = m.getJSONArray("playlists");
                for (int i = 0; i < pa.length(); i++) {
                    JSONObject po = pa.getJSONObject(i);
                    Playlist p = createPlaylist(po.getString("name"));
                    JSONArray tr = po.getJSONArray("tracks");
                    for (int j = 0; j < tr.length(); j++) {
                        Long id = fileToId.get(tr.getString(j));
                        if (id != null && !p.ids.contains(id)) p.ids.add(id);
                    }
                    // обложка плейлиста по старому plid
                    if (po.optBoolean("hasCover", false) && po.has("plid")) {
                        File from = new File(tmp, "plcovers/" + po.getLong("plid") + ".jpg");
                        if (from.exists()) copyFile(from, playlistCoverFile(p.id));
                    }
                }
                savePlaylists();
            }

            // обложки треков: имя файла → новый id
            File coversDir = new File(tmp, "covers");
            if (coversDir.isDirectory()) {
                File[] files = coversDir.listFiles();
                if (files != null) {
                    for (File cf : files) {
                        String fn = cf.getName();
                        if (fn.endsWith(".jpg")) fn = fn.substring(0, fn.length() - 4);
                        Long id = fileToId.get(fn);
                        if (id != null) copyFile(cf, customCoverFile(id));
                    }
                }
            }

            deleteDir(tmp);
            thumbCache.evictAll();
            refreshShown(true);
            String summary = audioRestored > 0
                    ? audioRestored + " " + plural(audioRestored) : getString(R.string.backup);
            Toast.makeText(this, getString(R.string.restore_done, summary), Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, R.string.restore_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void copyFile(File from, File to) {
        try {
            File dir = to.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileInputStream in = new FileInputStream(from);
                 FileOutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
        } catch (Exception ignored) { }
    }

    private void deleteDir(File d) {
        if (d == null || !d.exists()) return;
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) deleteDir(k); else k.delete();
        }
        d.delete();
    }

    private void setPowerSave(boolean on) {
        powerSave = on;
        prefs.edit().putBoolean("powersave", on).apply();
        if (on) {
            particles.setRunning(false);
            list.setLayoutAnimation(null);
            if (blurAnim != null) blurAnim.cancel();
            if (Build.VERSION.SDK_INT >= 31) mainScreen.setRenderEffect(null);
            blurAmt = 0f;
        } else {
            setupListAnimation();
            PlayerService s = svc();
            if (expanded) {
                particles.setRunning(s != null && s.isPlaying());
                blurMain(1f);
            } else if (drawerOpen) {
                blurMain(0.6f);
            }
        }
    }

    // ---------- Статистика ----------

    private String formatListened(long ms) {
        long min = ms / 60000;
        long h = min / 60;
        min %= 60;
        String hs = getString(R.string.h_short), m = getString(R.string.m_short);
        return h > 0 ? h + " " + hs + " " + min + " " + m : min + " " + m;
    }

    private String favoriteTitle() {
        Track best = null;
        int bestCount = 0;
        for (Track t : allTracks) {
            int c = prefs.getInt("pc_" + t.id, 0);
            if (c > bestCount) {
                bestCount = c;
                best = t;
            }
        }
        return best == null ? "—" : best.title;
    }

    private String plural(int n) {
        if (lang.equals("en")) return n == 1 ? "track" : "tracks";
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "трек";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "трека";
        return "треков";
    }

    // ---------- Список ----------

    private class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return shownTracks.size(); }
        @Override public Object getItem(int i) { return shownTracks.get(i); }
        @Override public long getItemId(int i) { return shownTracks.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : getLayoutInflater().inflate(R.layout.item_track, parent, false);
            Track t = shownTracks.get(pos);
            boolean isCurrent = t.id == playingId;

            TextView tv = v.findViewById(R.id.item_title);
            tv.setText(t.title);
            tv.setTextColor(isCurrent ? accent : textColor);
            tv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            ((TextView) v.findViewById(R.id.item_artist)).setText(t.artist);
            ((TextView) v.findViewById(R.id.item_duration)).setText(formatTime((int) t.duration));

            TextView now = v.findViewById(R.id.item_now);
            now.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            now.setTextColor(accent);

            ImageView img = v.findViewById(R.id.item_cover);
            roundCorners(img, dp(11));
            img.setTag(t.id);
            Bitmap cached = thumbCache.get(t.id);
            if (cached != null) {
                img.setImageBitmap(cached);
            } else {
                img.setImageResource(R.drawable.ic_track_placeholder);
                exec.execute(() -> {
                    if (thumbCache.get(t.id) == null) {
                        Bitmap art = loadArt(t, 96);
                        if (art == null) return;
                        thumbCache.put(t.id, art);
                    }
                    ui.post(() -> bindThumb(img, t.id));
                });
            }
            return v;
        }

        private void bindThumb(ImageView img, long id) {
            Bitmap bmp = thumbCache.get(id);
            if (bmp != null && img.getTag() instanceof Long && (Long) img.getTag() == id) {
                img.setImageBitmap(bmp);
            }
        }
    }

    // ---------- Утилиты ----------

    private static void roundCorners(View v, final int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String formatTime(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (themeAnim != null) themeAnim.cancel();
        if (blurAnim != null) blurAnim.cancel();
        exec.shutdownNow();
        if (PlayerService.listener == this) PlayerService.listener = null;
    }
}
