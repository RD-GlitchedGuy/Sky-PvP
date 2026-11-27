# SkyKits

Ein einfaches Kit-Plugin für Paper 26.3. Es verwendet normale Minecraft-Inventar-Menüs und Items, daher ist keine Geyser-Sonderintegration nötig.

## Befehle

- `/kits` – Kit-Menü öffnen und ein Kit anklicken.
- `/kits give mace` – Mace-PvP-Kit selbst ausrüsten.
- `/kits give @p mace` – Kit an den nächsten Spieler vergeben; nur OPs und Command-Blöcke dürfen ein Ziel angeben.
- `/kit give <name>` – ältere Kurzform zum Selbstausrüsten.
- `/kit save <name>` – aktuelles Inventar, Rüstung und Offhand als neues Kit speichern (OP oder `skykits.admin`).
- `/kit delete <name>` – gespeichertes Kit entfernen (OP oder `skykits.admin`).

Das eingebaute `mace`-Kit folgt dem Verlauf und dem Screenshot: Diamantrüstung mit Schutz IV, Mace, Elytra, Feuerwerksraketen, Windladungen, Enderperlen, Totems, Schild, Netherite-Axt und -Schwert sowie Speed II, Heilung II und Stärke II. Die Kits werden in `plugins/SkyKits/config.yml` gespeichert.

## Bauen

Der GitHub-Workflow unter `.github/workflows/build-plugin.yml` baut die JAR automatisch bei jedem Push und bei Pull Requests. Du kannst ihn auch auf GitHub unter **Actions → Build SkyKits → Run workflow** von Hand starten. Nach erfolgreichem Build liegt `SkyKits-JAR` als herunterladbares Artefakt beim Workflow-Lauf.

Lokal brauchst du JDK 25 und Gradle. Im Projektordner `gradle build` ausführen; die Plugin-Datei liegt danach unter `build/libs/SkyKits-v26.0.jar`. Die JAR kommt in den `plugins`-Ordner des Paper-Servers.
