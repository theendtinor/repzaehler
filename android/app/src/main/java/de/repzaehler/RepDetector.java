package de.repzaehler;

import java.util.ArrayList;

/**
 * RepDetector – erkennt Hoch-/Runter-Phasen aus den Beschleunigungssensoren des Handys.
 *
 * Eingabe pro Messwert: Zeit t (s), Beschleunigung inkl. Schwerkraft (aig) und – falls
 * vorhanden – lineare Beschleunigung ohne Schwerkraft (lin, aus der Sensorfusion).
 *
 *  0. Vertikalbeschleunigung: Projektion von aig auf "oben" (Richtung aus aig - lin);
 *     ohne lin wird der Betrag von aig verwendet.
 *  1. Kalibrierung (~1 s Ruhe): Offset der Vertikalbeschleunigung.
 *  2. Beschleunigung - Offset -> Geschwindigkeit v -> Position p (Integration).
 *  3. Ruhe-Erkennung: Beschleunigung konstant ~0 und v klein -> v = 0. Die bis dahin übrig
 *     gebliebene Geschwindigkeit war ein Offset-Fehler: Der Offset wird korrigiert und die
 *     gerade beendete Phase rückwirkend bereinigt.
 *  4. Phasen werden über den WEG erkannt: Eine Phase läuft, solange p in eine Richtung
 *     wandert, und endet, wenn p ein Stück zurückläuft (Umkehrpunkt) oder das Handy ruht.
 *  5. Nach Hin- und Rückweg ist das Gewicht wieder auf der Ausgangshöhe -> daraus wird
 *     der verbleibende Offset-Fehler geschätzt und korrigiert (gegen Drift).
 *  6. Ist der Offset zum ersten Mal sicher bestimmt, wird der Anfang des Satzes damit
 *     noch einmal durchgerechnet.
 *  7. Eine Wiederholung = zwei Phasen in entgegengesetzter Richtung. Wurde eine Hälfte
 *     nicht erkannt, zählt die Wiederholung trotzdem (fehlende Zeit = NaN).
 */
public final class RepDetector {

    public static final class Rep {
        public int n, firstDir;
        public double up = Double.NaN, down = Double.NaN, total, t0, t1;
    }

    static final class Phase {
        int dir; double t0, t1, dur, disp, peak, pA, SA, pB, SB; Rep rep;
    }

    static final class Leg {
        int dir; double t0, p0, S0, ext, tExt, SExt, peak;
        ArrayList<double[]> tr = new ArrayList<>();   // [t, v in Phasenrichtung, Sv]
    }

    // ---- Einstellungen ----
    public double minDisp = 0.10;   // m   Mindestweg einer Phase
    double vmin = 0.07;             // m/s Mindest-Spitzengeschwindigkeit einer Phase
    double minPhase = 0.2, maxPhase = 15;
    double calTime = 1.0, calRange = 0.35;
    double tauA = 0.06;             // s   Glättung (nur für die Ruhe-Erkennung)
    double win = 0.3;               // s   Fenster der Ruhe-Erkennung
    double steadyR0 = 0.16;         // m/s² max. Schwankung im Fenster = "gleichmäßig"
    double restA = 0.03;            // m/s² |Beschleunigung| darunter = keine Beschleunigung
    double vz = 0.05;               // m/s  |v| darunter (und gleichmäßig) = Ruhe
    double endFrac = 0.5;
    double tauBase = 1.0, baseOld = 0.6;
    double holdAfter = 0.5, holdFlat = 0.1;
    double startDisp = 0.03, revDisp = 0.03, revFrac = 0.10;
    double catchUp = 0.15, hardRest = 4;
    double trimFrac = 0.10, trimMin = 0.02;
    double mergeGap = 1.2, pairGap = 10;
    double zuptGain = 0.6, zuptMax = 0.04, zuptMin = 1.0;
    double loopGain = 0.5, loopMax = 0.05, loopFirst = 0.85; int loopFirstN = 2;
    double replayMin = 0.008, replayTime = 40; int replayMax = 4;

    // ---- Zustand ----
    private boolean hasLast; private double last;
    public double t;
    private double aS, base;
    public double v;
    private double p, Sv, Sp;
    private final ArrayList<double[]> w = new ArrayList<>();   // [t, aS, a]
    private final ArrayList<double[]> q = new ArrayList<>();   // [t, aS, a]
    private boolean steadyOn, calmOn, resting, wasHold;
    private double steadyT, calmT, sLo, sHi, steadyR;
    public boolean calibrated;
    private boolean calOn, calFine; private double calT0;
    private final ArrayList<double[]> calQ = new ArrayList<>(); // [t, a, am, aS]
    private ArrayList<double[]> buf;                            // [t, dt, a]
    private int nReplay, nLoop;
    private double corr;
    private boolean linOk, hasGmS, hasGRef; private double gmS, gRef, magOff;
    public double qz;
    private double zr, zS;

    private Leg leg;
    private ArrayList<double[]> pre = new ArrayList<>();        // [t, v, p, Sp, Sv]
    public final ArrayList<Rep> reps = new ArrayList<>();
    private final ArrayList<Phase> phases = new ArrayList<>();
    private Phase pending, lastPh;
    public boolean hasSet; public double setStart, lastEnd;
    public int rev;
    private double typDisp;

    public RepDetector() { reset(); }

    public void reset() {
        hasLast = false; t = 0;
        aS = 0; base = 0; v = 0; p = 0; Sv = 0; Sp = 0;
        w.clear(); q.clear(); steadyOn = false; calmOn = false; resting = false; wasHold = false;
        sLo = 0; sHi = 0; steadyR = steadyR0;
        calibrated = false; calOn = false; buf = null; nReplay = 0; corr = 0; nLoop = 0;
        linOk = true; hasGRef = false; magOff = 0; hasGmS = false; qz = 0; zr = 0; zS = 0;
        clearSet();
    }

    private void clearSet() {
        leg = null; pre = new ArrayList<>();
        reps.clear(); phases.clear(); pending = null; lastPh = null;
        hasSet = false; typDisp = 0; rev++;
    }

    /** Nur Zähler/Verlauf löschen, Sensor-Zustand (Offset) behalten. */
    public void resetSet() { clearSet(); v = 0; Sv = 0; buf = null; }

    /** Neuer Messwert. lin darf null sein. Rückgabe: Anzahl neu gezählter Wiederholungen. */
    public int push(double tt, double[] aig, double[] lin) {
        if (!hasLast) { hasLast = true; last = tt; t = tt; return 0; }
        double dt = tt - last;
        if (dt <= 0) return 0;
        last = tt; t = tt;
        if (dt > 0.5) { // Datenlücke -> neu kalibrieren
            leg = null; pre = new ArrayList<>(); v = 0; Sv = 0; w.clear(); steadyOn = false;
            calibrated = false; calOn = false; buf = null; dt = 0.02;
        }
        if (dt > 0.1) dt = 0.1;

        // Vertikalbeschleunigung
        double a = 0, gm = 0;
        if (lin != null && !hasGmS) { gmS = hyp(aig[0] - lin[0], aig[1] - lin[1], aig[2] - lin[2]); hasGmS = true; }
        if (lin != null && linOk) {
            double gx = aig[0] - lin[0], gy = aig[1] - lin[1], gz = aig[2] - lin[2];
            gm = hyp(gx, gy, gz); if (gm == 0) gm = 1;
            a = (aig[0] * gx + aig[1] * gy + aig[2] * gz) / gm;
            // Echte Fusion liefert eine Schwerkraft mit konstantem Betrag. Schwankt er, taugt "lin"
            // nicht als Richtung -> dann lieber den Betrag der Gesamtbeschleunigung nehmen.
            gmS += (gm - gmS) * Math.min(1, dt / 0.5);
            if (hasGRef && Math.abs(gmS - gRef) > 0.2) { linOk = false; base += magOff; buf = null; }
        }
        double am = hyp(aig[0], aig[1], aig[2]);
        if (lin == null || !linOk) a = am;

        // Kalibrierung: 1 s lang ruhig. Gelingt das 4 s lang nicht, wird mit der letzten Sekunde
        // gestartet und die Ruhe-Schwelle an das Rauschen angepasst.
        if (!calibrated) {
            if (!calOn) { calOn = true; calT0 = tt; calQ.clear(); calFine = false; aS = a; }
            aS += (a - aS) * Math.min(1, dt / tauA);
            calQ.add(new double[]{tt, a, am, aS});
            for (int i = 0; i < 3; i++) if (Math.abs(aig[i] * 10 - Math.rint(aig[i] * 10)) > 1e-6) calFine = true;
            while (tt - calQ.get(0)[0] > calTime) calQ.remove(0);
            if (tt - calT0 < calTime || calQ.size() < 6) return 0;
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY, sum = 0, sumM = 0;
            for (double[] x : calQ) { sum += x[1]; sumM += x[2]; if (x[3] < lo) lo = x[3]; if (x[3] > hi) hi = x[3]; }
            if (hi - lo > calRange && tt - calT0 < 4) return 0;
            int n = calQ.size();
            base = sum / n; calibrated = true; calOn = false;
            hasGRef = gm != 0; gRef = gmS; magOff = (sumM - sum) / n;
            qz = calFine ? 0 : 0.05;   // auf 0,1 gerundete Werte (Browser) – in der App normalerweise 0
            steadyR = Math.min(0.6, Math.max(steadyR0, 1.3 * (hi - lo)));
            start(base);
            pre = new ArrayList<>(); pre.add(new double[]{tt, 0, 0, 0, 0});
            buf = new ArrayList<>(); buf.add(new double[]{tt, dt, base}); nReplay = 0;
            return 0;
        }

        // Anfangs werden die Werte mitgeschrieben: Sobald der Offset zum ersten Mal sicher bestimmt
        // ist (erste Ruhe / erste Wiederholung), wird der Anfang damit noch einmal durchgerechnet.
        ArrayList<double[]> b = buf;
        if (b != null) b.add(new double[]{tt, dt, a});
        corr = 0;
        int had = reps.size();
        step(tt, dt, a);
        if (b != null && buf == b) {
            if (corr >= replayMin && nReplay < replayMax) replay();
            if (tt - b.get(0)[0] > replayTime || nReplay >= replayMax) buf = null;
        }
        return Math.max(0, reps.size() - had);
    }

    /** Anfang mit dem jetzt bekannten Offset neu auswerten. */
    private void replay() {
        ArrayList<double[]> b = buf;
        for (int pass = 0; pass < 2 && nReplay < replayMax; pass++) {
            double b0 = base; nReplay++;
            start(b.get(0)[2]); clearSet();
            pre.add(new double[]{b.get(0)[0], 0, 0, 0, 0});
            for (int i = 0; i < b.size(); i++) { double[] x = b.get(i); step(x[0], x[1], x[2]); }
            if (Math.abs(base - b0) < replayMin) break;
        }
        rev++; corr = 0;
    }

    /** Zustand der Auswertung auf "in Ruhe" setzen (Offset usw. bleiben). */
    private void start(double a) {
        aS = a; v = 0; p = 0; Sv = 0; Sp = 0;
        w.clear(); q.clear(); steadyOn = false; calmOn = false; resting = false; wasHold = false;
        zr = 0; zS = 0;
    }

    private void step(double tt, double dt, double a) {
        aS += (a - aS) * Math.min(1, dt / tauA);

        // Fenster: ist die Beschleunigung gleichmäßig?
        w.add(new double[]{tt, aS, a});
        while (w.size() > 2 && tt - w.get(1)[0] >= win) w.remove(0);
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY, sum = 0, rs = 0, rs2 = 0;
        for (double[] x : w) { if (x[1] < lo) lo = x[1]; if (x[1] > hi) hi = x[1]; sum += x[1]; rs += x[2]; rs2 += x[2] * x[2]; }
        double mean = sum / w.size();
        double u = unc(rs, rs2, w.size());
        double w0 = w.get(0)[0];
        boolean steady = tt - w0 >= win * 0.9 && hi - lo < steadyR;
        // "flach": über längere Zeit in einem engen Band (für die sichere Ruhe-Erkennung)
        if (steady) {
            if (!steadyOn) { steadyOn = true; steadyT = w0; sLo = lo; sHi = hi; }
            if (lo < sLo) sLo = lo;
            if (hi > sHi) sHi = hi;
            if (sHi - sLo > steadyR) { steadyT = w0; sLo = lo; sHi = hi; }
        } else steadyOn = false;
        double flatDur = steady ? tt - steadyT : 0;

        // "ruhig": gleichmäßig und ~0 -> keine Beschleunigung (Ruhe ODER gleichförmige Bewegung)
        boolean calm = steady && Math.abs(mean - base) < restA + u;
        if (calm) { if (!calmOn) { calmOn = true; calmT = w0; } } else calmOn = false;
        double calmDur = calm ? tt - calmT : 0;
        // Offset nachführen: nur bei länger anhaltender Ruhe und mit älteren Werten, damit der
        // (langsame) Beginn einer Bewegung nie als Offset gelernt wird.
        q.add(new double[]{tt, aS, a});
        while (!q.isEmpty() && tt - q.get(0)[0] > baseOld + 0.3) q.remove(0);
        if (calmDur >= baseOld + 0.3) {
            double sm = 0, r1 = 0, r2 = 0; int n = 0;
            for (int i = 0; i < q.size() && tt - q.get(i)[0] >= baseOld; i++) { double[] x = q.get(i); sm += x[1]; r1 += x[2]; r2 += x[2] * x[2]; n++; }
            if (n > 0) pull(sm / n, unc(r1, r2, n), Math.min(1, dt / tauBase));
        }
        // Integration. Bei anhaltender Ruhe bleibt v stehen (Offset-Fehler summieren sich nicht auf).
        boolean hold = calm && calmDur >= (u > 0.02 ? holdFlat : holdAfter);
        if (!hold) {
            if (wasHold) { // Beginn einer Beschleunigung: den Anfang (noch im ruhigen Fenster) nachholen
                for (int i = w.size() - 2; i > 0 && tt - w.get(i)[0] < catchUp; i--) {
                    double d = (w.get(i)[2] - base) * (w.get(i + 1)[0] - w.get(i)[0]);
                    v += d; p += d * (tt - w.get(i)[0]);
                }
            }
            v += (a - base) * dt; Sv += dt;
        }
        wasHold = hold;
        p += v * dt; Sp += Sv * dt;

        // Ruhe?
        Leg L = leg;
        double travel = L != null ? (L.ext - L.p0) * L.dir : 0;
        boolean rest = false, sure = false;
        if (calm) {
            double av = Math.abs(v);
            rest = sure = av < vz || (L != null && travel >= minDisp && av < endFrac * L.peak);
            if (!rest) rest = calmDur >= 1.0 && travel < minDisp;
        }
        if (flatDur >= hardRest) { rest = true; pull(mean, u, 1); } // lange völlig gleichmäßig -> sicher Ruhe
        if (rest) {
            zr = 0; zS = 0;
            if (!resting && Sv > zuptMin) { // Rest-Geschwindigkeit = Offset-Fehler seit der letzten Ruhe
                if (sure) {
                    zr = v; zS = Sv;
                    double e = Math.max(-zuptMax, Math.min(zuptMax, zuptGain * v / Sv));
                    base += e; corr += Math.abs(e);
                }
            }
            v = 0; Sv = 0;
        }
        resting = rest;

        if (leg == null) {
            if (rest) pre.clear();
            pre.add(new double[]{tt, v, p, Sp, Sv});
            while (pre.size() > 1 && tt - pre.get(0)[0] > 0.8) pre.remove(0);
            double[] h0 = pre.get(0);
            double d = p - h0[2];
            if (Math.abs(d) >= startDisp) {
                Leg s = new Leg();
                s.dir = d > 0 ? 1 : -1; s.t0 = h0[0]; s.p0 = h0[2]; s.S0 = h0[3];
                s.ext = p; s.tExt = tt; s.SExt = Sp; s.peak = Math.abs(v);
                for (double[] h : pre) s.tr.add(new double[]{h[0], h[1] * s.dir, h[4]});
                leg = s; pre = new ArrayList<>();
            }
        } else {
            Leg s = leg; double sv = v * s.dir;
            s.tr.add(new double[]{tt, sv, Sv});
            if (sv > s.peak) s.peak = sv;
            if ((p - s.ext) * s.dir > 0) { s.ext = p; s.tExt = tt; s.SExt = Sp; }
            double back = (s.ext - p) * s.dir;
            if (rest) {
                // In Ruhe ist v = 0 bekannt. Die übrig gebliebene Geschwindigkeit war ein Fehler, der seit
                // der letzten Ruhe gleichmäßig angewachsen ist -> rückwirkend aus dieser Phase herausrechnen.
                if (zS > 0 && zr != 0) {
                    double k = zr * s.dir / zS, d = 0, pk = 0, dMax = 0, tMax = s.t0;
                    for (int i = 0; i < s.tr.size() - 1; i++) {
                        double[] x = s.tr.get(i); x[1] -= k * x[2];
                        if (x[1] > pk) pk = x[1];
                        if (i > 0) { d += x[1] * (x[0] - s.tr.get(i - 1)[0]); if (d > dMax) { dMax = d; tMax = x[0]; } }
                    }
                    s.peak = pk; s.ext = s.p0 + dMax * s.dir; s.tExt = tMax; p = s.ext;
                }
                finish(s, tt);
                leg = null; pre = new ArrayList<>(); pre.add(new double[]{tt, 0, p, Sp, 0});
            } else if (back >= Math.max(revDisp, revFrac * travel)) {
                // Umkehrpunkt: Phase endet am Extrempunkt, die Gegenrichtung läuft seitdem
                finish(s, s.tExt);
                Leg n = new Leg(); double pk = 0;
                for (double[] x : s.tr) if (x[0] >= s.tExt) { n.tr.add(new double[]{x[0], -x[1], x[2]}); if (-x[1] > pk) pk = -x[1]; }
                n.dir = -s.dir; n.t0 = s.tExt; n.p0 = s.ext; n.S0 = s.SExt; n.ext = p; n.tExt = tt; n.SExt = Sp; n.peak = pk;
                leg = n;
            } else if (tt - s.t0 > maxPhase || travel > Math.min(2.5, Math.max(1.0, 3 * typDisp))) {
                // Unplausibel lange/weite "Phase" = Drift. Die mittlere Geschwindigkeit über diese Zeit muss
                // ~0 gewesen sein (das Gewicht pendelt nur) -> abziehen und den Offset etwas nachziehen.
                double T = Math.max(0.5, tt - s.t0), vm = travel / T * s.dir;
                v -= vm; base += Math.max(-0.03, Math.min(0.03, vm / T));
                leg = null; Sv = 0; pre = new ArrayList<>(); pre.add(new double[]{tt, v, p, Sp, 0});
            }
        }
    }

    private static double hyp(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }

    /** Rundungs-Unsicherheit eines Fensters aus der Streuung der Rohwerte. */
    private double unc(double s1, double s2, int n) {
        if (qz == 0 || n == 0) return 0;
        double m = s1 / n, sd = Math.sqrt(Math.max(0, s2 / n - m * m));
        return Math.max(0, qz - 1.5 * sd);
    }

    /** Offset zum Zielwert ziehen, aber nur so weit, wie der Zielwert sicher ist (±ub). */
    private void pull(double target, double ub, double k) {
        double d = target - base;
        if (Math.abs(d) > ub) base += (d - (d > 0 ? ub : -ub)) * k;
    }

    /** Phase abschließen: prüfen und genaue Zeiten aus dem Geschwindigkeitsverlauf bestimmen. */
    private void finish(Leg s, double tEnd) {
        double disp = (s.ext - s.p0) * s.dir;
        if (disp < minDisp || s.peak < vmin) return;
        double thr = Math.max(trimFrac * s.peak, trimMin);
        int i0 = -1, i1 = -1;
        for (int i = 0; i < s.tr.size(); i++) {
            double[] x = s.tr.get(i);
            if (x[0] > tEnd) break;
            if (x[1] > thr) { if (i0 < 0) i0 = i; i1 = i; }
        }
        if (i0 < 0) return;
        double t0 = s.tr.get(i0)[0], t1 = s.tr.get(i1)[0];
        if (t1 - t0 < minPhase) return;
        Phase ph = new Phase();
        ph.dir = s.dir; ph.t0 = t0; ph.t1 = t1; ph.dur = t1 - t0; ph.disp = disp; ph.peak = s.peak;
        ph.pA = s.p0; ph.SA = s.S0; ph.pB = s.ext; ph.SB = s.SExt;
        phase(ph);
    }

    private void phase(Phase ph) {
        Phase lastP = lastPh;
        if (!hasSet) { hasSet = true; setStart = ph.t0; }
        lastEnd = ph.t1;

        // Gleiche Richtung kurz nach der letzten Phase -> gehört dazu (z. B. kurzes Stocken)
        if (lastP != null && lastP.dir == ph.dir && ph.t0 - lastP.t1 < mergeGap) {
            lastP.t1 = ph.t1; lastP.dur = lastP.t1 - lastP.t0; lastP.disp += ph.disp; lastP.peak = Math.max(lastP.peak, ph.peak);
            lastP.pB = ph.pB; lastP.SB = ph.SB;
            Rep r = lastP.rep;
            if (r != null) { if (lastP.dir > 0) r.up = lastP.dur; else r.down = lastP.dur; r.t1 = lastP.t1; r.total = r.t1 - r.t0; rev++; }
            return;
        }
        // Drift-Korrektur: Nach Hin- und Rückweg ist das Gewicht wieder auf der Ausgangshöhe.
        // Die Abweichung kommt vom Offset-Fehler. Am Anfang (Offset noch unsicher) stärker korrigieren.
        if (lastP != null && lastP.dir != ph.dir && ph.t0 - lastP.t1 <= pairGap) {
            double dS = ph.SB - lastP.SA;
            if (dS > 0.2) {
                double g = nLoop < loopFirstN ? loopFirst : loopGain; nLoop++;
                double e = Math.max(-loopMax, Math.min(loopMax, g * (ph.pB - lastP.pA) / dS));
                base += e; v -= e * Sv; corr += Math.abs(e);
            }
        }
        phases.add(ph); lastPh = ph;
        typDisp = typDisp != 0 ? typDisp + 0.3 * (ph.disp - typDisp) : ph.disp;

        Phase pd = pending;
        if (pd != null && pd.dir != ph.dir && ph.t0 - pd.t1 <= pairGap) {
            pending = null;
            rep(pd, ph);
        } else if (pd != null && pd.dir == ph.dir) {
            // Gegenrichtung wurde nicht erkannt -> Wiederholung trotzdem zählen
            rep(pd, null);
            pending = ph;
        } else if (pd == null && lastP != null && lastP.rep != null && lastP.dir == ph.dir && ph.t0 - lastP.t1 <= pairGap) {
            rep(null, ph);
        } else {
            pending = ph;
        }
    }

    private void rep(Phase a, Phase b) {
        Phase up = (a != null && a.dir > 0) ? a : (b != null && b.dir > 0) ? b : null;
        Phase down = (a != null && a.dir < 0) ? a : (b != null && b.dir < 0) ? b : null;
        Phase f = a != null ? a : b, l = b != null ? b : a;
        Rep r = new Rep();
        r.n = reps.size() + 1; r.firstDir = a != null ? a.dir : -b.dir;
        if (up != null) r.up = up.dur;
        if (down != null) r.down = down.dur;
        r.total = l.t1 - f.t0; r.t0 = f.t0; r.t1 = l.t1;
        if (a != null) a.rep = r;
        if (b != null) b.rep = r;
        reps.add(r); rev++;
    }

    // ---- Für die Anzeige ----

    /** Aktuelle Richtung (+1 hoch, -1 runter) oder 0, wenn keine Bewegung erkannt ist. */
    public int liveDir() {
        Leg s = leg;
        if (s == null) return 0;
        double sv = v * s.dir, tr = (s.ext - s.p0) * s.dir;
        if ((s.ext - p) * s.dir >= 0.012 && sv < -0.04 && tr >= minDisp) return -s.dir; // dreht gerade um
        if (tr >= 0.05 && s.peak >= vmin) return s.dir;
        return 0;
    }

    /** Dauer der laufenden Phase in s (nur gültig, wenn liveDir() != 0). */
    public double liveElapsed() {
        Leg s = leg;
        if (s == null) return 0;
        double sv = v * s.dir, tr = (s.ext - s.p0) * s.dir;
        if ((s.ext - p) * s.dir >= 0.012 && sv < -0.04 && tr >= minDisp) return t - s.tExt;
        return t - s.t0;
    }
}
