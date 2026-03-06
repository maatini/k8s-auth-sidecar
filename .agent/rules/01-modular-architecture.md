---
trigger: always_on
---

---
name: modular-architecture
priority: 100
---

**Strenge Regeln für JEDEN Prompt und jede Änderung:**
- Arbeite IMMER nur in genau einem Maven-Modul pro Task.
- Erstelle neue Dateien statt bestehende Klassen aufzublasen.
- Keine Datei darf > 300 Zeilen haben (bei > 250 LOC → extrahiere neue Klasse).
- Verwende immer Interfaces + Dependency Inversion.
- Bevor du eine Datei änderst: Führe zuerst `read_file` aus.
- Niemals unaufgefordert implementieren – immer zuerst Planning Mode + User-Bestätigung.
- Änderungen nur mit `sed` oder `replace` nach read_file.
