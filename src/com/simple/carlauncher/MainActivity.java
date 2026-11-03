package com.simple.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

public class MainActivity extends Activity implements NowPlaying.Listener {

    private static final String MAPS = "com.google.android.apps.maps/com.google.android.maps.MapsActivity";
    private static final String EQ = "com.nwd.audioset/.home_horizontalActivity";
    private static final String SETTINGS = "com.android.car.setting/.home_horizontalActivity";

    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000;

    private final Handler handler = new Handler();
    private final RadioClient radio = new RadioClient();
    private NowPlaying nowPlaying;

    private TextView greeting, weatherTemp, weatherDesc, weatherCity, weatherExtra;
    private ImageView weatherIcon;
    private GradientDrawable weatherBg;
    private ImageView car, carLights;
    private boolean hasLightsImage;
    private Boolean manualLights = null;
    private boolean currentLightsOn = false;
    private int maxBrightness = -1;

    private TextView oilInfo;
    private boolean createdDark;
    private String createdProfile;

    private String profileStamp() {
        return Profile.name(this) + "|" + Profile.carPhotoFile(this).lastModified();
    }
    private GradientDrawable oilBg;
    private long lastWeatherFetch;

    private TextView fmBand, fmFreq, fmUnit;
    private ImageView art, playBtn;
    private TextView songSource, songTitle, songArtist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(4));
        root.setBackground(Ui.background(this));

        LinearLayout cards = new LinearLayout(this);
        cards.addView(buildWeatherCard(), weight(1.2f, 0));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.addView(buildClockCard(), vweight(0.62f, 0));
        middle.addView(buildFmCard(), vweight(1.38f, dp(12)));
        cards.addView(middle, weight(1f, dp(12)));

        cards.addView(buildMusicCard(), weight(1.05f, dp(12)));
        root.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildDock(), new LinearLayout.LayoutParams(-1, dp(84)));
        setContentView(root);

        radio.bind(this);
        nowPlaying = NowPlaying.get(this);
        createdDark = Ui.isDark(this);
        createdProfile = profileStamp();
        if (!Profile.isSetupDone(this) && Profile.bundledCar(this, false) == 0) {
            startActivity(new Intent(this, SetupActivity.class));
        }
        if (Profile.isNwd(this)) startService(new Intent(this, VolumeOverlay.class));
        startService(new Intent(this, GreeterService.class));
        startService(new Intent(this, Odometer.class));
    }

    // ---------- Greeting + weather ----------

    private View buildWeatherCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        weatherBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Ui.pick(this, new int[]{0x993FA9FF, 0x667B4DFF}, new int[]{0xFF1E1E28, 0xFF121218}));
        weatherBg.setCornerRadius(dp(18));
        weatherBg.setStroke(dp(1), 0x447FD8FF);
        card.setBackground(weatherBg);

        TextView hello = left(Ui.text(this, Profile.hello(this), 34, Color.WHITE));
        hello.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(hello);

        greeting = left(Ui.text(this, "", 17, 0xCCFFFFFF));
        greeting.setPadding(0, dp(4), 0, 0);
        card.addView(greeting);

        FrameLayout carBox = new FrameLayout(this);
        car = new ImageView(this);
        car.setScaleType(ImageView.ScaleType.FIT_CENTER);
        carLights = new ImageView(this);
        carLights.setScaleType(ImageView.ScaleType.FIT_CENTER);
        carLights.setAlpha(0f);

        int bundled = Profile.bundledCar(this, false), bundledLights = Profile.bundledCar(this, true);
        Bitmap own = Profile.carPhoto(this);
        if (bundled != 0) {
            setTrimmedBundledCars(bundled, bundledLights);
        } else if (own != null) {
            car.setImageBitmap(trimSingleBitmap(own));
        } else {
            car.setImageResource(R.drawable.ic_car);
            car.setColorFilter(0x40FFFFFF);
        }

        carBox.addView(car, new FrameLayout.LayoutParams(-1, -1));
        carBox.addView(carLights, new FrameLayout.LayoutParams(-1, -1));

        // Un toque prende/apaga los faros del carro; mantener presionado abre la configuración
        carBox.setOnClickListener(v -> {
            if (hasLightsImage) {
                manualLights = !currentLightsOn;
                showCarLights(manualLights);
            } else {
                startActivity(new Intent(this, SetupActivity.class));
            }
        });
        carBox.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SetupActivity.class));
            return true;
        });

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.setMargins(0, dp(4), 0, dp(4));
        card.addView(carBox, clp);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(this);
        weatherIcon.setImageResource(R.drawable.ic_w_cloud);
        weatherIcon.setColorFilter(0xFFFFE07A);
        now.addView(weatherIcon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        weatherTemp = left(Ui.text(this, "--°", 50, Color.WHITE));
        weatherTemp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        weatherTemp.setIncludeFontPadding(false);
        weatherTemp.setPadding(dp(10), 0, dp(12), 0);
        now.addView(weatherTemp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        weatherDesc = left(Ui.text(this, "Cargando el clima…", 16, Color.WHITE));
        weatherDesc.setSingleLine(false);
        weatherDesc.setMaxLines(2);
        texts.addView(weatherDesc);
        weatherCity = left(Ui.text(this, "", 13, 0xB3FFFFFF));
        weatherCity.setPadding(0, dp(2), 0, 0);
        texts.addView(weatherCity);
        now.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(now);

        weatherExtra = left(Ui.text(this, "", 14, 0xE6FFFFFF));
        weatherExtra.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x26FFFFFF);
        chip.setCornerRadius(dp(20));
        weatherExtra.setBackground(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        card.addView(weatherExtra, lp);

        Ui.pressable(card, 0.98f);
        card.setOnClickListener(v -> fetchWeather(true));
        return card;
    }

    /** Recorta el espacio transparente sobrante manteniendo alineadas las 2 fotos (apagado y prendido). */
    private void setTrimmedBundledCars(int resOff, int resOn) {
        try {
            Bitmap bOff = BitmapFactory.decodeResource(getResources(), resOff);
            Bitmap bOn = resOn != 0 ? BitmapFactory.decodeResource(getResources(), resOn) : null;
            if (bOff == null) {
                car.setImageResource(resOff);
                return;
            }
            Rect rOff = findOpaqueBounds(bOff);
            if (bOn != null && bOn.getWidth() == bOff.getWidth() && bOn.getHeight() == bOff.getHeight()) {
                Rect rOn = findOpaqueBounds(bOn);
                Rect union = new Rect(
                        Math.min(rOff.left, rOn.left),
                        Math.min(rOff.top, rOn.top),
                        Math.max(rOff.right, rOn.right),
                        Math.max(rOff.bottom, rOn.bottom));
                car.setImageBitmap(Bitmap.createBitmap(bOff, union.left, union.top, union.width(), union.height()));
                carLights.setImageBitmap(Bitmap.createBitmap(bOn, union.left, union.top, union.width(), union.height()));
                hasLightsImage = true;
            } else {
                car.setImageBitmap(Bitmap.createBitmap(bOff, rOff.left, rOff.top, rOff.width(), rOff.height()));
                if (bOn != null) {
                    Rect rOn = findOpaqueBounds(bOn);
                    carLights.setImageBitmap(Bitmap.createBitmap(bOn, rOn.left, rOn.top, rOn.width(), rOn.height()));
                    hasLightsImage = true;
                }
            }
        } catch (Exception e) {
            car.setImageResource(resOff);
            if (resOn != 0) {
                carLights.setImageResource(resOn);
                hasLightsImage = true;
            }
        }
    }

    private Bitmap trimSingleBitmap(Bitmap b) {
        try {
            Rect r = findOpaqueBounds(b);
            return Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
        } catch (Exception e) {
            return b;
        }
    }

    private static Rect findOpaqueBounds(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int minX = w, minY = h, maxX = 0, maxY = 0;
        int step = Math.max(1, Math.min(w, h) / 150);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (Color.alpha(b.getPixel(x, y)) > 20) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return new Rect(0, 0, w, h);
        int padX = Math.max(2, (maxX - minX) / 30);
        int padY = Math.max(2, (maxY - minY) / 30);
        return new Rect(
                Math.max(0, minX - padX),
                Math.max(0, minY - padY),
                Math.min(w, maxX + padX),
                Math.min(h, maxY + padY));
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText((h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches")
                + " · Bienvenido, buen viaje");
    }

    private void showWeather(Weather w) {
        if (w == null) return;
        weatherIcon.setImageResource(w.icon());
        weatherIcon.setColorFilter(w.icon() == R.drawable.ic_w_sun ? 0xFFFFD25A
                : w.icon() == R.drawable.ic_w_moon ? 0xFFDDE4FF : Color.WHITE);
        weatherTemp.setText(w.temp + "°");
        weatherDesc.setText(w.description());
        weatherCity.setText(w.city.isEmpty() ? "Sensación " + w.feelsLike + "°"
                : w.city + " · Sensación " + w.feelsLike + "°");
        weatherExtra.setText("↑ " + w.max + "°   ↓ " + w.min + "°   💧 " + w.humidity + "%   " + w.wind + " km/h");
        weatherBg.setColors(Ui.isDark(this) ? new int[]{0xFF1E1E28, 0xFF121218}
                : w.isDay ? new int[]{0x993FA9FF, 0x667B4DFF} : new int[]{0x8C2A3A9E, 0x80261A6E});
        if (manualLights == null) showCarLights(carLightsOn());
    }

    private void fetchWeather(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastWeatherFetch != 0 && now - lastWeatherFetch < WEATHER_EVERY_MS) return;
        lastWeatherFetch = now;
        Weather.fetch(this, this::showWeather);
    }

    private final Runnable periodic = new Runnable() {
        public void run() {
            if (Ui.isDark(MainActivity.this) != createdDark) recreate();
            updateGreeting();
            fetchWeather(false);
            if (manualLights == null) showCarLights(carLightsOn());
            handler.postDelayed(this, 60 * 1000);
        }
    };

    // ---------- Car lights (ILL wire / Brightness / Night mode) ----------

    private final android.content.BroadcastReceiver illReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            manualLights = null;
            boolean on = i.getByteExtra("extra_ill_state", (byte) 0) != 0
                    || i.getBooleanExtra("ill_state", false)
                    || carLightsOn();
            showCarLights(on);
            handler.postDelayed(() -> { if (Ui.isDark(MainActivity.this) != createdDark) recreate(); }, 1500);
        }
    };

    private final ContentObserver settingsObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            if (manualLights == null) {
                showCarLights(carLightsOn());
            }
        }
    };

    /** Detecta las luces en NWD, en Gearelec AC8227L (bajada de brillo por cable ILL o modo noche) o de noche. */
    private boolean carLightsOn() {
        int nwdState = Settings.System.getInt(getContentResolver(), "mcu_no_source_device_state", 0);
        if ((nwdState & 2) != 0) return true;

        int illKey = Settings.System.getInt(getContentResolver(), "ill_state",
                Settings.System.getInt(getContentResolver(), "headlight_state", 0));
        if (illKey != 0) return true;

        int curBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        if (curBrightness > maxBrightness) maxBrightness = curBrightness;
        if (maxBrightness > 0 && curBrightness > 0 && curBrightness <= maxBrightness - 20) {
            return true;
        }

        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode == Configuration.UI_MODE_NIGHT_YES) return true;

        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 6 || Ui.isDark(this);
    }

    private void showCarLights(boolean on) {
        if (!hasLightsImage) return;
        currentLightsOn = on;
        carLights.animate().alpha(on ? 1f : 0f).setDuration(600).start();
        car.animate().alpha(on ? 0f : 1f).setDuration(600).start();
    }

    private void updateOil() {
        if (!Odometer.isConfigured(this)) {
            oilInfo.setText("Aceite: configurar");
            oilBg.setColor(0x26FFFFFF);
            return;
        }
        int left = Odometer.remainingKm(this);
        oilInfo.setText(left < 0 ? "Aceite: ¡ya toca el cambio!" : "Aceite: faltan " + OilActivity.fmt(left) + " km");
        oilBg.setColor(left < 0 ? 0xCCE53935 : left <= Odometer.WARN_KM ? 0xCCFF9800 : 0x26FFFFFF);
    }

    // ---------- Clock + Oil ----------

    private View buildClockCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.card(this));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("hh:mm");
        time.setTextSize(40);
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER);
        card.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextSize(14);
        date.setTextColor(0xCCFFFFFF);
        date.setPadding(0, dp(6), 0, 0);
        date.setGravity(Gravity.CENTER);
        card.addView(date);

        oilInfo = Ui.text(this, "", 13, Color.WHITE);
        oilInfo.setPadding(dp(16), dp(8), dp(16), dp(8));
        oilBg = new GradientDrawable();
        oilBg.setCornerRadius(dp(14));
        oilInfo.setBackground(oilBg);
        Ui.pressable(oilInfo, 0.92f);
        oilInfo.setOnClickListener(v -> startActivity(new Intent(this, OilActivity.class)));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.topMargin = dp(8);
        card.addView(oilInfo, olp);

        Ui.pressable(card, 0.97f);
        // Al tocar cualquier parte de la tarjeta del reloj/aceite se abre la pantalla de Aceite
        card.setOnClickListener(v -> startActivity(new Intent(this, OilActivity.class)));
        return card;
    }

    // ---------- FM ----------

    private View buildFmCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.card(this));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER);
        ImageView radioIcon = new ImageView(this);
        radioIcon.setImageResource(R.drawable.ic_radio);
        radioIcon.setColorFilter(0xFFFF9F5A);
        head.addView(radioIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        fmBand = Ui.text(this, "RADIO FM", 15, Color.WHITE);
        fmBand.setPadding(dp(8), 0, 0, 0);
        head.addView(fmBand);
        card.addView(head);

        boolean isNwd = Profile.isNwd(this);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(Ui.iconButton(this, R.drawable.ic_prev, 52, false, () -> stationStep(false)),
                new LinearLayout.LayoutParams(dp(52), dp(52)));
        fmFreq = Ui.text(this, isNwd ? "--.-" : "FM", 50, Color.WHITE);
        fmFreq.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(fmFreq, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(Ui.iconButton(this, R.drawable.ic_next, 52, false, () -> stationStep(true)),
                new LinearLayout.LayoutParams(dp(52), dp(52)));
        row.setPadding(dp(12), dp(10), dp(12), 0);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        fmUnit = Ui.text(this, isNwd ? "MHz" : "Toca para abrir", 15, 0x99FFFFFF);
        card.addView(fmUnit);

        Ui.pressable(card, 0.97f);
        card.setOnClickListener(v -> openRadio());
        return card;
    }

    private void stationStep(boolean next) {
        if (!Profile.isNwd(this)) {
            openRadio();
            return;
        }
        if (!Ui.radioIsSource(this)) {
            Ui.selectRadio(this, radio);
            handler.postDelayed(this::updateRadio, 2500);
            return;
        }
        radio.preset(next);
        handler.postDelayed(this::updateRadio, 300);
    }

    private void updateRadio() {
        RadioClient.Station st = radio.current();
        if (st == null) return;
        fmBand.setText(st.psName.isEmpty() ? "RADIO " + st.bandLabel() : st.bandLabel() + " · " + st.psName);
        fmFreq.setText(st.frequencyLabel());
        fmUnit.setText(st.isAm() ? "kHz" : "MHz");
    }

    private final Runnable radioPoll = new Runnable() {
        public void run() {
            updateRadio();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------- Music (any source) ----------

    private View buildMusicCard() {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Ui.pick(this, new int[]{0xFF6A3BFF, 0xFFC04DE8, 0xFF2B1A7A},
                        new int[]{0xFF2E1A55, 0xFF1A1030, 0xFF0A0A0E}));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setClipToOutline(true);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33000000, 0x00000000, 0xCC100A30}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        songSource = Ui.text(this, "MÚSICA", 12, Color.WHITE);
        songSource.setLetterSpacing(0.1f);
        songSource.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x40000000);
        chip.setCornerRadius(dp(14));
        songSource.setBackground(chip);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        slp.setMargins(dp(14), dp(14), 0, 0);
        card.addView(songSource, slp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), 0, dp(16), dp(18));
        songTitle = Ui.text(this, "", 21, Color.WHITE);
        songTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bottom.addView(songTitle);
        songArtist = Ui.text(this, "", 15, 0xCCFFFFFF);
        songArtist.setPadding(0, dp(2), 0, dp(14));
        bottom.addView(songArtist);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(Ui.iconButton(this, R.drawable.ic_prev, 50, false, () -> nowPlaying.skip(false)),
                new LinearLayout.LayoutParams(dp(50), dp(50)));
        playBtn = Ui.iconButton(this, R.drawable.ic_play, 66, true, () -> {
            NowPlaying.Source s = nowPlaying.current();
            if (s == null && !Profile.isNwd(this)) openMusic();
            else nowPlaying.playPause();
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(66), dp(66));
        plp.setMargins(dp(18), 0, dp(18), 0);
        controls.addView(playBtn, plp);
        controls.addView(Ui.iconButton(this, R.drawable.ic_next, 50, false, () -> nowPlaying.skip(true)),
                new LinearLayout.LayoutParams(dp(50), dp(50)));
        bottom.addView(controls);
        card.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        Ui.pressable(card, 0.98f);
        card.setOnClickListener(v -> openMusic());
        return card;
    }

    @Override
    public void onNowPlayingChanged() {
        NowPlaying.Source s = nowPlaying.current();
        NowPlaying.Track t = s == null ? null : nowPlaying.track(s);
        boolean empty = t == null || t.title.isEmpty();
        songSource.setText(s == null ? "MÚSICA" : nowPlaying.label(s).toUpperCase());
        songTitle.setText(empty ? "Nada sonando" : t.title);
        songArtist.setText(empty ? "Toca para abrir la música" : t.artist);
        playBtn.setImageResource(t != null && t.playing ? R.drawable.ic_pause : R.drawable.ic_play);

        if (t != null && t.art != null) {
            art.setPadding(0, 0, 0, 0);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.clearColorFilter();
            art.setAlpha(1f);
            art.setImageBitmap(t.art);
        } else {
            art.setPadding(dp(80), dp(56), dp(80), dp(170));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageResource(s == NowPlaying.Source.BT ? R.drawable.ic_bluetooth : R.drawable.ic_music);
            art.setColorFilter(Color.WHITE);
            art.setAlpha(0.25f);
        }
    }

    // ---------- Dock ----------

    private View buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.addView(dockIcon(R.drawable.ic_compass, this::openMaps), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_bluetooth, this::openBluetooth), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_radio, this::openRadio), weight(1f, 0));

        FrameLayout center = new FrameLayout(this);
        RingView ring = new RingView(this);
        center.addView(ring, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        Ui.pressable(ring, 0.88f);
        ring.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        dock.addView(center, weight(1f, 0));

        dock.addView(dockIcon(R.drawable.ic_w_moon,
                () -> startActivity(new Intent(this, ScreenOffActivity.class))), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_settings, this::openEqualizer), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_gear, this::openSettings), weight(1f, 0));
        return dock;
    }

    // ---------- Shortcuts: NWD screens, or the head unit's own apps elsewhere ----------

    private void openRadio() {
        if (Profile.isNwd(this)) {
            startActivity(new Intent(this, RadioActivity.class));
            return;
        }
        if (!launchByKeyword("radio fm", "fm radio", "radio", "fm", "fmradiomain", "sintonizador")) {
            toast("No se encontró la app de Radio");
        }
    }

    private void openMusic() {
        if (Profile.isNwd(this)) {
            nowPlaying.open(this);
            return;
        }
        if (!launchByKeyword("música", "musica", "music", "spotify", "reproductor", "player", "audio")) {
            nowPlaying.open(this);
        }
    }

    private void openBluetooth() {
        if (Profile.isNwd(this)) startActivity(new Intent(this, BtActivity.class));
        else if (!launchByKeyword("bluetooth", "bt music", "btmusic", "teléfono", "telefono", "bphone", "bt"))
            safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void openEqualizer() {
        if (Profile.isNwd(this)) {
            startActivity(new Intent(this, EqActivity.class));
            return;
        }
        Intent eq = new Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        if (!safeStart(eq) && !launchByKeyword("ecualizador", "equalizer", "dsp", "efecto", "sonido", "audio"))
            toast("No se encontró un ecualizador");
    }

    private void openSettings() {
        if (Profile.isNwd(this)) Ui.launch(this, SETTINGS);
        else safeStart(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openMaps() {
        Intent maps = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
        if (maps == null || !safeStart(maps)) {
            safeStart(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=")));
        }
    }

    /** Busca primero por el nombre exacto de la app e ignora Adobe/PDF/Navegadores. */
    private boolean launchByKeyword(String... keywords) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(main, 0);

        // 1. Buscar primero coincidencia en el nombre visible de la app
        for (String k : keywords) {
            for (android.content.pm.ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName.toLowerCase();
                if (pkg.equals(getPackageName().toLowerCase()) || isExcludedApp(pkg)) continue;
                String appName = String.valueOf(ri.loadLabel(pm)).toLowerCase().trim();
                if (appName.equals(k) || appName.contains(k)) {
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                    if (safeStart(i)) return true;
                }
            }
        }

        // 2. Si no coincidió el nombre visible, buscar en el nombre del paquete
        for (String k : keywords) {
            for (android.content.pm.ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName.toLowerCase();
                if (pkg.equals(getPackageName().toLowerCase()) || isExcludedApp(pkg)) continue;
                if (pkg.contains(k)) {
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                    if (safeStart(i)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isExcludedApp(String pkg) {
        return pkg.contains("adobe") || pkg.contains("acrobat") || pkg.contains("reader")
                || pkg.contains("pdf") || pkg.contains("chrome") || pkg.contains("vending")
                || pkg.contains("whatsapp") || pkg.contains("facebook");
    }

    private boolean safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private View dockIcon(int icon, Runnable action) {
        FrameLayout f = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(0xE6FFFFFF);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        f.addView(iv, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        Ui.pressable(iv, 0.85f);
        iv.setOnClickListener(v -> action.run());
        return f;
    }

    // ---------- Lifecycle & helpers ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.isDark(this) != createdDark || !profileStamp().equals(createdProfile)) {
            recreate();
            return;
        }
        updateGreeting();
        updateOil();
        showWeather(Weather.cached(this));

        IntentFilter illFilter = new IntentFilter("com.nwd.ACTION_ILL_STATE_CHANGE");
        illFilter.addAction("com.jancar.intent.action.ILL_STATE");
        illFilter.addAction("autochips.intent.action.ILL_STATE");
        registerReceiver(illReceiver, illFilter);
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver);

        boolean lights = hasLightsImage && (manualLights != null ? manualLights : carLightsOn());
        currentLightsOn = lights;
        carLights.setAlpha(lights ? 1f : 0f);
        car.setAlpha(lights ? 0f : 1f);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        handler.post(periodic);
        if (Profile.isNwd(this)) handler.post(radioPoll);
        nowPlaying.addListener(this);
        onNowPlayingChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(illReceiver); } catch (Exception ignored) { }
        try { getContentResolver().unregisterContentObserver(settingsObserver); } catch (Exception ignored) { }
        handler.removeCallbacks(periodic);
        handler.removeCallbacks(radioPoll);
        nowPlaying.removeListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) fetchWeather(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        radio.unbind(this);
    }

    private TextView left(TextView tv) {
        tv.setGravity(Gravity.START);
        return tv;
    }

    private LinearLayout.LayoutParams weight(float w, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        lp.leftMargin = left;
        return lp;
    }

    private LinearLayout.LayoutParams vweight(float w, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, w);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    static void pressable(View v) {
        Ui.pressable(v, 0.94f);
    }

    @Override
    public void onBackPressed() {
        // Home screen: nothing to go back to.
    }
}
