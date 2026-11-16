package de.repzaehler;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    // Farben (wie in der Web-Version)
    static final int BG = 0xFF0E1116, CARD = 0xFF171B23, CARD2 = 0xFF1E232D, LINE = 0xFF2A303C;
    static final int TEXT = 0xFFEEF1F6, MUTED = 0xFF8B93A5;
    static final int UP = 0xFF3DDC84, DOWN = 0xFF4AA8FF, WARN = 0xFFFFB020, BAD = 0xFFFF5D5D;

    static final int IDLE = 0, COUNTDOWN = 1, RUNNING = 2;
    static final double COUNTDOWN_S = 3;
    static final int REQ_SAVE = 7;
    static final int REC_MAX = 150000;

    private final RepDetector det = new RepDetector();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private Sensor accSensor, gravSensor;
    private SharedPreferences prefs;
    private ToneGenerator tone;
    private Vibrator vibrator;

    private int mode = IDLE;
    private long cdEnd;                 // Ende des Countdowns (elapsedRealtime, ms)
    private long runStart;              // Beginn der Messung (elapsedRealtime, ms)
    private boolean registered;
    private final double[] grav = new double[3];
    private boolean haveGrav;
    private long evCount, firstEvNs, lastEvNs;
    private long t0Ns;
    private final ArrayList<float[]> rec = new ArrayList<>();   // t, aig xyz, Schwerkraft xyz
    private boolean recHasGrav;
    private int shownRev = -1;
    private boolean everRan;

    // Verlauf für das Diagramm: [t, v, Richtung]
    private final ArrayList<double[]> hist = new ArrayList<>();

    // Anzeige
    private TextView status, num, phaseName, phaseTime, setTime, banner, sensorInfo, sensLabel;
    private TextView lastUp, lastDown, lastTotal, avgUp, avgDown, avgTotal;
    private LinearLayout phaseBox, rows;
    private Button mainBtn, newSetBtn;
    private ChartView chart;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("rz", MODE_PRIVATE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gravSensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); } catch (RuntimeException e) { tone = null; }
        det.minDisp = prefs.getInt("minDisp", 10) / 100.0;

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        updateButtons();
    }

    // ------------------------------------------------------------------ Oberfläche

    private int dp(float x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private GradientDrawable box(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.topMargin = dp(topDp);
        return p;
    }

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT, WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private LinearLayout card(int orientation, int padDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(orientation);
        l.setBackground(box(CARD, LINE, 18));
        l.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        return l;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.GERMANY), 12, MUTED, false);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    private TextView[] tile(LinearLayout parent, String label, int color, boolean first) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setBackground(box(CARD2, LINE, 14));
        t.setPadding(dp(6), dp(10), dp(6), dp(10));
        TextView k = text(label, 12, MUTED, false); k.setGravity(Gravity.CENTER);
        TextView v = text("–", 24, color, true); v.setGravity(Gravity.CENTER);
        TextView s = text("Ø –", 11, MUTED, false); s.setGravity(Gravity.CENTER);
        t.addView(k); t.addView(v); t.addView(s);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1);
        if (!first) p.leftMargin = dp(8);
        parent.addView(t, p);
        return new TextView[]{v, s};
    }

    private LinearLayout settingRow(String title, String sub, View control) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(title, 15, TEXT, false));
        if (sub != null) l.addView(text(sub, 12.5f, MUTED, false));
        r.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (control != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
            p.leftMargin = dp(12);
            r.addView(control, p);
        }
        return r;
    }

    private Button button(String s, int fill, int textColor, float sp) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextSize(sp); b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(box(fill, fill == CARD2 ? LINE : 0, 16));
        b.setStateListAnimator(null);
        b.setPadding(dp(18), dp(16), dp(18), dp(16));
        return b;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(8));
        scroll.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));

        // Kopfzeile
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Rep-Zähler", 17, TEXT, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        status = text("Bereit", 13, MUTED, false);
        status.setBackground(box(CARD2, 0, 99));
        status.setPadding(dp(11), dp(6), dp(11), dp(6));
        head.addView(status);
        col.addView(head);

        banner = text("", 14, 0xFFFFB3B3, false);
        banner.setBackground(box(0x1AFF5D5D, 0x80FF5D5D, 14));
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        banner.setVisibility(View.GONE);
        col.addView(banner, lp(MATCH, WRAP, 0, 12));

        // Zähler
        LinearLayout cnt = card(LinearLayout.VERTICAL, 16);
        cnt.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView cl = text("WIEDERHOLUNGEN", 12, MUTED, false); cl.setLetterSpacing(0.15f);
        num = text("0", 104, TEXT, true);
        num.setIncludeFontPadding(false);
        cnt.addView(cl); cnt.addView(num);
        col.addView(cnt, lp(MATCH, WRAP, 0, 12));

        // Laufende Phase
        phaseBox = new LinearLayout(this);
        phaseBox.setGravity(Gravity.CENTER_VERTICAL);
        phaseBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        phaseBox.setMinimumHeight(dp(62));
        phaseName = text("Warte auf Start", 16, MUTED, false);
        phaseTime = text("", 30, MUTED, true);
        phaseBox.addView(phaseName, new LinearLayout.LayoutParams(0, WRAP, 1));
        phaseBox.addView(phaseTime);
        col.addView(phaseBox, lp(MATCH, WRAP, 0, 12));

        // Satzdauer
        LinearLayout set = card(LinearLayout.HORIZONTAL, 12);
        set.setGravity(Gravity.CENTER_VERTICAL);
        set.addView(text("Satzdauer (Zeit unter Spannung)", 14, MUTED, false), new LinearLayout.LayoutParams(0, WRAP, 1));
        setTime = text("0,0 s", 22, TEXT, true);
        set.addView(setTime);
        col.addView(set, lp(MATCH, WRAP, 0, 12));

        // Letzte Wiederholung
        col.addView(section("Letzte Wiederholung"), lp(MATCH, WRAP, 0, 14));
        LinearLayout tiles = new LinearLayout(this);
        TextView[] a = tile(tiles, "▲ Hoch", UP, true); lastUp = a[0]; avgUp = a[1];
        a = tile(tiles, "▼ Runter", DOWN, false); lastDown = a[0]; avgDown = a[1];
        a = tile(tiles, "Gesamt", TEXT, false); lastTotal = a[0]; avgTotal = a[1];
        col.addView(tiles, lp(MATCH, WRAP, 0, 6));

        // Diagramm
        col.addView(section("Sensor-Verlauf (Geschwindigkeit)"), lp(MATCH, WRAP, 0, 14));
        chart = new ChartView(this);
        chart.setBackground(box(CARD2, LINE, 12));
        col.addView(chart, lp(MATCH, dp(92), 0, 6));

        // Tabelle
        col.addView(section("Alle Wiederholungen"), lp(MATCH, WRAP, 0, 14));
        rows = card(LinearLayout.VERTICAL, 10);
        col.addView(rows, lp(MATCH, WRAP, 0, 6));

        // Einstellungen
        col.addView(section("Einstellungen"), lp(MATCH, WRAP, 0, 14));
        LinearLayout st = card(LinearLayout.VERTICAL, 16);
        st.setPadding(dp(16), dp(4), dp(16), dp(4));

        LinearLayout sens = new LinearLayout(this);
        sens.setOrientation(LinearLayout.VERTICAL);
        sens.setPadding(0, dp(10), 0, dp(10));
        LinearLayout sh = new LinearLayout(this);
        sh.addView(text("Empfindlichkeit", 15, TEXT, false), new LinearLayout.LayoutParams(0, WRAP, 1));
        sensLabel = text("", 13, MUTED, false);
        sh.addView(sensLabel);
        sens.addView(sh);
        sens.addView(text("Mindest-Bewegungsweg pro Hoch/Runter. Kleiner = empfindlicher, aber anfälliger für Wackler.", 12.5f, MUTED, false));
        SeekBar bar = new SeekBar(this);
        bar.setMax(25);
        bar.setProgress(prefs.getInt("minDisp", 10) - 5);
        sensLabel.setText((bar.getProgress() + 5) + " cm");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                int cm = v + 5;
                sensLabel.setText(cm + " cm");
                det.minDisp = cm / 100.0;
                prefs.edit().putInt("minDisp", cm).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sens.addView(bar, lp(MATCH, WRAP, 0, 6));
        st.addView(sens);

        Switch swSound = new Switch(this);
        swSound.setChecked(prefs.getBoolean("sound", true));
        swSound.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("sound", on).apply());
        st.addView(settingRow("Ton bei jeder Wiederholung", null, swSound));

        Switch swVib = new Switch(this);
        swVib.setChecked(prefs.getBoolean("vib", true));
        swVib.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("vib", on).apply());
        st.addView(settingRow("Vibration bei jeder Wiederholung", null, swVib));

        Button save = button("Speichern", CARD2, TEXT, 14);
        save.setPadding(dp(12), dp(8), dp(12), dp(8));
        save.setOnClickListener(v -> saveRecording());
        st.addView(settingRow("Messdaten speichern", "Speichert die Sensor-Rohdaten des letzten Laufs als Datei – hilfreich, falls die Erkennung nicht passt.", save));

        sensorInfo = text("Wird beim Start geprüft.", 12.5f, MUTED, false);
        LinearLayout si = new LinearLayout(this);
        si.setOrientation(LinearLayout.VERTICAL);
        si.setPadding(0, dp(10), 0, dp(10));
        si.addView(text("Sensor", 15, TEXT, false));
        si.addView(sensorInfo);
        st.addView(si);
        col.addView(st, lp(MATCH, WRAP, 0, 6));

        TextView foot = text("Handy flach und fest auf die Hantel bzw. Hantelscheibe legen (rutschfest). Start drücken, nach dem Countdown kurz ruhig halten, dann den Satz machen.", 12, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, lp(MATCH, WRAP, 0, 12));

        // Knöpfe unten
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(dp(14), dp(8), dp(14), dp(14));
        mainBtn = button("Start", UP, 0xFF07220F, 19);
        mainBtn.setOnClickListener(v -> { if (mode == IDLE) start(); else stop(); });
        newSetBtn = button("Neuer Satz", CARD2, TEXT, 16);
        newSetBtn.setOnClickListener(v -> { det.resetSet(); hist.clear(); shownRev = -1; });
        btns.addView(mainBtn, new LinearLayout.LayoutParams(0, WRAP, 1));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(WRAP, WRAP);
        p2.leftMargin = dp(10);
        btns.addView(newSetBtn, p2);
        root.addView(btns);

        renderRows();
        return root;
    }

    private void showBanner(String msg) {
        if (msg == null || msg.isEmpty()) banner.setVisibility(View.GONE);
        else { banner.setText(msg); banner.setVisibility(View.VISIBLE); }
    }

    private void updateButtons() {
        if (mode == IDLE) {
            mainBtn.setText(everRan ? "Neuer Start" : "Start");
            mainBtn.setBackground(box(UP, 0, 16)); mainBtn.setTextColor(0xFF07220F);
        } else if (mode == COUNTDOWN) {
            mainBtn.setText("Abbrechen");
            mainBtn.setBackground(box(WARN, 0, 16)); mainBtn.setTextColor(0xFF2A1C00);
        } else {
            mainBtn.setText("Stopp");
            mainBtn.setBackground(box(BAD, 0, 16)); mainBtn.setTextColor(0xFFFFFFFF);
        }
        newSetBtn.setEnabled(mode == RUNNING);
        newSetBtn.setAlpha(mode == RUNNING ? 1f : 0.4f);
    }

    // ------------------------------------------------------------------ Ablauf

    private void start() {
        showBanner("");
        if (accSensor == null) { showBanner("Dieses Gerät hat keinen Beschleunigungssensor."); return; }
        det.reset(); hist.clear(); shownRev = -1;
        mode = COUNTDOWN;
        cdEnd = SystemClock.elapsedRealtime() + (long) (COUNTDOWN_S * 1000);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
    }

    private void beginRun() {
        mode = RUNNING; everRan = true;
        det.reset(); hist.clear(); shownRev = -1;
        rec.clear(); evCount = 0; haveGrav = false; recHasGrav = gravSensor != null;
        runStart = SystemClock.elapsedRealtime();
        // 5000 µs = 200 Werte pro Sekunde (mehr gibt Android ohne Sonderrecht nicht her)
        sm.registerListener(this, accSensor, 5000);
        if (gravSensor != null) sm.registerListener(this, gravSensor, 5000);
        registered = true;
        beep();
        updateButtons();
    }

    private void stop() {
        if (registered) { sm.unregisterListener(this); registered = false; }
        mode = IDLE;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
    }

    private void beep() {
        if (tone != null && prefs.getBoolean("sound", true)) {
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120); } catch (RuntimeException e) { /* egal */ }
        }
    }

    private void buzz() {
        if (vibrator != null && prefs.getBoolean("vib", true)) {
            try { vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)); } catch (RuntimeException e) { /* egal */ }
        }
    }

    // ------------------------------------------------------------------ Sensoren

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (mode != RUNNING) return;
        if (e.sensor.getType() == Sensor.TYPE_GRAVITY) {
            grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2];
            haveGrav = true;
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        // Kurz auf den ersten Wert des Lagesensors warten, damit von Anfang an gleich gerechnet wird
        if (gravSensor != null && !haveGrav && SystemClock.elapsedRealtime() - runStart < 600) return;
        if (evCount == 0) { firstEvNs = e.timestamp; t0Ns = e.timestamp; recHasGrav = haveGrav; }
        evCount++; lastEvNs = e.timestamp;
        double t = (e.timestamp - t0Ns) * 1e-9;
        double[] aig = {e.values[0], e.values[1], e.values[2]};
        double[] lin = null;
        if (recHasGrav) lin = new double[]{aig[0] - grav[0], aig[1] - grav[1], aig[2] - grav[2]};
        if (rec.size() < REC_MAX) {
            rec.add(new float[]{(float) t, e.values[0], e.values[1], e.values[2], (float) grav[0], (float) grav[1], (float) grav[2]});
        }
        int n = det.push(t, aig, lin);
        if (n > 0) { beep(); buzz(); }
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }

    // ------------------------------------------------------------------ Anzeige

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 50);
        }
    };

    @Override protected void onResume() { super.onResume(); handler.post(tick); }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        if (mode != IDLE) stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tone != null) { tone.release(); tone = null; }
    }

    private static String f1(double x) { return String.format(Locale.GERMANY, "%.1f", x); }
    private static String fs(double x) { return Double.isNaN(x) ? "–" : f1(x) + " s"; }
    private static String fset(double x) {
        if (x >= 60) { int m = (int) (x / 60); double s = x - m * 60; return m + ":" + (s < 10 ? "0" : "") + f1(s); }
        return f1(x) + " s";
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        if (mode == COUNTDOWN && now >= cdEnd) beginRun();

        boolean calibrating = mode == RUNNING && !det.calibrated;
        int live = mode == RUNNING ? det.liveDir() : 0;

        // Status
        if (mode == IDLE) { status.setText(everRan ? "Gestoppt" : "Bereit"); status.setTextColor(MUTED); }
        else if (mode == COUNTDOWN) {
            status.setText("Handy auflegen … " + Math.max(1, (int) Math.ceil((cdEnd - now) / 1000.0)));
            status.setTextColor(WARN);
        } else if (calibrating) { status.setText("Kalibriere – nicht bewegen"); status.setTextColor(WARN); }
        else { status.setText(live != 0 ? "Bewegung erkannt" : "Bereit – los geht's"); status.setTextColor(UP); }

        if (mode == RUNNING && evCount == 0 && now - runStart > 2500) {
            showBanner("Kein Sensor-Signal. Bitte in den Android-Einstellungen prüfen, ob die App auf Sensoren zugreifen darf (Apps → Rep-Zähler → Berechtigungen → Sensoren).");
        }
        if (mode == RUNNING && evCount > 40) {
            long hz = Math.round((evCount - 1) / Math.max(1e-3, (lastEvNs - firstEvNs) * 1e-9));
            sensorInfo.setText("Aktiv, ca. " + hz + " Werte pro Sekunde, " + (recHasGrav ? "mit Lagesensor" : "ohne Lagesensor")
                    + (det.qz > 0 ? ", Werte gerundet" : ", ungerundet"));
        }

        // Zähler und Tabelle
        if (det.rev != shownRev) {
            shownRev = det.rev;
            num.setText(String.valueOf(det.reps.size()));
            renderRows();
        }

        // Laufende Phase
        if (live != 0) {
            int c = live > 0 ? UP : DOWN;
            phaseBox.setBackground(box((c & 0x00FFFFFF) | 0x21000000, (c & 0x00FFFFFF) | 0x8C000000, 14));
            phaseName.setText(live > 0 ? "▲ HOCH" : "▼ RUNTER");
            phaseName.setTextColor(c); phaseName.setTextSize(20); phaseName.setTypeface(Typeface.DEFAULT_BOLD);
            phaseTime.setText(fs(Math.max(0, det.liveElapsed()))); phaseTime.setTextColor(c);
        } else {
            phaseBox.setBackground(box(CARD2, LINE, 14));
            String s = mode == IDLE ? (det.reps.isEmpty() ? "Warte auf Start" : "Satz beendet")
                    : (mode == COUNTDOWN || calibrating) ? "Gleich geht's los …" : "Warte auf Bewegung";
            phaseName.setText(s);
            phaseName.setTextColor(MUTED); phaseName.setTextSize(16); phaseName.setTypeface(Typeface.DEFAULT);
            phaseTime.setText("");
        }

        // Satzdauer (Zeiten des Sensors, nicht der Uhr)
        double sd = 0;
        if (det.hasSet) {
            boolean active = mode == RUNNING && (live != 0 || det.t - det.lastEnd < 4);
            sd = (active ? det.t : det.lastEnd) - det.setStart;
        }
        setTime.setText(fset(Math.max(0, sd)));

        // Diagramm
        double tn = now / 1000.0;
        if (mode == RUNNING && !calibrating) {
            hist.add(new double[]{tn, det.v, live});
            while (!hist.isEmpty() && tn - hist.get(0)[0] > 11) hist.remove(0);
        }
        chart.now = tn;
        chart.invalidate();
    }

    private TextView cell(String s, int color, float weight, int gravity) {
        TextView t = text(s, 15, color, false);
        t.setGravity(gravity);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, weight));
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    private void renderRows() {
        rows.removeAllViews();
        ArrayList<RepDetector.Rep> reps = det.reps;
        if (reps.isEmpty()) {
            TextView e = text("Noch keine Wiederholung.", 14, MUTED, false);
            e.setGravity(Gravity.CENTER); e.setPadding(0, dp(8), 0, dp(8));
            rows.addView(e);
            lastUp.setText("–"); lastDown.setText("–"); lastTotal.setText("–");
            avgUp.setText("Ø –"); avgDown.setText("Ø –"); avgTotal.setText("Ø –");
            return;
        }
        LinearLayout h = new LinearLayout(this);
        TextView h0 = cell("#", MUTED, 0.6f, Gravity.START); h0.setTextSize(12);
        TextView h1 = cell("▲ Hoch", MUTED, 1, Gravity.CENTER); h1.setTextSize(12);
        TextView h2 = cell("▼ Runter", MUTED, 1, Gravity.CENTER); h2.setTextSize(12);
        TextView h3 = cell("Gesamt", MUTED, 1, Gravity.CENTER); h3.setTextSize(12);
        h.addView(h0); h.addView(h1); h.addView(h2); h.addView(h3);
        rows.addView(h);
        double su = 0, sdn = 0, st = 0; int nu = 0, nd = 0, nt = 0;
        for (RepDetector.Rep r : reps) {
            if (!Double.isNaN(r.up)) { su += r.up; nu++; }
            if (!Double.isNaN(r.down)) { sdn += r.down; nd++; }
            if (!Double.isNaN(r.up) && !Double.isNaN(r.down)) { st += r.total; nt++; }
        }
        for (int i = reps.size() - 1; i >= 0 && i >= reps.size() - 60; i--) {
            RepDetector.Rep r = reps.get(i);
            View line = new View(this);
            line.setBackgroundColor(LINE);
            rows.addView(line, new LinearLayout.LayoutParams(MATCH, dp(1)));
            LinearLayout row = new LinearLayout(this);
            row.addView(cell(String.valueOf(r.n), MUTED, 0.6f, Gravity.START));
            row.addView(cell(fs(r.up), UP, 1, Gravity.CENTER));
            row.addView(cell(fs(r.down), DOWN, 1, Gravity.CENTER));
            row.addView(cell(fs(r.total), TEXT, 1, Gravity.CENTER));
            rows.addView(row);
        }
        RepDetector.Rep l = reps.get(reps.size() - 1);
        lastUp.setText(fs(l.up)); lastDown.setText(fs(l.down)); lastTotal.setText(fs(l.total));
        avgUp.setText("Ø " + fs(nu > 0 ? su / nu : Double.NaN));
        avgDown.setText("Ø " + fs(nd > 0 ? sdn / nd : Double.NaN));
        avgTotal.setText("Ø " + fs(nt > 0 ? st / nt : Double.NaN));
    }

    /** Geschwindigkeitsverlauf der letzten 10 Sekunden. */
    private final class ChartView extends View {
        double now;
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChartView(Context c) { super(c); pt.setStyle(Paint.Style.STROKE); pt.setStrokeCap(Paint.Cap.ROUND); }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            float w = getWidth(), h = getHeight(), mid = h / 2, pad = dp(6);
            double span = 10, vmax = 0.8;
            pt.setStrokeWidth(dp(1)); pt.setColor(0x598B93A5);
            cv.drawLine(0, mid, w, mid, pt);
            pt.setStrokeWidth(dp(2.5f));
            for (int i = 1; i < hist.size(); i++) {
                double[] a = hist.get(i - 1), b = hist.get(i);
                if (now - a[0] > span) continue;
                pt.setColor(b[2] > 0 ? UP : b[2] < 0 ? DOWN : 0x8CEEF1F6);
                float x0 = (float) (w - (now - a[0]) / span * w), x1 = (float) (w - (now - b[0]) / span * w);
                float y0 = (float) (mid - Math.max(-1, Math.min(1, a[1] / vmax)) * (mid - pad));
                float y1 = (float) (mid - Math.max(-1, Math.min(1, b[1] / vmax)) * (mid - pad));
                cv.drawLine(x0, y0, x1, y1, pt);
            }
        }
    }

    // ------------------------------------------------------------------ Messdaten

    private void saveRecording() {
        if (rec.isEmpty()) { showBanner("Noch keine Messdaten vorhanden – erst einen Satz aufzeichnen."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "rep-zaehler-messdaten.csv");
        try { startActivityForResult(i, REQ_SAVE); }
        catch (RuntimeException e) { showBanner("Speichern nicht möglich: " + e.getMessage()); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new java.io.IOException("Datei lässt sich nicht öffnen");
            StringBuilder sb = new StringBuilder("t,ax,ay,az,gx,gy,gz\n");
            for (float[] r : rec) {
                sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", r[0], r[1], r[2], r[3], r[4], r[5], r[6]));
                if (sb.length() > 60000) { os.write(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0); }
            }
            sb.append("# app reps=").append(det.reps.size()).append(" minDisp=").append(det.minDisp)
              .append(" lagesensor=").append(recHasGrav).append('\n');
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            showBanner("");
        } catch (Exception e) {
            showBanner("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }
}
