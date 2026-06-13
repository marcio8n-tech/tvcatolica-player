package com.tvcatolica.exoplayer;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.ui.PlayerView;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    private static final int  MAX_RETRY          = 10;
    private static final long KEEPALIVE_MS        = 25_000;
    private static final long UI_HIDE_MS          = 5_000;
    private static final long PREPARE_TIMEOUT_MS  = 18_000;

    private static final String[] NAMES = {
        "Paróquia Vianney","TV Família","Milênio TV",
        "Canção Nova","TV Católica 2","TV Católica",
        "TV Católica HD","Gospel Cartoon","Santa Cruz TV",
        "TV Horizonte","TV Padre Cícero","Rede Imaculada","TV Pai Eterno"
    };
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

    private int     idx        = 5; // TV Católica
    private int     retries    = 0;
    private boolean uiVisible  = false;

    private ExoPlayer    player;
    private PlayerView   playerView;
    private TextView     tvName, tvNum, tvLoad, tvRetry, tvErr;
    private View         loading, errorBox;
    private Button       btnRetry;
    private LinearLayout chList;
    private View         uiLayer;
    private AudioManager audio;

    private final Handler  h       = new Handler(Looper.getMainLooper());
    private       Runnable rKeep, rTimeout, rUi, rRetry;

    // ─────────────────────────────────────────────────────────────
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

        playerView = findViewById(R.id.surface_view);
        uiLayer    = findViewById(R.id.ui_layer);
        tvName     = findViewById(R.id.ch_name);
        tvNum      = findViewById(R.id.ch_num);
        tvLoad     = findViewById(R.id.load_txt);
        tvRetry    = findViewById(R.id.retry_txt);
        tvErr      = findViewById(R.id.err_msg);
        loading    = findViewById(R.id.loading_view);
        errorBox   = findViewById(R.id.error_layout);
        btnRetry   = findViewById(R.id.retry_button);
        chList     = findViewById(R.id.ch_list);

        playerView.setUseController(false);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        btnRetry.setOnClickListener(v -> { errorBox.setVisibility(View.GONE); loadCh(idx); });

        // Toque na tela
        playerView.setOnClickListener(v -> toggleUi());

        buildList();
        loadCh(idx);
        showUi();
    }

    // ─────────────────────────────────────────────────────────────
    private void loadCh(int i) {
        if (i < 0) i = NAMES.length - 1;
        if (i >= NAMES.length) i = 0;
        idx = i; retries = 0;

        cancel(rKeep); cancel(rTimeout); cancel(rRetry);
        errorBox.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        tvLoad.setText("Carregando " + NAMES[idx] + "...");
        tvRetry.setText("");
        tvName.setText(NAMES[idx]);
        tvNum.setText("Canal " + (idx+1) + " de " + NAMES.length);
        buildList();
        play(URLS[idx]);
    }

    private void play(String url) {
        if (player != null) { player.release(); player = null; }

        LoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 2_500, 5_000)
            .setTargetBufferBytes(10 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();

        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        playerView.setPlayer(player);

        HlsMediaSource src = new HlsMediaSource.Factory(
            new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(10_000)
                .setUserAgent("TVCatolica/2.0"))
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)));

        player.setMediaSource(src);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    retries = 0;
                    cancel(rTimeout);
                    keepalive();
                } else if (st == Player.STATE_ENDED) {
                    recover();
                }
            }
            @Override public void onPlayerError(PlaybackException e) { recover(); }
        });

        cancel(rTimeout);
        rTimeout = () -> { if (player == null || player.getPlaybackState() < Player.STATE_READY) recover(); };
        h.postDelayed(rTimeout, PREPARE_TIMEOUT_MS);
    }

    private void recover() {
        retries++;
        if (retries > MAX_RETRY) {
            loading.setVisibility(View.GONE);
            errorBox.setVisibility(View.VISIBLE);
            tvErr.setText("Sem sinal após " + MAX_RETRY + " tentativas.\nVerifique sua internet.");
            return;
        }
        long delay = Math.min(2000L * retries, 15_000L);
        loading.setVisibility(View.VISIBLE);
        tvRetry.setText("Tentativa " + retries + "/" + MAX_RETRY);
        cancel(rRetry);
        rRetry = () -> play(URLS[idx]);
        h.postDelayed(rRetry, delay);
    }

    private void keepalive() {
        cancel(rKeep);
        rKeep = () -> {
            if (player != null && !player.isPlaying()) recover();
            else keepalive();
        };
        h.postDelayed(rKeep, KEEPALIVE_MS);
    }

    // ─────────────────────────────────────────────────────────────
    private void buildList() {
        if (chList == null) return;
        chList.removeAllViews();
        for (int i = 0; i < NAMES.length; i++) {
            final int fi = i;
            TextView tv = new TextView(this);
            tv.setText((i+1) + ". " + NAMES[i]);
            tv.setTextColor(i == idx ? 0xFF000000 : 0xFFFFFFFF);
            tv.setBackgroundColor(i == idx ? 0xFFFFFFFF : 0x44000000);
            tv.setPadding(20, 12, 20, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> { loadCh(fi); hideUi(); });
            chList.addView(tv);
        }
    }

    private void showUi() {
        if (uiLayer == null) return;
        uiLayer.setVisibility(View.VISIBLE);
        uiVisible = true;
        cancel(rUi);
        rUi = this::hideUi;
        h.postDelayed(rUi, UI_HIDE_MS);
    }
    private void hideUi()   { if (uiLayer != null) uiLayer.setVisibility(View.GONE); uiVisible = false; }
    private void toggleUi() { if (uiVisible) hideUi(); else showUi(); }

    private void cancel(Runnable r) { if (r != null) h.removeCallbacks(r); }

    // ─────────────────────────────────────────────────────────────
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                loadCh(idx - 1); showUi(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                loadCh(idx + 1); showUi(); return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleUi(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (uiVisible) { hideUi(); return true; }
                return super.onKeyDown(code, e);
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onPause()  { super.onPause();  if (player != null) player.setPlayWhenReady(false); }
    @Override protected void onResume() { super.onResume(); if (player != null) player.setPlayWhenReady(true); }
    @Override protected void onDestroy(){
        super.onDestroy();
        cancel(rKeep); cancel(rTimeout); cancel(rUi); cancel(rRetry);
        if (player != null) { player.release(); player = null; }
    }
}
