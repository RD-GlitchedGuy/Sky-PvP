# SkyBots

Eigenständiges Paper-Plugin für Spieler-NPCs. Die NPCs werden über Citizens erstellt, verwaltet und bewegt. Darum müssen **Paper 26.3 und Citizens 2.0.44 für 26.3** auf dem Server installiert sein.

## Befehle

- `/bot` oder `/bots` – eigenes Bot-Menü öffnen.
- `/bot spawn <Name>` – einen Bot an deiner Position erstellen und dessen Einstellungen öffnen.

Pro Spieler sind höchstens fünf eigene Bots möglich. Im Menü kannst du dich zum Bot teleportieren, Angriffe aktivieren, Crystal-PvP aktivieren, Items und Rüstung bearbeiten oder den Bot löschen.

## Fairness

Die KI greift nur bei Sichtkontakt und innerhalb normaler Nahkampfreichweite an. Ihre Angriffsgeschwindigkeit richtet sich nach der Waffe. Beim Crystal-PvP verbraucht der Bot ein End-Crystal-Item und platziert eine echte End-Crystal-Entität auf Obsidian oder Bedrock. Anschließend schlägt er den Kristall innerhalb normaler Nahkampfreichweite; Paper/Minecraft wickelt die normale Kristall-Explosion, den Schaden und den Rückstoß ab.

Die Bots bewegen sich mit Citizens' Wegfindung. Das ist eine serverseitige NPC-KI und kein vollständig simulierter Minecraft-Client. Crystal-PvP ist eine einfache taktische Routine und spielt nicht auf dem Niveau eines menschlichen Crystal-PvP-Spielers.

## Bauen

Der GitHub-Workflow **Build SkyBots** baut bei Änderungen an diesem Ordner automatisch eine eigene `SkyBots-JAR`. Du findest sie unter GitHub **Actions → Build SkyBots → letzter Lauf → Artifacts**. Die JAR benötigt Citizens auf dem Server.

