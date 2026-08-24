# AfkKicker3000

Een strikte Paper anti-AFK plugin. Spelers krijgen na **19 minuten** een waarschuwing en worden na **20 minuten** gekickt.

De timer reset alleen door voldoende horizontaal te verplaatsen. Dus chatten, omkijken, springen op dezelfde plek, auto-clickers en stil AFK minen houden iemand niet online. De ingebouwde lusdetectie voorkomt bovendien dat kleine AFK-pools met een herhaald rondje de timer blijven resetten.

## Builden

Vereist Java 21 en Maven:

```bash
mvn package
```

Plaats daarna `target/AfkKicker3000.jar` in de `plugins`-map van een Paper 1.21.4-server en herstart de server.

## Instellen

Alle timings en detectiedrempels staan in `plugins/AfkKicker3000/config.yml`. De standaardwaarden zijn 1140 seconden (waarschuwing) en 1200 seconden (kick).

Commands:

- `/afk` toont de resterende tijd.
- `/afk reset` reset de eigen timer (permission `afkkicker.reset`).
- `/afk reload` herlaadt de configuratie (permission `afkkicker.reload`).

Operators hebben standaard `afkkicker.bypass` en worden niet gecontroleerd.