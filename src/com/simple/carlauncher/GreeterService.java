package com.simple.carlauncher;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class GreeterService extends Service {

    private static final String TAG = "Greeter";
    private static final long DELAY_MS = 20_000;
    private static final long MIN_INTERVAL_MS = 60_000;

    private static final int SRC_RADIO = 4, SRC_BT = -125, SRC_USB = -128;

    private final Handler handler = new Handler();
    private final List<Integer> queue = new ArrayList<>();
    private final RadioClient radio = new RadioClient();
    private final SoundClient sound = new SoundClient();
    private MediaPlayer player;
    private boolean waitingForPhone;
    private boolean btPlaying;

    @Override
    public void onCreate() {
        super.onCreate();
        radio.bind(this);
        sound.bind(this, () -> handler.post(() -> EqAuto.apply(this, sound)));
        IntentFilter f = new IntentFilter();
        f.addAction("com.nwd.action.ACTION_CHANGE_SOURCE");
        f.addAction("com.nwd.action.ACTION_VOLUME_STATE_CHANGE");
        f.addAction("com.nwd.action.ACTION_MCU_STATE_CHANGE");
        f.addAction("com.bt.ACTION_A2DP_ESTABLISHED");
        f.addAction("com.bt.ACTION_AVRCP_ESTABLISHED");
        f.addAction("com.bt.ACTION_BT_MUSIC_PLAY");
        f.addAction("com.bt.ACTION_BT_MUSIC_PAUSE");
        f.addAction("com.simple.carlauncher.TEST_CAR_ON");
        registerReceiver(events, f);
        if (android.os.SystemClock.elapsedRealtime() < 3 * 60_000) {
            handler.postDelayed(this::restoreAudio, 4000);
            schedule();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if ("com.nwd.action.ACTION_CHANGE_SOURCE".equals(a)) {
                handler.postDelayed(() -> EqAuto.apply(GreeterService.this, sound), 1500);
                return;
            }
            if ("com.nwd.action.ACTION_VOLUME_STATE_CHANGE".equals(a)) {
                handler.postDelayed(() -> EqAuto.applyLoudness(GreeterService.this, sound), 300);
                return;
            }
            if ("com.nwd.action.ACTION_MCU_STATE_CHANGE".equals(a)) {
                int state = i.getByteExtra("extra_mcu_state", (byte) -1);
                Log.d(TAG, "mcu_state=" + state);
                if (state == 3) onCarOff();
                else if (state == 1) onCarOn();
            } else if ("com.simple.carlauncher.TEST_CAR_ON".equals(a)) {
                onCarOn();
            } else if ("com.bt.ACTION_BT_MUSIC_PAUSE".equals(a)) {
                btPlaying = false;
            } else if ("com.bt.ACTION_BT_MUSIC_PLAY".equals(a)) {
                waitingForPhone = false;
                boolean started = !btPlaying;
                btPlaying = true;
                long sinceRadio = android.os.SystemClock.elapsedRealtime() - Ui.userChoseRadioAt;
                if (started && Ui.radioIsSource(GreeterService.this) && sinceRadio > 30_000) {
                    Log.d(TAG, "BT music started over the radio, switching to Bluetooth");
                    Ui.selectBluetooth(GreeterService.this);
                }
            } else if (waitingForPhone) {
                waitingForPhone = false;
                handler.postDelayed(() -> startBluetoothMusic(), 2500);
            }
        }
    };

    private void onCarOff() {
        int src = android.provider.Settings.System.getInt(getContentResolver(), "mcu_current_source", 0);
        NowPlaying np = NowPlaying.get(this);
        boolean btPlaying = np.track(NowPlaying.Source.BT).playing;
        if (btPlaying) src = SRC_BT;
        prefs().edit().putInt("last_source", src).apply();
        Log.d(TAG, "car off, last source=" + src);
    }

    private void onCarOn() {
        handler.postDelayed(this::restoreAudio, 3000);
        schedule();
    }

    private void restoreAudio() {
        if (!Profile.isNwd(this)) return;
        int src = prefs().getInt("last_source",
                android.provider.Settings.System.getInt(getContentResolver(), "mcu_current_source", 0));
        Log.d(TAG, "restore audio source=" + src);
        if (NowPlaying.get(this).track(NowPlaying.Source.BT).playing) {
            Ui.selectBluetooth(this);
        } else if (src == SRC_RADIO) {
            Ui.selectRadio(this, radio);
            Ui.userChoseRadioAt = 0;
        } else if (src == SRC_BT) {
            if (NowPlaying.get(this).btClient.isPhoneConnected()) startBluetoothMusic();
            else waitingForPhone = true;
        } else if (src == SRC_USB) {
            Ui.requestSource(this, Ui.SOURCE_MUSIC);
        }
    }

    private void startBluetoothMusic() {
        Ui.selectBluetooth(this);
        handler.postDelayed(() -> NowPlaying.get(this).btClient.playControl(BtClient.PLAY), 1500);
    }

    // ---------- Greeting ----------

    private void schedule() {
        long last = prefs().getLong("last_greet", 0);
        if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return;
        handler.removeCallbacks(greet);
        handler.postDelayed(greet, DELAY_MS);
        Weather.fetch(this, w -> { });
    }

    private final Runnable greet = () -> {
        if (!Profile.voiceEnabled(this)) return;
        prefs().edit().putLong("last_greet", System.currentTimeMillis()).apply();
        List<String> clips = buildMessage();
        speakTts(Speech.text(this, clips));
    };

    private android.speech.tts.TextToSpeech tts;

    private void speakTts(String text) {
        if (tts != null) tts.shutdown();
        tts = new android.speech.tts.TextToSpeech(this, status -> {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) return;
            java.util.Locale es = new java.util.Locale("es", "MX");
            if (tts.isLanguageAvailable(es) < 0) es = new java.util.Locale("es", "US");
            if (tts.isLanguageAvailable(es) < 0) es = new java.util.Locale("es");
            tts.setLanguage(es);
            Bundle params = new Bundle();
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "greet");
        });
    }

    private List<String> buildMessage() {
        List<String> clips = new ArrayList<>();
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_WEEK);
        int weekOfMonth = now.get(Calendar.DAY_OF_MONTH - 1) / 7 + 1;
        int h = now.get(Calendar.HOUR_OF_DAY);

        if (day == Calendar.MONDAY) clips.add("v_greet_monday");
        else clips.add(h < 12 ? "v_greet_morning" : h < 19 ? "v_greet_afternoon" : "v_greet_night");

        Weather w = Weather.cached(this);
        if (w != null) {
            clips.add("v_cond_" + conditionClip(w.code));
            int t = Math.max(-5, Math.min(40, w.temp));
            clips.add(t < 0 ? "v_temp_m" + (-t) : "v_temp_" + t);
        }
        if (day == Calendar.MONDAY) clips.add("v_maintenance");
        if (Odometer.isConfigured(this)) {
            int left = Odometer.remainingKm(this);
            if (left < 0) clips.add("v_oil_due");
            else if (left <= Odometer.WARN_KM) clips.add("v_oil_soon");
        }
        clips.add("v_seatbelt");

        // Hoy No Circula: Jueves y 1er / 3er Sábado del mes
        int nthSaturday = (now.get(Calendar.DAY_OF_MONTH) - 1) / 7 + 1;
        boolean isNoCirculaDay = (day == Calendar.THURSDAY)
                || (day == Calendar.SATURDAY && (nthSaturday == 1 || nthSaturday == 3));
        if (isNoCirculaDay && Profile.picoPlacaEnabled(this)) {
            clips.add("v_picoplaca");
        }

        clips.add("v_careful");
        return clips;
    }

    private static String conditionClip(int code) {
        if (code == 0) return "clear";
        if (code == 1) return "mostly";
        if (code == 2) return "partly";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code >= 95) return "storm";
        return "cloudy";
    }

    void speak(List<String> clips) {
        queue.clear();
        for (String name : clips) {
            int id = getResources().getIdentifier(name, "raw", getPackageName());
            if (id != 0) queue.add(id);
        }
        Log.d(TAG, "speaking " + clips);
        playNext();
    }

    private void playNext() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (queue.isEmpty()) return;
        int id = queue.remove(0);
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(this, android.net.Uri.parse(
                    "android.resource://" + getPackageName() + "/" + id));
            player.setOnCompletionListener(mp -> playNext());
            player.setOnErrorListener((mp, what, extra) -> {
                playNext();
                return true;
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.w(TAG, "clip failed", e);
            playNext();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("greeter", MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
        unregisterReceiver(events);
        radio.unbind(this);
        sound.unbind(this);
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            c.startService(new Intent(c, GreeterService.class));
            c.startService(new Intent(c, VolumeOverlay.class));
            c.startService(new Intent(c, Odometer.class));
        }
    }
}
