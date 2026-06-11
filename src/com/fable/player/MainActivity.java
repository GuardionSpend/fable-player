package com.fable.player;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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
import android.widget.PopupMenu;
import android.widget.SeekBar;
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
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final int REQ_PICK_COVER = 2;
    private static final int REQ_PICK_PL_COVER = 3;
    private static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final int BG_BOTTOM = 0xFF0E0E14;

    private static class Track {
        long id;
        String title;
        String artist;
        long duration;
    }

    private static class Playlist {
        long id;
        String name;
        final ArrayList<Long> ids = new ArrayList<>();
    }

    // Библиотека и очередь
    private final ArrayList<Track> allTracks = new ArrayList<>();
    private final ArrayList<Track> shownTracks = new ArrayList<>();
    private final ArrayList<Track> queue = new ArrayList<>();
    private int qIndex = -1;
    private long playingId = -1;
    private final ArrayList<Playlist> playlists = new ArrayList<>();
    private Playlist activePlaylist; // null = вся музыка

    private TrackAdapter adapter;
    private MediaPlayer player;
    private int repeatMode = REPEAT_OFF;
    private boolean shuffle = false;
    private boolean userSeeking = false;
    private boolean expanded = false;
    private boolean drawerOpen = false;
    private boolean bgToggle = false;
    private long pendingCoverId = -1;
    private long pendingPlCoverId = -1;
    private final Random random = new Random();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final LruCache<Long, Bitmap> thumbCache = new LruCache<>(120);
    private final ArgbEvaluator argb = new ArgbEvaluator();

    // Статистика
    private SharedPreferences prefs;
    private long listenedMs;
    private int saveTick;

    // Тема
    private int accent, curDark;
    private int defaultAccent, textColor, dimColor;
    private ValueAnimator themeAnim;
    private GradientDrawable mainBg;

    // Вьюхи
    private View rootFrame, mainScreen, nowPlaying, miniBar, miniProgress;
    private View drawerLayer, drawer, drawerDim;
    private LinearLayout playlistContainer;
    private TextView headerTitle, listLabel, statTime, statFav, statCount;
    private EditText searchBox;
    private ImageView npCover, miniCover, bgA, bgB;
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
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        listenedMs += 300;
                        if (++saveTick >= 50) { saveTick = 0; saveStats(); }
                    }
                    int pos = player.getCurrentPosition();
                    if (!userSeeking) {
                        seek.setProgress(pos, true);
                        timeNow.setText(formatTime(pos));
                    }
                    updateMiniProgress(pos, seek.getMax());
                } catch (IllegalStateException ignored) { }
            }
            ui.postDelayed(this, 300);
        }
    };

    // ---------- Создание ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("fable", MODE_PRIVATE);
        listenedMs = prefs.getLong("listened", 0);

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
        applyThemeColors(accent, curDark);

        ui.post(progressTick);

        if (hasPermission()) loadTracks();
        else requestPermissions(new String[]{permissionName()}, REQ_PERM);
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
        miniCover = findViewById(R.id.mini_cover);
        bgA = findViewById(R.id.bg_a);
        bgB = findViewById(R.id.bg_b);
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

        AnimationSet in = new AnimationSet(true);
        in.setInterpolator(new DecelerateInterpolator(2f));
        in.setDuration(380);
        in.addAnimation(new AlphaAnimation(0f, 1f));
        in.addAnimation(new TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_SELF, 0f, TranslateAnimation.RELATIVE_TO_SELF, 0f,
                TranslateAnimation.RELATIVE_TO_SELF, 0.35f, TranslateAnimation.RELATIVE_TO_SELF, 0f));
        list.setLayoutAnimation(new LayoutAnimationController(in, 0.045f));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshShown(false); }
        });
    }

    private void setupControls() {
        miniBar.setOnClickListener(v -> expandPlayer());
        findViewById(R.id.btn_collapse).setOnClickListener(v -> collapsePlayer());
        miniPlay.setOnClickListener(v -> togglePlay());
        miniNext.setOnClickListener(v -> next(true));
        btnPlay.setOnClickListener(v -> togglePlay());
        btnNext.setOnClickListener(v -> next(true));
        btnPrev.setOnClickListener(v -> prev());
        npCover.setOnClickListener(v -> togglePlay());
        btnRepeat.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            pulse(btnRepeat);
            updateToggleButtons();
        });
        btnShuffle.setOnClickListener(v -> {
            shuffle = !shuffle;
            pulse(btnShuffle);
            updateToggleButtons();
        });

        findViewById(R.id.btn_menu).setOnClickListener(this::showMainMenu);
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
                if (player != null) {
                    try { player.seekTo(s.getProgress()); } catch (IllegalStateException ignored) { }
                }
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
        return (qIndex >= 0 && qIndex < queue.size()) ? queue.get(qIndex) : null;
    }

    /** Большая обложка всегда квадратная и вписана в доступное место. */
    private void squareCover() {
        final View frame = findViewById(R.id.cover_frame);
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int size = Math.min(r - l - dp(16), b - t - dp(16));
            if (size <= 0) return;
            ViewGroup.LayoutParams lp = npCover.getLayoutParams();
            if (lp.width != size) {
                lp.width = size;
                lp.height = size;
                npCover.setLayoutParams(lp);
            }
        });
    }

    // ---------- Свайп вправо: боковое меню ----------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
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
                    } else if (!expanded && !drawerOpen
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
    }

    /** Наполнение бокового меню: статистика + плейлисты. */
    private void buildDrawer() {
        statTime.setText(formatListened(listenedMs));
        statFav.setText(favoriteTitle());
        statCount.setText(allTracks.size() + " " + plural(allTracks.size()));

        playlistContainer.removeAllViews();
        addPlaylistRow(null);
        for (Playlist p : playlists) addPlaylistRow(p);
    }

    private void addPlaylistRow(final Playlist p) {
        View row = getLayoutInflater().inflate(R.layout.item_playlist, playlistContainer, false);
        TextView name = row.findViewById(R.id.pl_name);
        TextView count = row.findViewById(R.id.pl_count);
        ImageView img = row.findViewById(R.id.pl_cover);
        roundCorners(img, dp(10));

        boolean isActive = (p == null && activePlaylist == null)
                || (p != null && activePlaylist != null && p.id == activePlaylist.id);
        int n = p == null ? allTracks.size() : p.ids.size();
        name.setText(p == null ? getString(R.string.all_music) : p.name);
        name.setTextColor(isActive ? accent : textColor);
        name.setTypeface(null, isActive ? Typeface.BOLD : Typeface.NORMAL);
        count.setText(n + " " + plural(n));

        if (p != null) {
            File cf = playlistCoverFile(p.id);
            if (cf.exists()) {
                Bitmap bmp = decodeFileScaled(cf, 96);
                if (bmp != null) img.setImageBitmap(bmp);
            }
            row.setOnLongClickListener(v -> {
                showPlaylistMenu(v, p);
                return true;
            });
        }

        row.setOnClickListener(v -> {
            activePlaylist = p;
            refreshShown(true);
            closeDrawer();
        });
        playlistContainer.addView(row);
    }

    private void showPlaylistMenu(View anchor, final Playlist p) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.rename);
        menu.getMenu().add(0, 2, 1, R.string.pick_cover);
        menu.getMenu().add(0, 3, 2, R.string.delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showNameDialog(getString(R.string.rename), p.name, name -> {
                        p.name = name;
                        savePlaylists();
                        buildDrawer();
                        if (activePlaylist == p) refreshShown(false);
                    });
                    break;
                case 2:
                    pendingPlCoverId = p.id;
                    pickImage(REQ_PICK_PL_COVER);
                    break;
                case 3:
                    playlists.remove(p);
                    playlistCoverFile(p.id).delete();
                    savePlaylists();
                    if (activePlaylist == p) {
                        activePlaylist = null;
                        refreshShown(true);
                    }
                    buildDrawer();
                    break;
            }
            return true;
        });
        menu.show();
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
        if (initial != null) et.setText(initial);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(22), dp(8), dp(22), 0);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!name.isEmpty()) cb.onName(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface NameCallback { void onName(String name); }

    private void addToPlaylistDialog(final Track t) {
        String[] items = new String[playlists.size() + 1];
        for (int i = 0; i < playlists.size(); i++) items[i] = playlists.get(i).name;
        items[playlists.size()] = "+  " + getString(R.string.new_playlist);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_to_playlist)
                .setItems(items, (d, which) -> {
                    if (which < playlists.size()) {
                        addTrackTo(playlists.get(which), t);
                    } else {
                        showNameDialog(getString(R.string.new_playlist), null, name ->
                                addTrackTo(createPlaylist(name), t));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_refresh);
        menu.getMenu().add(0, 2, 1, R.string.menu_author);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (hasPermission()) {
                    loadTracks();
                    list.scheduleLayoutAnimation();
                }
            } else {
                openTelegram();
            }
            return true;
        });
        menu.show();
    }

    private void showTrackMenu(View anchor, final Track t) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_set_cover);
        if (customCoverFile(t.id).exists()) {
            menu.getMenu().add(0, 2, 1, R.string.menu_remove_cover);
        }
        menu.getMenu().add(0, 3, 2, R.string.add_to_playlist);
        if (activePlaylist != null && activePlaylist.ids.contains(t.id)) {
            menu.getMenu().add(0, 4, 3, R.string.remove_from_playlist);
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    pendingCoverId = t.id;
                    pickImage(REQ_PICK_COVER);
                    break;
                case 2:
                    removeCustomCover(t.id);
                    break;
                case 3:
                    addToPlaylistDialog(t);
                    break;
                case 4:
                    activePlaylist.ids.remove(t.id);
                    savePlaylists();
                    refreshShown(false);
                    break;
            }
            return true;
        });
        menu.show();
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
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

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
        if (code == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                loadTracks();
            } else {
                empty.setText(R.string.no_permission);
                empty.setVisibility(View.VISIBLE);
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
                MediaStore.Audio.Media.DURATION
        };
        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
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
                    allTracks.add(t);
                }
            }
        }
        refreshShown(false);
    }

    /** Пересобирает видимый список: активный плейлист + поисковый запрос. */
    private void refreshShown(boolean animate) {
        shownTracks.clear();
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.getDefault());
        ArrayList<Track> base;
        if (activePlaylist == null) {
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

        listLabel.setText(activePlaylist == null
                ? getString(R.string.my_music) : activePlaylist.name);
        if (!q.isEmpty()) empty.setText(R.string.no_results);
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
        // мини-плеер прячется, чтобы не перекрывать кнопки плеера
        miniBar.animate().translationY(dp(140)).alpha(0f)
                .setDuration(260)
                .withEndAction(() -> miniBar.setVisibility(View.INVISIBLE))
                .start();
    }

    private void collapsePlayer() {
        if (!expanded) return;
        expanded = false;
        nowPlaying.animate().translationY(rootFrame.getHeight())
                .setDuration(360)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(() -> nowPlaying.setVisibility(View.INVISIBLE))
                .start();
        mainScreen.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).start();
        if (playingId >= 0) {
            miniBar.setVisibility(View.VISIBLE);
            miniBar.animate().translationY(0).alpha(1f).setDuration(320).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else if (expanded) collapsePlayer();
        else super.onBackPressed();
    }

    // ---------- Воспроизведение ----------

    private void playFromShown(int pos) {
        if (pos < 0 || pos >= shownTracks.size()) return;
        queue.clear();
        queue.addAll(shownTracks);
        startTrack(pos);
    }

    private void startTrack(int index) {
        if (index < 0 || index >= queue.size()) return;
        Track t = queue.get(index);
        qIndex = index;
        playingId = t.id;

        if (player != null) {
            player.release();
            player = null;
        }
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnCompletionListener(mp -> onTrackFinished());
        try {
            player.setDataSource(this, trackUri(t));
            player.prepare();
            player.start();
        } catch (Exception e) {
            player.release();
            player = null;
            return;
        }

        prefs.edit().putInt("pc_" + t.id, prefs.getInt("pc_" + t.id, 0) + 1).apply();

        npTitle.setText(t.title);
        npArtist.setText(t.artist);
        miniTitle.setText(t.title);
        miniArtist.setText(t.artist);
        seek.setMax((int) t.duration);
        seek.setProgress(0);
        timeNow.setText("0:00");
        timeTotal.setText(formatTime((int) t.duration));
        setPlayingState(true);
        if (!expanded) showMiniBar();
        adapter.notifyDataSetChanged();
        loadCoverAndTheme(t);
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

    private void togglePlay() {
        if (player == null) {
            if (!shownTracks.isEmpty()) playFromShown(0);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            setPlayingState(false);
        } else {
            player.start();
            setPlayingState(true);
        }
    }

    /** Иконки play/pause с «пружинкой», обложка слегка дышит при паузе. */
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
    }

    private void next(boolean byUser) {
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

    private void prev() {
        if (queue.isEmpty()) return;
        if (player != null && player.getCurrentPosition() > 4000) {
            player.seekTo(0);
            return;
        }
        int n = qIndex - 1;
        if (n < 0) n = queue.size() - 1;
        startTrack(n);
    }

    private void onTrackFinished() {
        if (repeatMode == REPEAT_ONE && player != null) {
            player.seekTo(0);
            player.start();
        } else {
            next(false);
        }
    }

    private void stopAtEnd() {
        if (player != null) {
            player.pause();
            player.seekTo(0);
        }
        seek.setProgress(0);
        timeNow.setText("0:00");
        updateMiniProgress(0, seek.getMax());
        setPlayingState(false);
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
            final Bitmap blurBmp = art != null ? blur(art) : null;
            ui.post(() -> {
                if (playingId != id) return;
                swapCover(art);
                swapBackground(blurBmp);
                animateTheme(color);
            });
        });
    }

    /** Смена обложки с лёгким fade + «пружинным» появлением. */
    private void swapCover(Bitmap art) {
        npCover.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                .setDuration(150)
                .withEndAction(() -> {
                    if (art != null) {
                        npCover.setImageBitmap(art);
                        miniCover.setImageBitmap(art);
                    } else {
                        npCover.setImageResource(R.drawable.ic_track_placeholder);
                        miniCover.setImageResource(R.drawable.ic_track_placeholder);
                    }
                    boolean playing = player != null && player.isPlaying();
                    float s = playing ? 1f : 0.93f;
                    npCover.animate().alpha(1f).scaleX(s).scaleY(s)
                            .setDuration(420)
                            .setInterpolator(new OvershootInterpolator(1.1f))
                            .start();
                })
                .start();
    }

    /** Кроссфейд размытого фона между двумя слоями. */
    private void swapBackground(Bitmap blurBmp) {
        if (blurBmp == null) {
            bgA.animate().alpha(0f).setDuration(700).start();
            bgB.animate().alpha(0f).setDuration(700).start();
            return;
        }
        ImageView show = bgToggle ? bgB : bgA;
        ImageView hide = bgToggle ? bgA : bgB;
        bgToggle = !bgToggle;
        show.setImageBitmap(blurBmp);
        show.animate().alpha(1f).setDuration(750).start();
        hide.animate().alpha(0f).setDuration(750).start();
    }

    /** «Размытие» через уменьшение до 26px и растягивание с фильтрацией. */
    private Bitmap blur(Bitmap src) {
        int h = Math.max(1, (int) (26f * src.getHeight() / src.getWidth()));
        return Bitmap.createScaledBitmap(src, 26, h, true);
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

    /** Доминирующий «живой» цвет: пиксели группируются по тону,
     *  побеждает группа с наибольшим весом (насыщенность × близость к средней яркости). */
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

    /** Плавный перелив всей темы в цвет новой обложки. */
    private void animateTheme(int toAccent) {
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
        getWindow().setStatusBarColor(dark);
        getWindow().setNavigationBarColor(BG_BOTTOM);

        ColorStateList accentTint = ColorStateList.valueOf(acc);
        btnPlay.setBackgroundTintList(accentTint);
        btnPlay.setImageTintList(ColorStateList.valueOf(BG_BOTTOM));
        seek.setProgressTintList(accentTint);
        seek.setThumbTintList(accentTint);
        miniProgress.setBackgroundColor(acc);
        btnPrev.setImageTintList(ColorStateList.valueOf(textColor));
        btnNext.setImageTintList(ColorStateList.valueOf(textColor));
        miniPlay.setImageTintList(ColorStateList.valueOf(textColor));
        miniNext.setImageTintList(ColorStateList.valueOf(textColor));

        String text = headerTitle.getText().toString();
        headerTitle.getPaint().setShader(new LinearGradient(
                0, 0, Math.max(1f, headerTitle.getPaint().measureText(text)), 0,
                acc, textColor, Shader.TileMode.CLAMP));
        headerTitle.invalidate();

        updateToggleButtons();
    }

    private void updateToggleButtons() {
        btnRepeat.setImageResource(repeatMode == REPEAT_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        btnRepeat.setImageTintList(ColorStateList.valueOf(repeatMode != REPEAT_OFF ? accent : dimColor));
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

    // ---------- Статистика ----------

    private void saveStats() {
        prefs.edit().putLong("listened", listenedMs).apply();
    }

    private String formatListened(long ms) {
        long min = ms / 60000;
        long h = min / 60;
        min %= 60;
        return h > 0 ? h + " ч " + min + " мин" : min + " мин";
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

    private static String plural(int n) {
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
    protected void onPause() {
        super.onPause();
        saveStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveStats();
        ui.removeCallbacksAndMessages(null);
        if (themeAnim != null) themeAnim.cancel();
        exec.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
