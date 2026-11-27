# SkyBotRecorder

Eigenständiges Paper-26.3-Plugin zum Aufzeichnen deines eigenen Bewegungs- und PvP-Verlaufs. Es benötigt kein Citizens und keine Client-Mod. Paper stellt Bewegungsinputs wie vorwärts, rückwärts, links, rechts, springen, schleichen und sprinten für den Spieler bereit. Zusätzlich speichert der Recorder jede Server-Tick-Position, Blickrichtung, Geschwindigkeit, ausgewählte Hotbar, Inventaränderungen und beobachtbare Aktionen.

## Befehle

- `/record start` – beginnt eine eigene Aufnahme.
- `/record start <Name>` – beginnt eine Aufnahme mit einem kurzen Namen.
- `/record stop` – beendet sie und schließt die Datei.
- `/record status` – zeigt den Status und Dateinamen.

Es wird nur der Spieler aufgezeichnet, der den Befehl selbst ausführt. Der Server nimmt keine Chatnachrichten oder Tastatureingaben außerhalb der von Paper gelieferten Bewegungsdaten auf. Die Aufnahme stoppt automatisch nach dem konfigurierten Maximum (Standard: 30 Minuten) oder beim Verlassen des Servers.

## Dateien

Aufnahmen werden im Serverordner unter `plugins/SkyBotRecorder/recordings/` als `.jsonl` gespeichert. Jede Zeile ist ein JSON-Objekt. `sample`-Zeilen enthalten pro Tick Position, Rotation, Velocity, Bewegungsinputs und Spielerzustand. `inventory`-Zeilen speichern alle 36 Inventarslots, Rüstung und Offhand vollständig über Paper/Bukkit-serialisierte Itemdaten, sobald sie sich ändern. Slotwechsel und Aktionen werden als `event`-Zeilen mit Tick-Zeitstempel erfasst.

Die Daten können danach ausgewertet werden, um Bewegungsmuster und Reaktionen in SkyBots nachzubilden. Eine Aufnahme allein erzeugt noch kein menschliches oder lernendes Modell: Der Bot bleibt eine serverseitige KI und kann nur Verhalten lernen, das in passenden Spielsituationen aufgezeichnet wurde. Für eine brauchbare Auswertung am besten echte Kämpfe in mehreren Situationen aufnehmen.

## Bauen

Der GitHub-Workflow **Build SkyBotRecorder** erstellt separat die `SkyBotRecorder-v26.0.jar`. Das Artefakt ist unter GitHub **Actions → Build SkyBotRecorder → letzter Lauf → Artifacts** zu finden.

