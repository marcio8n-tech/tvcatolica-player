package com.tvcatolica.exoplayer;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    // Ordem idêntica ao app original
    private static final String[] URLS = {
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

    // Timings
    private static final long KEEPALIVE_MS = 20_000;
    private static final long TIMEOUT_MS   = 15_000;
    private static final long RETRY_MAX_MS = 15_000;
    private static final int  MAX_RETRY    = 10;

    // Estado
    private int idx     = 5; // TV Católica
    private int retries = 0;

    // UM único player — sem shadow, sem pré-carregamento
    private ExoPlayer player;

    // Views
    private PlayerView playerView;
    private View       touchArea;

    // Handler
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable rKeep, rTimeout, rRetry;

    private GestureDetector gesture;
    private AudioManager    audio;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_main);
        touchArea  = findViewById(R.id.touch_area);

        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Swipe horizontal para trocar canal
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 100 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx < 0) goNext(); else goPrev();
                    return true;
                }
                return false;
            }
        });
        touchArea.setOnTouchListener((v, e) -> { gesture.onTouchEvent(e); return true; });

        play(idx);
    }

    // Navegar
    private void goNext() { play(clamp(idx + 1)); }
    private void goPrev() { play(clamp(idx - 1)); }

    // Carregar canal — destrói o player anterior e cria um novo limpo
    private void play(int i) {
        idx     = clamp(i);
        retries = 0;
        cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        releasePlayer();
        startPlayer();
    }

    private void startPlayer() {
        // Buffer bem conservador para TV Box com pouca RAM
        LoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                3_000,   // min buffer antes de começar
                8_000,   // max buffer (não acumula mais que isso)
                1_500,   // buffer para iniciar playback
                2_000)   // buffer para sair do rebuffering
            .setTargetBufferBytes(4 * 1024 * 1024) // máx 4MB de buffer na RAM
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();

        player = new ExoPlayer.Builder(this)
            .setLoadControl(lc)
            .build();

        playerView.setPlayer(player);

        HlsMediaSource src = new HlsMediaSource.Factory(
            new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(10_000)
                .setUserAgent("TVCatolica/7.0"))
            .createMediaSource(MediaItem.fromUri(Uri.parse(URLS[idx])));

        player.setMediaSource(src);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    cancel(rTimeout);
                    retries = 0;
                    keepalive();
                } else if (st == Player.STATE_ENDED) {
                    recover();
                }
            }
            @Override public void onPlayerError(PlaybackException e) { recover(); }
        });

        // Timeout se não abrir
        cancel(rTimeout);
        rTimeout = this::recover;
        h.postDelayed(rTimeout, TIMEOUT_MS);
    }

    // Reconexão automática silenciosa
    private void recover() {
        retries++;
        if (retries > MAX_RETRY) {
            // Pausa 30s e tenta do zero
            releasePlayer();
            h.postDelayed(() -> { retries = 0; startPlayer(); }, 30_000);
            return;
        }
        long delay = Math.min(2_000L * retries, RETRY_MAX_MS);
        cancel(rRetry);
        rRetry = () -> { releasePlayer(); startPlayer(); };
        h.postDelayed(rRetry, delay);
    }

    // Verifica se o stream não parou silenciosamente
    private void keepalive() {
        cancel(rKeep);
        rKeep = () -> {
            if (player != null
                    && !player.isPlaying()
                    && player.getPlaybackState() != Player.STATE_BUFFERING) {
                recover();
            } else {
                keepalive();
            }
        };
        h.postDelayed(rKeep, KEEPALIVE_MS);
    }

    // Utilitários
    private int clamp(int i) {
        if (i < 0) return URLS.length - 1;
        if (i >= URLS.length) return 0;
        return i;
    }

    private void releasePlayer() {
        cancel(rKeep);
        if (player != null) { player.release(); player = null; }
    }

    private void cancel(Runnable r) { if (r != null) h.removeCallbacks(r); }

    // D-Pad
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                goPrev(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                goNext(); return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return true;
            case KeyEvent.KEYCODE_BACK:
                finish(); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onPause()  { super.onPause();  if (player != null) player.setPlayWhenReady(false); }
    @Override protected void onResume() { super.onResume(); if (player != null) player.setPlayWhenReady(true);  }
    @Override protected void onDestroy() {
        super.onDestroy();
        cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        releasePlayer();
    }
}
