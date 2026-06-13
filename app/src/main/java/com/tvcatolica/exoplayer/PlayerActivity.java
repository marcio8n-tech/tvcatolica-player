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

    // ── Canais ──────────────────────────────────────────────────
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

    // ── Configurações ────────────────────────────────────────────
    private static final int  MAX_RETRY      = 12;
    private static final long RETRY_BASE_MS  = 3_000;
    private static final long KEEPALIVE_MS   = 20_000;
    private static final long ARROWS_SHOW_MS = 2_500;  // tempo que as setas ficam visíveis
    private static final long TIMEOUT_MS     = 15_000;
    // Buffer leve para TV Box (4-6 Mbps)
    private static final int  BUF_MIN   = 4_000;
    private static final int  BUF_MAX   = 12_000;
    private static final int  BUF_PLAY  = 1_500;
    private static final int  BUF_REBUF = 3_000;

    // ── Estado ───────────────────────────────────────────────────
    private int idx     = 5;   // TV Católica como padrão
    private int retries = 0;

    // ── Players ──────────────────────────────────────────────────
    private ExoPlayer main, shadow;   // shadow = pré-carrega o canal vizinho

    // ── Views ────────────────────────────────────────────────────
    private PlayerView playerView;
    private View       btnPrev, btnNext, touchArea;

    // ── Handler ──────────────────────────────────────────────────
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable rArrows, rKeep, rTimeout, rRetry;

    private GestureDetector gesture;
    private AudioManager    audio;

    // ════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);

        // Tela cheia, sem barra de status, tela sempre ligada
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_main);
        btnPrev    = findViewById(R.id.btn_prev);
        btnNext    = findViewById(R.id.btn_next);
        touchArea  = findViewById(R.id.touch_area);

        // Desabilitar controller padrão do ExoPlayer (tela limpa)
        playerView.setUseController(false);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Gestos de swipe na área de toque
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                    if (dx < 0) goNext(); else goPrev();
                    return true;
                }
                return false;
            }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showArrows();
                return true;
            }
        });

        touchArea.setOnTouchListener((v, e) -> { gesture.onTouchEvent(e); return true; });

        btnPrev.setOnClickListener(v -> goPrev());
        btnNext.setOnClickListener(v -> goNext());

        // Carregar canal padrão e pré-carregar vizinhos
        loadChannel(idx, true);
    }

    // ════════════════════════════════════════════════════════════
    // Carrega um canal no player principal
    private void loadChannel(int i, boolean startShadow) {
        idx     = clamp(i);
        retries = 0;
        cancel(rRetry); cancel(rTimeout); cancel(rKeep);

        // Liberar shadow (será recriado depois)
        releaseShadow();

        // Iniciar player principal
        startMain(URLS[idx]);

        // Depois que o main estiver estável, pré-carregar próximo
        if (startShadow) {
            h.postDelayed(() -> preload(clamp(idx + 1)), 6_000);
        }
    }

    // ── Player principal ─────────────────────────────────────────
    private void startMain(String url) {
        releaseMain();
        main = buildPlayer(BUF_MIN, BUF_MAX, BUF_PLAY, BUF_REBUF, 8 * 1024 * 1024);
        playerView.setPlayer(main);

        main.setMediaSource(hlsSource(url));
        main.prepare();
        main.setPlayWhenReady(true);

        main.addListener(new Player.Listener() {
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

        // Timeout: se não abrir em TIMEOUT_MS, tenta de novo
        cancel(rTimeout);
        rTimeout = this::recover;
        h.postDelayed(rTimeout, TIMEOUT_MS);
    }

    // ── Shadow (pré-carregamento silencioso) ─────────────────────
    private void preload(int nextIdx) {
        releaseShadow();
        shadow = buildPlayer(2_000, 6_000, 1_000, 2_000, 3 * 1024 * 1024);
        shadow.setMediaSource(hlsSource(URLS[nextIdx]));
        shadow.prepare();
        shadow.setPlayWhenReady(false); // silencioso, só baixa os primeiros segmentos
    }

    // Troca para o shadow (transição instantânea) se disponível
    private void switchToShadow() {
        if (shadow == null) return;
        cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        releaseMain();
        main   = shadow;
        shadow = null;
        playerView.setPlayer(main);
        main.setPlayWhenReady(true);
        main.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) { cancel(rTimeout); retries = 0; keepalive(); }
                else if (st == Player.STATE_ENDED) { recover(); }
            }
            @Override public void onPlayerError(PlaybackException e) { recover(); }
        });
        retries = 0;
    }

    // ── Recuperação automática ───────────────────────────────────
    private void recover() {
        retries++;
        if (retries > MAX_RETRY) {
            // Esgotou tentativas — aguarda 30s e tenta do zero
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
            if (main != null && !main.isPlaying() && main.getPlaybackState() != Player.STATE_BUFFERING)
                recover();
            else
                keepalive();
        };
        h.postDelayed(rKeep, KEEPALIVE_MS);
    }

    // ── Navegação ────────────────────────────────────────────────
    private void goNext() {
        int next = clamp(idx + 1);
        // Se o shadow já pré-carregou esse canal, transição instantânea
        if (shadow != null) {
            switchToShadow();
            idx = next;
            h.postDelayed(() -> preload(clamp(idx + 1)), 6_000);
        } else {
            loadChannel(next, true);
        }
        showArrows();
    }

    private void goPrev() {
        loadChannel(clamp(idx - 1), true);
        showArrows();
    }

    // ── Setas (aparecem só quando o usuário interage) ────────────
    private void showArrows() {
        btnPrev.setVisibility(View.VISIBLE);
        btnNext.setVisibility(View.VISIBLE);
        cancel(rArrows);
        rArrows = () -> {
            btnPrev.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
        };
        h.postDelayed(rArrows, ARROWS_SHOW_MS);
    }

    // ── Construção de players e sources ─────────────────────────
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
                .setUserAgent("TVCatolica/4.0"))
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
    }

    private int clamp(int i) {
        if (i < 0) return URLS.length - 1;
        if (i >= URLS.length) return 0;
        return i;
    }

    // ── Liberação de players ─────────────────────────────────────
    private void releaseMain() {
        cancel(rKeep);
        if (main != null) { main.release(); main = null; }
    }
    private void releaseShadow() {
        if (shadow != null) { shadow.release(); shadow = null; }
    }
    private void cancel(Runnable r) { if (r != null) h.removeCallbacks(r); }

    // ── Controle remoto (D-Pad) ──────────────────────────────────
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
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showArrows(); return true;
            case KeyEvent.KEYCODE_BACK:
                finish(); return true;
        }
        return super.onKeyDown(code, e);
    }

    // ── Ciclo de vida ────────────────────────────────────────────
    @Override protected void onPause() {
        super.onPause();
        if (main != null) main.setPlayWhenReady(false);
    }
    @Override protected void onResume() {
        super.onResume();
        if (main != null) main.setPlayWhenReady(true);
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        cancel(rArrows); cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        releaseMain(); releaseShadow();
    }
}
