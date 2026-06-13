package com.tvcatolica.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.app.Activity;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    // ── Constantes (equivalentes às do APK original) ──────────
    private static final long KEEPALIVE_MS       = 25000;
    private static final long PREPARE_TIMEOUT_MS = 18000;
    private static final long PRE_BUFFER_DELAY_MS= 5000;
    private static final int  MAX_RETRY          = 10;
    private static final long UI_HIDE_MS         = 5000;
    private static final int  TIMEOUT_MS         = 10000;

    // ── ExoPlayer adaptado para TV Box com pouca RAM ──────────
    // Buffer reduzido: 10MB RAM, 15s buffer (padrão seria 50MB/50s)
    private static final int BUFFER_MIN_MS     = 5_000;
    private static final int BUFFER_MAX_MS     = 15_000;
    private static final int BUFFER_PLAYBACK_MS= 2_500;
    private static final int BUFFER_REBUFFER_MS= 5_000;

    // ── Canais (ordem exata do APK original) ──────────────────
    private static final String[] CHANNEL_NAMES = {
        "Paróquia Vianney", "TV Família",    "Milênio TV",
        "Canção Nova",      "TV Católica 2", "TV Católica",
        "TV Católica HD",   "Gospel Cartoon","Santa Cruz TV",
        "TV Horizonte",     "TV Padre Cícero","Rede Imaculada",
        "TV Pai Eterno"
    };
    private static final String[] CHANNEL_URLS = {
        "https://58c8a6b3c74e2.streamlock.net:1936/paroquiavianney/smil:paroquiavianney.smil/playlist.m3u8",
        "https://59d39900ebfb8.streamlock.net/tvfamilia_480p/tvfamilia_480p/playlist.m3u8",
        "https://5a57bda70564a.streamlock.net/mileniotv/mileniotv.sdp/playlist.m3u8",
        "https://5c65286fc6ace.streamlock.net/cancaonova/CancaoNova.stream_720p/playlist.m3u8",
        "https://cdn.live.br1.jmvstream.com/w/LVW-19954/LVW19954_V2nXt9iI2m/playlist.m3u8",
        "https://cdn.live.br1.jmvstream.com/w/LVW-9716/LVW9716_HbtQtezcaw/chunklist.m3u8",
        "https://d12e4o88jd8gex.cloudfront.net/out/v1/cea3de0b76ac4e82ab8ee0fd3f17ce12/index.m3u8",
        "https://stmv1.srvif.com/gospelcartoon/gospelcartoon/playlist.m3u8",
        "https://stmv1.srvstm.com/santacruztv9906/santacruztv9906/playlist.m3u8",
        "https://tvhorizonte.brasilstream.com.br/hls/tvhorizonte/index.m3u8",
        "https://video01.logicahost.com.br/tvpadrecicero/tvpadrecicero/playlist.m3u8",
        "https://video08.logicahost.com.br/redeimaculada/redeimaculada/playlist.m3u8",
        "https://video09.logicahost.com.br/paieterno/paieterno/playlist.m3u8"
    };

    // ── Estado ────────────────────────────────────────────────
    private int currentIndex = 5; // começa na TV Católica
    private int retryCount   = 0;
    private boolean uiVisible= false;
    private boolean surfaceReady = false;

    // ── Players ───────────────────────────────────────────────
    private ExoPlayer mainPlayer;
    private ExoPlayer shadowNext; // pre-buffer próximo canal
    private ExoPlayer shadowPrev; // pre-buffer canal anterior

    // ── Views ─────────────────────────────────────────────────
    private PlayerView surfaceView;
    private View       touchOverlay;
    private View       uiLayer;
    private TextView   chName, chNum, loadTxt, retryTxt, errMsg;
    private View       loadingView, errorLayout;
    private Button     retryButton;
    private LinearLayout chList;

    // ── Timers ────────────────────────────────────────────────
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable keepaliveTask, prepareTimeoutTask, preBufferTask, uiTask, retryTask;

    // ── Gesto (swipe) ─────────────────────────────────────────
    private GestureDetector gestureDetector;
    private AudioManager    audioManager;
    private AudioFocusRequest audioFocusRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tela sempre ligada + fullscreen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_player);

        surfaceView  = findViewById(R.id.surface_view);
        touchOverlay = findViewById(R.id.touch_overlay);
        uiLayer      = findViewById(R.id.ui_layer);
        chName       = findViewById(R.id.ch_name);
        chNum        = findViewById(R.id.ch_num);
        loadTxt      = findViewById(R.id.load_txt);
        retryTxt     = findViewById(R.id.retry_txt);
        loadingView  = findViewById(R.id.loading_view);
        errorLayout  = findViewById(R.id.error_layout);
        errMsg       = findViewById(R.id.err_msg);
        retryButton  = findViewById(R.id.retry_button);
        chList       = findViewById(R.id.ch_list);

        // Esconder controles nativos do ExoPlayer (interface limpa)
        surfaceView.setUseController(false);

        // Gesto de swipe
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 60) {
                    if (dx < 0) goNext(); else goPrev();
                    return true;
                }
                return false;
            }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleUI(); return true;
            }
        });

        touchOverlay.setOnTouchListener((v, e) -> {
            gestureDetector.onTouchEvent(e); return true;
        });

        retryButton.setOnClickListener(v -> { hideError(); loadChannel(currentIndex, null); });

        requestAudioFocus();
        buildList();
        loadChannel(currentIndex, null);
        showUI();
    }

    // ── loadChannel ───────────────────────────────────────────
    private void loadChannel(int index, String fromShadow) {
        if (index < 0) index = CHANNEL_NAMES.length - 1;
        if (index >= CHANNEL_NAMES.length) index = 0;
        currentIndex = index;
        retryCount   = 0;

        cancelKeepalive();
        cancelPreBuffer();
        cancelPrepareTimeout();
        if (retryTask != null) { handler.removeCallbacks(retryTask); retryTask = null; }

        hideError();
        showLoading(CHANNEL_NAMES[currentIndex], 0);
        updateChannelInfo();
        buildList();

        // Usar shadow (pre-buffer) se disponível — igual ao APK original
        if ("next".equals(fromShadow) && shadowNext != null) {
            releaseMain();
            mainPlayer = shadowNext; shadowNext = null;
            attachMain();
        } else if ("prev".equals(fromShadow) && shadowPrev != null) {
            releaseMain();
            mainPlayer = shadowPrev; shadowPrev = null;
            attachMain();
        } else {
            startMain(CHANNEL_URLS[currentIndex]);
        }

        startKeepalive();
        schedulePrepareTimeout();
        preBufferTask = () -> scheduleAllShadows();
        handler.postDelayed(preBufferTask, PRE_BUFFER_DELAY_MS);
    }

    // ── startMain ─────────────────────────────────────────────
    private void startMain(String url) {
        releaseMain();
        mainPlayer = buildPlayer();
        surfaceView.setPlayer(mainPlayer);

        DataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(TIMEOUT_MS)
            .setReadTimeoutMs(TIMEOUT_MS)
            .setUserAgent("CanalTV/1.0");

        HlsMediaSource source = new HlsMediaSource.Factory(dsFactory)
            .createMediaSource(MediaItem.fromUri(url));

        mainPlayer.setMediaSource(source);
        mainPlayer.prepare();
        mainPlayer.setPlayWhenReady(true);
        attachMain();
    }

    private void attachMain() {
        if (mainPlayer == null) return;
        surfaceView.setPlayer(mainPlayer);
        mainPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    cancelPrepareTimeout();
                    hideLoading(); hideError();
                    retryCount = 0;
                    startKeepalive();
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading(CHANNEL_NAMES[currentIndex], 0);
                } else if (state == Player.STATE_ENDED) {
                    doRecover();
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                doRecover();
            }
        });
    }

    // ── buildPlayer — ExoPlayer com LoadControl adaptado ─────
    // Aqui está o coração da adaptação: menos RAM, buffer menor
    private ExoPlayer buildPlayer() {
        LoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                BUFFER_MIN_MS,      // buffer mínimo: 5s
                BUFFER_MAX_MS,      // buffer máximo: 15s (padrão: 50s)
                BUFFER_PLAYBACK_MS, // tempo pra iniciar playback: 2.5s
                BUFFER_REBUFFER_MS  // tempo pra rebuffer: 5s
            )
            .setTargetBufferBytes(10 * 1024 * 1024) // 10MB max RAM (padrão: sem limite)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();

        return new ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build();
    }

    // ── buildShadow (pre-buffer leve) ─────────────────────────
    private ExoPlayer buildShadow(String url) {
        LoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 5000, 1000, 2000)
            .setTargetBufferBytes(2 * 1024 * 1024) // 2MB
            .build();
        ExoPlayer p = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        DataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000);
        HlsMediaSource src = new HlsMediaSource.Factory(dsFactory)
            .createMediaSource(MediaItem.fromUri(url));
        p.setMediaSource(src);
        p.prepare();
        p.setPlayWhenReady(false); // só pré-carrega, não toca
        return p;
    }

    private void scheduleAllShadows() {
        cancelAllShadowTasks();
        int ni = (currentIndex + 1) % CHANNEL_NAMES.length;
        int pi = (currentIndex - 1 + CHANNEL_NAMES.length) % CHANNEL_NAMES.length;
        try { shadowNext = buildShadow(CHANNEL_URLS[ni]); } catch (Exception e) { shadowNext = null; }
        try { shadowPrev = buildShadow(CHANNEL_URLS[pi]); } catch (Exception e) { shadowPrev = null; }
    }

    private void cancelAllShadowTasks() {
        if (shadowNext != null) { shadowNext.release(); shadowNext = null; }
        if (shadowPrev != null) { shadowPrev.release(); shadowPrev = null; }
    }

    // ── doRecover — reconexão exponencial ─────────────────────
    private void doRecover() {
        retryCount++;
        if (retryCount > MAX_RETRY) {
            showError("Sem sinal após várias tentativas.\nVerifique sua internet.");
            return;
        }
        long delay = Math.min(2000L * retryCount, 15000L);
        showLoading(CHANNEL_NAMES[currentIndex], retryCount);
        retryTask = () -> startMain(CHANNEL_URLS[currentIndex]);
        handler.postDelayed(retryTask, delay);
    }

    // ── Keepalive ─────────────────────────────────────────────
    private void startKeepalive() {
        cancelKeepalive();
        keepaliveTask = () -> {
            if (mainPlayer == null || !mainPlayer.isPlaying()) doRecover();
            else startKeepalive();
        };
        handler.postDelayed(keepaliveTask, KEEPALIVE_MS);
    }
    private void cancelKeepalive()       { if (keepaliveTask != null) { handler.removeCallbacks(keepaliveTask); keepaliveTask = null; } }
    private void cancelPreBuffer()       { if (preBufferTask != null) { handler.removeCallbacks(preBufferTask); preBufferTask = null; } }
    private void schedulePrepareTimeout() {
        cancelPrepareTimeout();
        prepareTimeoutTask = () -> { if (mainPlayer == null || mainPlayer.getPlaybackState() < Player.STATE_READY) doRecover(); };
        handler.postDelayed(prepareTimeoutTask, PREPARE_TIMEOUT_MS);
    }
    private void cancelPrepareTimeout() { if (prepareTimeoutTask != null) { handler.removeCallbacks(prepareTimeoutTask); prepareTimeoutTask = null; } }
    private void releaseMain() {
        cancelKeepalive();
        if (mainPlayer != null) { mainPlayer.release(); mainPlayer = null; }
    }

    // ── UI ─────────────────────────────────────────────────────
    private void buildList() {
        chList.removeAllViews();
        for (int i = 0; i < CHANNEL_NAMES.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText((i + 1) + ". " + CHANNEL_NAMES[i]);
            tv.setTextColor(i == currentIndex ? 0xFF000000 : 0xFFFFFFFF);
            tv.setBackgroundColor(i == currentIndex ? 0xFFFFFFFF : 0x33FFFFFF);
            tv.setPadding(24, 16, 24, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd(12);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> { loadChannel(idx, null); hideUI(); });
            chList.addView(tv);
        }
    }

    private void updateChannelInfo() {
        chName.setText(CHANNEL_NAMES[currentIndex]);
        chNum.setText("Canal " + (currentIndex + 1) + " de " + CHANNEL_NAMES.length);
    }

    private void showUI() {
        uiLayer.setVisibility(View.VISIBLE);
        uiVisible = true;
        if (uiTask != null) handler.removeCallbacks(uiTask);
        uiTask = this::hideUI;
        handler.postDelayed(uiTask, UI_HIDE_MS);
    }
    private void hideUI() { uiLayer.setVisibility(View.INVISIBLE); uiVisible = false; }
    private void toggleUI() { if (uiVisible) hideUI(); else showUI(); }

    private void showLoading(String name, int retry) {
        loadingView.setVisibility(View.VISIBLE);
        errorLayout.setVisibility(View.GONE);
        loadTxt.setText("Carregando " + name + "...");
        retryTxt.setText(retry > 0 ? "Tentativa " + retry + " de " + MAX_RETRY : "");
    }
    private void hideLoading() { loadingView.setVisibility(View.GONE); }
    private void showError(String msg) { hideLoading(); errorLayout.setVisibility(View.VISIBLE); errMsg.setText(msg); }
    private void hideError() { errorLayout.setVisibility(View.GONE); }

    // ── Navegação ─────────────────────────────────────────────
    private void goNext() { loadChannel(currentIndex + 1, "next"); showUI(); }
    private void goPrev() { loadChannel(currentIndex - 1, "prev"); showUI(); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                goPrev(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                goNext(); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
                adjustVolume(AudioManager.ADJUST_RAISE); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                adjustVolume(AudioManager.ADJUST_LOWER); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleUI(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (uiVisible) { hideUI(); return true; }
                return super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    private void adjustVolume(int direction) {
        if (audioManager != null)
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    private void requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attr).build();
            audioManager.requestAudioFocus(audioFocusRequest);
        }
    }

    @Override
    protected void onPause()   { super.onPause();   if (mainPlayer != null) mainPlayer.setPlayWhenReady(false); }
    @Override
    protected void onResume()  { super.onResume();  if (mainPlayer != null) mainPlayer.setPlayWhenReady(true); }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelKeepalive(); cancelPreBuffer(); cancelPrepareTimeout();
        cancelAllShadowTasks();
        releaseMain();
    }
}
