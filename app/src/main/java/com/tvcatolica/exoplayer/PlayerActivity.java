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

    // ── Ordem EXATA do app original ──────────────────────────────
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

    // ── Timings ──────────────────────────────────────────────────
    private static final int  MAX_RETRY     = 12;
    private static final long RETRY_BASE_MS = 3_000;
    private static final long KEEPALIVE_MS  = 20_000;
    private static final long TIMEOUT_MS    = 15_000;
    // Shadow começa imediatamente após o main ficar pronto
    private static final long SHADOW_DELAY_MS = 1_500;

    // Buffer main: equilibrado para 4-6 Mbps
    private static final int M_MIN   = 4_000;
    private static final int M_MAX   = 12_000;
    private static final int M_PLAY  = 1_500;
    private static final int M_REBUF = 3_000;

    // Buffer shadow: agressivo — só precisa ter os primeiros segmentos prontos
    private static final int S_MIN   = 8_000;  // exige 8s antes de considerar "pronto"
    private static final int S_MAX   = 20_000;
    private static final int S_PLAY  = 8_000;
    private static final int S_REBUF = 8_000;

    // ── Estado ───────────────────────────────────────────────────
    private int idx     = 5;   // inicia na TV Católica (posição original)
    private int retries = 0;

    // ── Players ──────────────────────────────────────────────────
    private ExoPlayer main;
    private ExoPlayer shadowNext;  // pré-carrega idx+1
    private ExoPlayer shadowPrev;  // pré-carrega idx-1
    private int       shadowNextIdx = -1;
    private int       shadowPrevIdx = -1;

    // ── Views ────────────────────────────────────────────────────
    private PlayerView playerView;
    private View       touchArea;

    // ── Handler ──────────────────────────────────────────────────
    private final Handler  h = new Handler(Looper.getMainLooper());
    private Runnable rKeep, rTimeout, rRetry, rShadow;

    private GestureDetector gesture;
    private AudioManager    audio;

    // ════════════════════════════════════════════════════════════
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

        // Swipe: esquerda = próximo, direita = anterior (igual app original)
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

        loadChannel(idx);
    }

    // ════════════════════════════════════════════════════════════
    // Navegação
    private void goNext() {
        int next = clamp(idx + 1);
        // Se shadow do próximo já está pronto: troca instantânea
        if (shadowNext != null && shadowNextIdx == next &&
                shadowNext.getPlaybackState() >= Player.STATE_READY) {
            promoteNext();
        } else {
            loadChannel(next);
        }
    }

    private void goPrev() {
        int prev = clamp(idx - 1);
        if (shadowPrev != null && shadowPrevIdx == prev &&
                shadowPrev.getPlaybackState() >= Player.STATE_READY) {
            promotePrev();
        } else {
            loadChannel(prev);
        }
    }

    // Promove shadowNext → main
    private void promoteNext() {
        cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        releaseMain();
        main = shadowNext;
        shadowNext = null; shadowNextIdx = -1;
        idx = clamp(idx + 1);
        attachMain();
        main.setPlayWhenReady(true);
        scheduleShadows();
    }

    // Promove shadowPrev → main
    private void promotePrev() {
        cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        releaseMain();
        main = shadowPrev;
        shadowPrev = null; shadowPrevIdx = -1;
        idx = clamp(idx - 1);
        attachMain();
        main.setPlayWhenReady(true);
        scheduleShadows();
    }

    // Carrega canal do zero (sem shadow disponível)
    private void loadChannel(int i) {
        idx = clamp(i); retries = 0;
        cancel(rKeep); cancel(rTimeout); cancel(rRetry); cancel(rShadow);
        releaseAllShadows();
        startMain(URLS[idx]);
    }

    // ════════════════════════════════════════════════════════════
    // Player principal
    private void startMain(String url) {
        releaseMain();
        main = buildPlayer(M_MIN, M_MAX, M_PLAY, M_REBUF, 10 * 1024 * 1024);
        main.setMediaSource(hlsSource(url));
        main.prepare();
        main.setPlayWhenReady(true);
        attachMain();

        cancel(rTimeout);
        rTimeout = this::recover;
        h.postDelayed(rTimeout, TIMEOUT_MS);
    }

    private void attachMain() {
        playerView.setPlayer(main);
        main.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    cancel(rTimeout);
                    retries = 0;
                    keepalive();
                    // Assim que o main está rodando, agenda shadows
                    cancel(rShadow);
                    rShadow = PlayerActivity.this::scheduleShadows;
                    h.postDelayed(rShadow, SHADOW_DELAY_MS);
                } else if (st == Player.STATE_ENDED) {
                    recover();
                }
            }
            @Override public void onPlayerError(PlaybackException e) { recover(); }
        });
    }

    // ════════════════════════════════════════════════════════════
    // Pré-carregamento dos dois vizinhos simultaneamente
    private void scheduleShadows() {
        int ni = clamp(idx + 1);
        int pi = clamp(idx - 1);

        // Só recria se o índice mudou
        if (shadowNextIdx != ni) {
            if (shadowNext != null) shadowNext.release();
            shadowNext    = buildPlayer(S_MIN, S_MAX, S_PLAY, S_REBUF, 5 * 1024 * 1024);
            shadowNextIdx = ni;
            shadowNext.setMediaSource(hlsSource(URLS[ni]));
            shadowNext.prepare();
            shadowNext.setPlayWhenReady(false);
        }

        if (shadowPrevIdx != pi) {
            if (shadowPrev != null) shadowPrev.release();
            shadowPrev    = buildPlayer(S_MIN, S_MAX, S_PLAY, S_REBUF, 5 * 1024 * 1024);
            shadowPrevIdx = pi;
            shadowPrev.setMediaSource(hlsSource(URLS[pi]));
            shadowPrev.prepare();
            shadowPrev.setPlayWhenReady(false);
        }
    }

    // ════════════════════════════════════════════════════════════
    // Recuperação automática (sem mostrar nada na tela)
    private void recover() {
        retries++;
        if (retries > MAX_RETRY) {
            h.postDelayed(() -> { retries = 0; startMain(URLS[idx]); }, 30_000);
            return;
        }
        long delay = Math.min(RETRY_BASE_MS * retries, 20_000L);
        cancel(rRetry);
        rRetry = () -> startMain(URLS[idx]);
        h.postDelayed(rRetry, delay);
    }

    private void keepalive() {
        cancel(rKeep);
        rKeep = () -> {
            if (main != null && !main.isPlaying()
                    && main.getPlaybackState() != Player.STATE_BUFFERING) {
                recover();
            } else {
                keepalive();
            }
        };
        h.postDelayed(rKeep, KEEPALIVE_MS);
    }

    // ════════════════════════════════════════════════════════════
    // Utilitários
    private ExoPlayer buildPlayer(int bMin, int bMax, int bPlay, int bRebuf, int maxBytes) {
        LoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(bMin, bMax, bPlay, bRebuf)
            .setTargetBufferBytes(maxBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();
        return new ExoPlayer.Builder(this).setLoadControl(lc).build();
    }

    private HlsMediaSource hlsSource(String url) {
        return new HlsMediaSource.Factory(
            new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(10_000)
                .setUserAgent("TVCatolica/5.0"))
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
    }

    private int clamp(int i) {
        if (i < 0) return URLS.length - 1;
        if (i >= URLS.length) return 0;
        return i;
    }

    private void releaseMain() {
        cancel(rKeep);
        if (main != null) { main.release(); main = null; }
    }

    private void releaseAllShadows() {
        if (shadowNext != null) { shadowNext.release(); shadowNext = null; shadowNextIdx = -1; }
        if (shadowPrev != null) { shadowPrev.release(); shadowPrev = null; shadowPrevIdx = -1; }
    }

    private void cancel(Runnable r) { if (r != null) h.removeCallbacks(r); }

    // ════════════════════════════════════════════════════════════
    // Controle remoto D-Pad
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

    // ════════════════════════════════════════════════════════════
    // Ciclo de vida
    @Override protected void onPause() {
        super.onPause();
        if (main != null) main.setPlayWhenReady(false);
        // Pausar shadows para não gastar RAM
        if (shadowNext != null) shadowNext.setPlayWhenReady(false);
        if (shadowPrev != null) shadowPrev.setPlayWhenReady(false);
    }
    @Override protected void onResume() {
        super.onResume();
        if (main != null) main.setPlayWhenReady(true);
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        cancel(rKeep); cancel(rTimeout); cancel(rRetry); cancel(rShadow);
        releaseMain(); releaseAllShadows();
    }
}
