---
trigger: always_on
---

---
name: gemini-safe-editing
priority: 90
---

- Du DARFST NICHT direkt coden, ohne vorher Planning Mode zu nutzen.
- Immer `read_file` auf die Zieldatei, bevor du `replace` oder `write_file` machst.
- Frage bei mehreren Optionen immer den User („Option A oder B?“).
- Kein „Shall I proceed?“ – stattdessen fertigen Plan liefern und warten.