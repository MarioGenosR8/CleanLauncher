package com.simple.carlauncher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks what is playing from any source, manages offline/online synced lyrics (.lrc),
 * and routes controls back to the active media source.
 */
final class NowPlaying {

    enum Source { APP, LOCAL, BT }

    interface Listener {
        void onNowPlayingChanged();
    }

    private static final String LOCAL_PLAYER = "com.nwd.android.music.ui/.home_horizontalActivity";

    /** State reported by one source. */
    static final class Track {
        String title = "", artist = "";
        Bitmap art;
        boolean playing;
        long updated;
    }

    private static final class LyricLine implements Comparable<LyricLine> {
        final long timeMs;
        final String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        @Override
        public int compareTo(LyricLine o) {
            return Long.compare(this.timeMs, o.timeMs);
        }
    }

    private static NowPlaying instance;

    static NowPlaying get(Context c) {
        if (instance == null) instance = new NowPlaying(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Track app = new Track(), local = new Track(), bt = new Track();
    final BtClient btClient = new BtClient();

    private final MediaSessionManager sessions;
    private final ComponentName notificationListener;
    private MediaController controller;

    // --- Sincronización y Caché Offline de letras (LRCLIB + Memoria Interna) ---
    private String currentLyricKey = "";
    private List<LyricLine> lyricLines = Collections.emptyList();
    private long fallbackStartRealtime = 0;
    private long fallbackAccumulatedMs = 0;
    private boolean fallbackPlaying = false;

    private NowPlaying(Context c) {
        ctx = c;
        sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        notificationListener = new ComponentName(c, MediaListener.class);

        IntentFilter f = new IntentFilter();
        f.addAction("com.android.music.metachanged");
        f.addAction("com.android.music.playstatechanged");
        f.addAction("com.bt.ACTION_AVRCP_MUSIC_ID3");
        f.addAction("com.bt.ACTION_BT_MUSIC_PLAY");
        f.addAction("com.bt.ACTION_BT_MUSIC_PAUSE");
        f.addAction("com.bt.ACTION_A2DP_RELEASE");
        f.addAction("com.bt.ACTION_AVRCP_RELEASE");
        c.registerReceiver(receiver, f);

        try {
            sessions.addOnActiveSessionsChangedListener(list -> refreshSessions(), notificationListener);
        } catch (SecurityException ignored) {
            // Notification access not granted: app sessions are not visible.
        }
        refreshSessions();

        btClient.bind(c, () -> {
            bt.playing = btClient.isMusicPlaying();
            if (bt.playing) bt.updated = SystemClock.elapsedRealtime();
            btClient.queryId3();
            notifyListeners();
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            i.setExtrasClassLoader(NowPlaying.class.getClassLoader());
            try {
                handle(a, i);
            } catch (RuntimeException e) {
                android.util.Log.w("NowPlaying", "Ignoring broadcast " + a, e);
            }
        }

        private void handle(String a, Intent i) {
            long now = SystemClock.elapsedRealtime();
            switch (a) {
                case "com.android.music.metachanged":
                case "com.android.music.playstatechanged":
                    if (i.hasExtra("track")) local.title = nz(i.getStringExtra("track"));
                    if (i.hasExtra("artist")) local.artist = nz(i.getStringExtra("artist"));
                    local.playing = i.getBooleanExtra("playing", local.playing);
                    local.updated = now;
                    break;
                case "com.bt.ACTION_AVRCP_MUSIC_ID3":
                    bt.title = nz(i.getStringExtra("extra_avrcp_id3_title"));
                    bt.artist = nz(i.getStringExtra("extra_avrcp_id3_artist"));
                    if (!bt.title.isEmpty()) bt.updated = now;
                    break;
                case "com.bt.ACTION_BT_MUSIC_PLAY":
                    bt.playing = true;
                    bt.updated = now;
                    btClient.queryId3();
                    break;
                case "com.bt.ACTION_BT_MUSIC_PAUSE":
                    bt.playing = false;
                    break;
                default:
                    bt.playing = false;
                    bt.title = "";
                    bt.artist = "";
                    bt.updated = 0;
                    break;
            }
            notifyListeners();
        }
    };

    // ---------- App media sessions ----------

    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        public void onMetadataChanged(MediaMetadata m) { readSession(); }
        public void onPlaybackStateChanged(PlaybackState s) { readSession(); }
        public void onSessionDestroyed() { refreshSessions(); }
    };

    private void refreshSessions() {
        List<MediaController> list = null;
        try {
            list = sessions.getActiveSessions(notificationListener);
        } catch (SecurityException ignored) { }
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                if ("com.android.server.telecom".equals(c.getPackageName())) continue;
                if (pick == null) pick = c;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
        }
        if (controller != null) controller.unregisterCallback(sessionCallback);
        controller = pick;
        if (controller != null) controller.registerCallback(sessionCallback);
        readSession();
    }

    private void readSession() {
        if (controller == null) {
            app.title = "";
            app.artist = "";
            app.art = null;
            app.playing = false;
            app.updated = 0;
        } else {
            MediaMetadata m = controller.getMetadata();
            PlaybackState s = controller.getPlaybackState();
            app.title = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            app.artist = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            app.art = art;
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (playing || !app.title.isEmpty()) app.updated = SystemClock.elapsedRealtime();
            app.playing = playing;
        }
        notifyListeners();
    }

    // ---------- Lyrics (Offline Cache + LRCLIB) ----------

    private void updateLyricsState() {
        Source s = current();
        Track t = s == null ? null : track(s);
        if (t == null || t.title.isEmpty()) {
            currentLyricKey = "";
            lyricLines = Collections.emptyList();
            fallbackPlaying = false;
            fallbackAccumulatedMs = 0;
            return;
        }
        String key = (t.title + "|" + t.artist).toLowerCase().trim();
        long now = SystemClock.elapsedRealtime();
        if (!key.equals(currentLyricKey)) {
            currentLyricKey = key;
            lyricLines = Collections.emptyList();
            fallbackAccumulatedMs = 0;
            fallbackStartRealtime = now;
            fallbackPlaying = t.playing;
            fetchLyricsAsync(key, t.title, t.artist);
        } else {
            if (t.playing != fallbackPlaying) {
                if (t.playing) {
                    fallbackStartRealtime = now;
                } else {
                    fallbackAccumulatedMs += (now - fallbackStartRealtime);
                }
                fallbackPlaying = t.playing;
            }
        }
    }

    private void fetchLyricsAsync(final String expectedKey, final String title, final String artist) {
        new Thread(() -> {
            // 1. Buscar primero en la memoria interna del estéreo (Offline)
            String rawLrc = loadCachedLrc(title, artist);
            List<LyricLine> parsed = rawLrc != null ? parseLrc(rawLrc) : Collections.emptyList();

            // 2. Si no está guardada aún, descargar de internet y guardarla para siempre
            if (parsed.isEmpty()) {
                String downloaded = queryLrclibRaw(title, artist);
                if (downloaded == null) {
                    String clean = cleanTitle(title);
                    if (!clean.equalsIgnoreCase(title)) {
                        downloaded = queryLrclibRaw(clean, artist);
                    }
                }
                if (downloaded != null) {
                    parsed = parseLrc(downloaded);
                    if (!parsed.isEmpty()) {
                        saveCachedLrc(title, artist, downloaded);
                    }
                }
            }

            final List<LyricLine> result = parsed;
            mainHandler.post(() -> {
                if (expectedKey.equals(currentLyricKey)) {
                    lyricLines = result;
                    for (Listener l : new ArrayList<>(listeners)) l.onNowPlayingChanged();
                }
            });
        }).start();
    }

    private File getLyricsCacheDir() {
        File dir = new File(ctx.getFilesDir(), "lyrics_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String safeFileName(String title, String artist) {
        String raw = (cleanTitle(title) + "_" + artist).toLowerCase().trim();
        return raw.replaceAll("[^a-z0-9áéíóúñü_-]", "_") + ".lrc";
    }

    private String loadCachedLrc(String title, String artist) {
        // Revisar caché interna de la app
        File cached = new File(getLyricsCacheDir(), safeFileName(title, artist));
        if (cached.exists()) {
            String content = readFileText(cached);
            if (content != null && !content.isEmpty()) return content;
        }
        // Revisar si el usuario metió archivos .lrc en la carpeta /sdcard/Lyrics
        try {
            File extDir = new File(Environment.getExternalStorageDirectory(), "Lyrics");
            if (extDir.exists() && extDir.isDirectory()) {
                File f1 = new File(extDir, artist + " - " + title + ".lrc");
                if (f1.exists()) return readFileText(f1);
                File f2 = new File(extDir, title + ".lrc");
                if (f2.exists()) return readFileText(f2);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void saveCachedLrc(String title, String artist, String lrcContent) {
        File cached = new File(getLyricsCacheDir(), safeFileName(title, artist));
        try (FileOutputStream fos = new FileOutputStream(cached)) {
            fos.write(lrcContent.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private static String readFileText(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cleanTitle(String title) {
        return title.replaceAll("\\s*\\([^)]*\\)", "")
                .replaceAll("\\s*\\[[^]]*\\]", "")
                .replaceAll("\\s*-\\s*.*$", "")
                .trim();
    }

    private static String queryLrclibRaw(String title, String artist) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://lrclib.net/api/search?track_name="
                    + URLEncoder.encode(title, "UTF-8")
                    + "&artist_name=" + URLEncoder.encode(artist, "UTF-8");
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "CleanLauncher/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String synced = obj.optString("syncedLyrics", "");
                if (!synced.isEmpty() && !"null".equals(synced)) {
                    return synced;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> list = new ArrayList<>();
        String[] rows = lrc.split("\\r?\\n");
        for (String row : rows) {
            int open = row.indexOf('[');
            int close = row.indexOf(']');
            if (open < 0 || close <= open) continue;
            String stamp = row.substring(open + 1, close).trim();
            int colon = stamp.indexOf(':');
            if (colon <= 0) continue;
            try {
                long min = Long.parseLong(stamp.substring(0, colon).trim());
                double sec = Double.parseDouble(stamp.substring(colon + 1).replace(',', '.').trim());
                long ms = min * 60000L + Math.round(sec * 1000.0);
                String text = row.substring(close + 1).trim();
                if (text.isEmpty()) text = "♪  ♪  ♪";
                list.add(new LyricLine(ms, text));
            } catch (NumberFormatException ignored) { }
        }
        Collections.sort(list);
        return list;
    }

    private long currentPositionMs() {
        Source s = current();
        if (s == Source.APP && controller != null) {
            PlaybackState ps = controller.getPlaybackState();
            if (ps != null && ps.getPosition() != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                long pos = ps.getPosition();
                if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                    float speed = ps.getPlaybackSpeed() > 0f ? ps.getPlaybackSpeed() : 1f;
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * speed);
                }
                if (pos >= 0) return pos;
            }
        }
        long extra = fallbackPlaying ? (SystemClock.elapsedRealtime() - fallbackStartRealtime) : 0;
        return fallbackAccumulatedMs + extra;
    }

    /** Devuelve [versoActual, siguienteVerso] o null si la canción no tiene letra sincronizada. */
    String[] currentLyrics() {
        List<LyricLine> lines = lyricLines;
        if (lines == null || lines.isEmpty()) return null;
        long pos = currentPositionMs() + 250;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= pos) idx = i;
            else break;
        }
        if (idx < 0) {
            return new String[]{"♪  ♪  ♪", lines.get(0).text};
        }
        String curr = lines.get(idx).text;
        String next = (idx + 1 < lines.size()) ? lines.get(idx + 1).text : "";
        return new String[]{curr, next};
    }

    // ---------- Public API ----------

    Source current() {
        Source best = null;
        long bestTime = -1;
        for (Source s : Source.values()) {
            Track t = track(s);
            if (t.playing && t.updated > bestTime) { best = s; bestTime = t.updated; }
        }
        if (best != null) return best;
        for (Source s : Source.values()) {
            Track t = track(s);
            if (t.updated > 0 && t.updated > bestTime) { best = s; bestTime = t.updated; }
        }
        return best;
    }

    Track track(Source s) {
        return s == Source.APP ? app : s == Source.LOCAL ? local : bt;
    }

    String label(Source s) {
        if (s == Source.BT) return "Bluetooth";
        if (s == Source.LOCAL) return "USB";
        if (controller != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(controller.getPackageName(), 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return "Música";
    }

    void playPause() {
        Source s = current();
        if (s == Source.BT) {
            if (Ui.radioIsSource(ctx)) {
                Ui.userChoseRadioAt = 0;
                Ui.selectBluetooth(ctx);
                btClient.playControl(BtClient.PLAY);
            } else {
                btClient.playControl(bt.playing ? BtClient.PAUSE : BtClient.PLAY);
            }
        } else if (s == Source.APP && controller != null) {
            if (app.playing) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    void skip(boolean next) {
        Source s = current();
        if (s == Source.BT) {
            btClient.playControl(next ? BtClient.NEXT : BtClient.PREVIOUS);
        } else if (s == Source.APP && controller != null) {
            if (next) controller.getTransportControls().skipToNext();
            else controller.getTransportControls().skipToPrevious();
        } else {
            mediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    void open(Context c) {
        Source s = current();
        if (s == Source.BT) {
            c.startActivity(new Intent(c, BtActivity.class));
        } else if (s == Source.APP && controller != null) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
            if (i != null) c.startActivity(i);
        } else {
            Ui.launch(c, LOCAL_PLAYER);
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
        refreshSessions();
        if (btClient.isBound()) btClient.queryId3();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        updateLyricsState();
        for (Listener l : new ArrayList<>(listeners)) l.onNowPlayingChanged();
    }

    private void mediaKey(int keyCode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
