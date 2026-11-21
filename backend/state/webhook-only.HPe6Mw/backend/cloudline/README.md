# AEIOU Cloudline · Übergabe an das Backend

Cloudline formatiert den fertigen Angebotstext als HTML. Die Auswahl der Frachtrate, Preis und Währung kommen aus dem Angebotsprozess. Der Renderer verändert den Inhalt des Textes nicht. Er setzt Absätze und Zeilenumbrüche um und hebt den übergebenen Gesamtpreis fett hervor, wenn dessen Wortlaut im Text genau einmal vorkommt.

## Dateien übernehmen

Das ZIP enthält nur Cloudline. Für den Betrieb werden diese drei Dateien mit ihrer relativen Ordnerstruktur benötigt.

```text
cloudline_renderer.py
mail-designs/
  cloudline.html
  assets/clouds.webp
```

`README.md` beschreibt den Einbau. `mail-designs/previews/cloudline.html` ist die fertige Beispielmail für die Sichtprüfung. Der eigenständige Renderer braucht Python ab 3.10 und verwendet die Standardbibliothek.

Im AEIOU-Repository ist Cloudline bereits in `mail_template.py` registriert. Wenn das Backend diesen Renderer übernimmt oder bereits nutzt, erfolgt der Aufruf über `from mail_template import render_mail_html`. Das ZIP stellt für andere Projekte den eigenständigen Import `from cloudline_renderer import render_mail_html` bereit.

Das bereitgestellte Wolkenfoto `mint_foto-clouds-7050884.jpg` liegt in komprimierter Form als `clouds.webp` bei. Der Renderer bettet es je Mail als Base64-Data-URL ein. Das Flugzeug ist ein Unicode-Textzeichen und benötigt keine zusätzliche Grafikdatei.

## Im Angebotsversand aufrufen

```python
from cloudline_renderer import render_mail_html

html_body = render_mail_html(
    body=finished_offer_text,
    all_in_total=formatted_total,  # z. B. "EUR 3316.54", exakt wie im Text
    language=offer_language,      # z. B. "en" oder "de"
    variant="cloudline",
    route=(origin_airport, destination_airport),
)
```

Rufe den Renderer auf, sobald der fertige Angebotstext, die gewählte Route und der formatierte Gesamtpreis vorliegen. Übergib den Text als Plaintext ohne Markdown. `all_in_total` dient nur zur Hervorhebung, der Betrag muss bereits im Angebotstext stehen. Der Text enthält genau eine Option und einen All-in-Gesamtpreis samt Währung.

Den bestehenden Antwortpfad und die ursprüngliche Thread-Zuordnung beibehalten. Für die erste Angebotsantwort referenziert `in_reply_to` die ID der eingegangenen Kundenanfrage. Erst nach erfolgreichem HTML-Renderingtest gehört `html_body` in das `body`-Feld des bestehenden 5U-POSTs. Bis dahin bleibt der fertige Plaintext die Grundlage für den Live-Versand.

## Vor Freigabe prüfen

Die Beispielmail wurde in Chrome bei 320, 390 und 800 Pixeln geprüft, auch ohne Wolkenbild oder Weichzeichnung. Das fertige Beispiel umfasst **47.776 Byte UTF-8** einschließlich Base64-Bild.

Die [5U-API-Dokumentation](https://api.start-hack.5u.ai/docs) weist für `body` ein Limit von 200.000 Zeichen aus. Sie dokumentiert kein separates HTML-Formatfeld. Mit einem freigegebenen Testempfänger prüfen, ob der tatsächliche Empfänger im 5U-Thread HTML gerendert sieht, ob das Base64-Wolkenbild erscheint und ob Route, Preis und Text bei blockiertem Bild sichtbar bleiben. Eine erfolgreiche POST-Antwort allein bestätigt die Darstellung nicht.
