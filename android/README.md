# Rep-Zähler (Android-App)

Zählt Wiederholungen und misst die Zeit für Hoch und Runter über die Sensoren des Handys.
Die App liest die Sensoren direkt aus (ungerundet, 200 Werte pro Sekunde) und braucht keinen Browser.

## APK bauen lassen (GitHub)

1. Dieser Ordner liegt im Repository unter `android/`, der Build-Ablauf unter `.github/workflows/build.yml`.
2. Im Repository auf **Actions** gehen. Der Lauf „APK bauen“ startet bei jedem Push von selbst
   (oder dort über **Run workflow** von Hand).
3. Nach ein paar Minuten liegt die Datei `rep-zaehler.apk` unter **Releases**.
   Auf dem Handy herunterladen, öffnen und die Installation erlauben.

Jede gebaute APK ist mit demselben Schlüssel signiert (`signing/repzaehler.jks`),
neue Versionen lassen sich deshalb einfach über die alte installieren.

## Aufbau

- `app/src/main/java/de/repzaehler/RepDetector.java` – Erkennungslogik (reines Java, ohne Android)
- `app/src/main/java/de/repzaehler/MainActivity.java` – Sensoren und Anzeige
- `.github/workflows/build.yml` – baut die APK auf GitHub
