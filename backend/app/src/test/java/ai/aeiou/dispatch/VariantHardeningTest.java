package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import static org.junit.jupiter.api.Assertions.*;

/** New variants of the live failure classes of 2026-11-21, run through the real parsing and worker paths. */
class VariantHardeningTest {
  @TempDir Path temp;
  static final Catalog catalog = new DispatchApplication().catalog(null);

  record V(String name, String subject, String body, String llm, String separate) {
    V(String name, String subject, String body, String llm) {
      this(name, subject, body, llm, null);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static String review() {
    return "{\"origin\":null,\"destination\":null,\"weight_kg\":null,\"volume_cbm\":null,\"preference\":\"cheapest\","
        + "\"pickup_required\":true,\"pickup_zone\":\"85\",\"needs_review\":true,\"reply_language\":\"en\","
        + "\"ready_date\":\"2026-11-26\",\"arrive_by\":null,\"arrival_deadline_requested\":false}";
  }

  static String sep(String first, String d1, int kg1, double cbm1, String second, String d2, int kg2, double cbm2) {
    return "{\"needs_review\":false,\"shipments\":[{\"source\":%s,\"destination\":\"%s\",\"weight_kg\":%d,\"volume_cbm\":%s,\"pieces\":null,\"pickup_required\":true},{\"source\":%s,\"destination\":\"%s\",\"weight_kg\":%d,\"volume_cbm\":%s,\"pieces\":null,\"pickup_required\":true}]}"
        .formatted(json(first), d1, kg1, cbm1, json(second), d2, kg2, cbm2);
  }

  static String json(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  static List<V> structure() {
    String r = "2026-11-26";
    String s1a = "Sendung 1 geht nach SIN: 3 Kisten, 420 kg, 1,9 cbm.";
    String s1b = "Sendung 2 geht nach BKK: 1 Palette, 160 kg, 0,8 cbm.";
    String s2a = "First consignment: Bangkok (BKK), 500 kg, 2.5 cbm.";
    String s2b = "Second consignment: Singapore (SIN), 220 kg, 1.1 cbm.";
    String s3a = "Shipment A – to BKK – 500 kg / 2.5 cbm";
    String s3b = "Shipment B – to SIN – 220 kg / 1.1 cbm";
    return List.of(
        new V("S1 DE Sendung 1 geht nach SIN", "Luftfrachtanfrage zwei Sendungen",
            "Hallo,\n\nbitte separate Preise für zwei Sendungen, günstigste Variante, Abholung in 85049 Ingolstadt, bereit ab 2026-11-26:\n\n"
                + s1a + "\n" + s1b + "\n\nGruß\nJan", review(), sep(s1a, "SIN", 420, 1.9, s1b, "BKK", 160, 0.8)),
        new V("S2 first/second consignment", "Two air freight quotes",
            "Hello,\n\nplease quote two separate shipments, cheapest, both collected from 85049 Ingolstadt, ready 2026-11-26.\n\n"
                + s2a + "\n" + s2b + "\n\nThanks\nJan", review(), sep(s2a, "BKK", 500, 2.5, s2b, "SIN", 220, 1.1)),
        new V("S3 Shipment A/B", "Quotes for two shipments",
            "Hello,\n\nplease quote both shipments separately, cheapest, pickup at 85049 Ingolstadt, ready 2026-11-26:\n\n"
                + s3a + "\n" + s3b + "\n\nThanks\nJan", review(), sep(s3a, "BKK", 500, 2.5, s3b, "SIN", 220, 1.1)),
        new V("S4 Lot I/II roman one AWB", "RFQ FRA-ORD consolidated",
            "Hi,\n\nplease quote FRA to ORD, cheapest, we deliver to FRA ourselves, ready 2026-11-26. The lot ships as one AWB.\n\n"
                + "Lot I: 2 pallets, each 150 kg, each 120x80x100 cm\nLot II: 1 crate, 80 kg, 0.5 cbm\n\nThanks\nAli",
            j("FRA", "ORD", 380, 2.42, "cheapest", false, null, "en", r, null, false)),
        new V("S5 DE Los je einem AWB", "Luftfracht FRA-SIN",
            "Hallo,\n\nbitte Angebot FRA nach SIN, günstigste Variante, wir liefern selbst an, bereit ab 2026-11-26. Alles auf einem AWB.\n\n"
                + "Los 1: 3 Kisten, je 120 kg, je 80x60x50 cm\nLos 2: 2 Kartons, zusammen 40 kg, zusammen 0,2 cbm\n\nGruß\nEva",
            j("FRA", "SIN", 400, 0.92, "cheapest", false, null, "de", r, null, false)),
        new V("S6 KO lots per piece", "FRA-ICN 항공 견적",
            "안녕하세요,\n\nFRA에서 ICN까지 가장 저렴한 옵션으로 견적 부탁드립니다. FRA 공항까지는 저희가 직접 반입하겠습니다. 2026-11-26 출고 가능. 항공화물운송장 한 장으로 보내주세요.\n\n"
                + "로트 1: 카톤 4개, 개당 25 kg, 개당 0.1 cbm\n로트 2: 팔레트 1개, 300 kg, 1.2 cbm\n\n감사합니다",
            j("FRA", "ICN", 400, 1.6, "cheapest", false, null, "ko", r, null, false)),
        new V("S7 lb and cu ft each", "RFQ MUC-MIA", "Hi,\n\nMUC to MIA, fastest. 4 boxes at 55 lb each, 3 cu ft each, ready 2026-11-26. We bring them to MUC ourselves.\n\nThanks\nBob",
            j("MUC", "MIA", 99.79, 0.34, "fastest", false, null, "en", r, null, false)),
        new V("S8 ES cada uno", "Cotización FRA-BOG", "Hola,\n\ncotización flete aéreo FRA a BOG, la opción más barata. 5 cajas de 40 kg cada una, 0,3 m3 cada una, listo el 2026-11-26. Llevamos la carga al aeropuerto de FRA.\n\nSaludos\nAna",
            j("FRA", "BOG", 200, 1.5, "cheapest", false, null, "es", r, null, false)));
  }

  static String j(String o, String d, Object kg, Object cbm, String pref, boolean pickup, String zone,
      String lang, String ready, String arrive, boolean deadline) {
    return "{\"origin\":%s,\"destination\":%s,\"weight_kg\":%s,\"volume_cbm\":%s,\"preference\":%s,"
        .formatted(q(o), q(d), kg, cbm, q(pref))
        + "\"pickup_required\":%s,\"pickup_zone\":%s,\"needs_review\":false,\"reply_language\":%s,"
            .formatted(pickup, q(zone), q(lang))
        + "\"ready_date\":%s,\"arrive_by\":%s,\"arrival_deadline_requested\":%s}".formatted(q(ready), q(arrive), deadline);
  }

  static String q(String s) {
    return s == null ? "null" : "\"" + s + "\"";
  }

  static List<V> variants() {
    String r = "2026-11-26";
    return List.of(
        new V("P1 do not arrange collection", "RFQ FRA-ORD", "Hello,\n\nplease quote the cheapest air freight FRA to ORD for 250 kg, 1.2 cbm, ready on 2026-11-26. Please do not arrange collection – we drop it off at the FRA terminal.\n\nThanks\nAnna",
            j("FRA", "ORD", 250, 1.2, "cheapest", false, null, "en", r, null, false)),
        new V("P2 no need to collect + bring ourselves", "Air freight MUC-JFK", "Hi,\n\nNo need to collect – we'll bring the cargo to Munich airport (MUC) ourselves. 3 pallets, 480 kg, 2.4 cbm to JFK, cheapest option. Ready 2026-11-26.\n\nBest\nTom",
            j("MUC", "JFK", 480, 2.4, "cheapest", false, null, "en", r, null, false)),
        new V("P3 pickup not needed (no verb)", "Quote FRA to SIN", "Dear team,\n\nQuote request FRA -> SIN, fastest service.\n- 2 crates, 310 kg, 1.5 cbm\n- Pickup: not required, we deliver to FRA\n- Ready: 2026-11-26\n\nRegards\nLea",
            j("FRA", "SIN", 310, 1.5, "fastest", false, null, "en", r, null, false)),
        new V("P4 DE Abholung nicht erforderlich", "Luftfrachtanfrage FRA-ORD", "Guten Tag,\n\nbitte um ein Angebot für Luftfracht von FRA nach ORD, günstigste Variante. 2 Paletten, 400 kg, 1,8 cbm, bereit ab 2026-11-26. Abholung nicht erforderlich – wir liefern die Ware selbst am Flughafen Frankfurt an.\n\nMit freundlichen Grüßen\nKlaus",
            j("FRA", "ORD", 400, 1.8, "cheapest", false, null, "de", r, null, false)),
        new V("P5 FR pas d'enlevement", "Devis fret aérien FRA-YYZ", "Bonjour,\n\nPourriez-vous nous faire un devis pour un envoi FRA - YYZ, le moins cher possible ? 150 kg, 0,9 m³, prêt le 2026-11-26. Pas d'enlèvement svp, nous apportons la marchandise à l'aéroport.\n\nCordialement\nMarie",
            j("FRA", "YYZ", 150, 0.9, "cheapest", false, null, "fr", r, null, false)),
        new V("P6 TR kamyonla taşıma gerekmiyor", "FRA-DXB hava kargo teklifi", "Merhaba,\n\nFRA çıkışlı DXB varışlı gönderimiz için en ucuz teklifi rica ediyoruz. 220 kg, 1.1 cbm. Kamyonla taşıma gerekmiyor, yükü FRA havalimanına kendimiz getireceğiz. Yük 2026-11-26 tarihinde hazır olacak.\n\nTeşekkürler\nAyşe",
            j("FRA", "DXB", 220, 1.1, "cheapest", false, null, "tr", r, null, false)),
        new V("P7 truck it to FRA ourselves", "RFQ FRA to MIA", "Hi,\n\ncould you quote the cheapest air freight to MIA? 600 kg, 3.1 cbm, ready for collection on 2026-11-26, but we'll truck it to FRA ourselves.\n\nCheers\nJon",
            j("FRA", "MIA", 600, 3.1, "cheapest", false, null, "en", r, null, false)),
        new V("P8 ES recogida CP", "Cotización flete aéreo a MEX", "Hola,\n\nnecesitamos una cotización de flete aéreo a MEX, la opción más económica. Por favor recoger en nuestro almacén en 60311 Frankfurt. 2 palés, 350 kg, 1,6 m3, listo el 2026-11-26.\n\nSaludos\nCarlos",
            j(null, "MEX", 350, 1.6, "cheapest", true, "60", "es", r, null, false)),
        new V("P9 IT ritiro", "Richiesta quotazione aerea MUC-NBO", "Buongiorno,\n\nvi chiediamo una quotazione per spedizione aerea verso NBO, opzione più veloce. Ritiro presso il nostro magazzino: Via Roma 1? no – Landsberger Str. 12, 80339 München. 180 kg, 0,8 m³, pronto il 2026-11-26.\n\nCordiali saluti\nLuca",
            j(null, "NBO", 180, 0.8, "fastest", true, "80", "it", r, null, false)),
        new V("P10 NL ophalen", "Offerte luchtvracht DUS-JNB", "Goedemiddag,\n\nGraag een offerte voor luchtvracht naar JNB, zo goedkoop mogelijk. Ophalen bij ons magazijn, Königsallee 10, 40212 Düsseldorf. 270 kg, 1,3 m³, klaar op 2026-11-26.\n\nMet vriendelijke groet\nSanne",
            j(null, "JNB", 270, 1.3, "cheapest", true, "40", "nl", r, null, false)),
        new V("P11 PL odbior", "Zapytanie fracht lotniczy STR-ORD", "Dzień dobry,\n\nprosimy o wycenę frachtu lotniczego do ORD, najtańsza opcja. Odbiór z naszego zakładu: Hauptstr. 3, 70173 Stuttgart. 520 kg, 2,2 m3, gotowe 2026-11-26.\n\nPozdrawiam\nPiotr",
            j(null, "ORD", 520, 2.2, "cheapest", true, "70", "pl", r, null, false)),
        new V("P12 TR depomuzdan alın", "Köln - LAX hava kargo", "Merhaba,\n\nLAX varışlı gönderimiz için en hızlı seçeneği istiyoruz. Malları depomuzdan almanızı rica ediyoruz, adres: Domstr. 1, 50667 Köln. 410 kg, 2 cbm. Yük 2026-11-26 tarihinde hazır olacak.\n\nTeşekkürler\nMehmet",
            j(null, "LAX", 410, 2, "fastest", true, "50", "tr", r, null, false)),
        new V("P13 pick up at our Cologne plant", "RFQ to ORD", "Hello,\n\nplease quote the cheapest air freight to ORD with pick up at our Cologne plant. 300 kg, 1.4 cbm, ready 2026-11-26.\n\nRegards\nSam",
            j("CGN", "ORD", 300, 1.4, "cheapest", true, null, "en", r, null, false)),
        new V("P14 our address Köln", "RFQ CGN area to YYZ", "Hello,\n\nplease collect from our address: Musterstr. 5, 50667 Köln and quote the fastest air freight to YYZ. 4 cartons, 95 kg, 0.4 cbm, ready 2026-11-26.\n\nRegards\nSam",
            j("CGN", "YYZ", 95, 0.4, "fastest", true, "50", "en", r, null, false)),
        new V("P15 deliver directly to Hamburg Airport w/ signature address", "Air freight HAM to SIN", "Hi,\n\nWe will deliver directly to Hamburg Airport. Please quote the cheapest air freight to SIN: 700 kg, 3.5 cbm, ready 2026-11-26.\n\nBest\nNina\nNordwerk GmbH, Speicherstr. 2, 20457 Hamburg",
            j("HAM", "SIN", 700, 3.5, "cheapest", false, null, "en", r, null, false)),
        new V("P16 we hand over at terminal", "Quote STR-LAX", "Hello,\n\nWe'll hand the goods over at the STR cargo terminal ourselves; no warehouse pickup. Fastest option to LAX please. 140 kg, 0.6 cbm, ready 2026-11-26.\n\nRegards\nIda",
            j("STR", "LAX", 140, 0.6, "fastest", false, null, "en", r, null, false)),
        new V("Q1 price matters most", "RFQ FRA-HKG", "Hi,\n\nFRA to HKG, 330 kg, 1.7 cbm, ready 2026-11-26, we deliver to FRA ourselves. Price matters most to us.\n\nBest\nUli",
            j("FRA", "HKG", 330, 1.7, "cheapest", false, null, "en", r, null, false)),
        new V("Q2 time-critical", "Urgent RFQ FRA-JFK", "Hi,\n\nThis is a time-critical shipment of spare parts FRA to JFK, 120 kg, 0.5 cbm, ready 2026-11-26. We deliver to FRA ourselves. Please send your all-in price.\n\nBest\nUli",
            j("FRA", "JFK", 120, 0.5, "fastest", false, null, "en", r, null, false)),
        new V("Q3 DE Preis am wichtigsten", "Anfrage Luftfracht MUC-SIN", "Hallo,\n\nLuftfracht MUC nach SIN, 260 kg, 1,2 cbm, bereit ab 2026-11-26. Wir bringen die Ware selbst zum Flughafen München. Am wichtigsten ist uns der Preis.\n\nGruß\nPia",
            j("MUC", "SIN", 260, 1.2, "cheapest", false, null, "de", r, null, false)),
        new V("Q4 PT mais rápida", "Cotação frete aéreo FRA-GRU", "Olá,\n\nprecisamos de uma cotação de frete aéreo de FRA para GRU, a opção mais rápida. 230 kg, 1,0 m³, pronto em 2026-11-26. Entregaremos a carga no aeroporto de Frankfurt.\n\nObrigado\nJoão",
            j("FRA", "GRU", 230, 1.0, "fastest", false, null, "pt", r, null, false)),
        new V("Q5 JA 最速", "FRA-NRT 航空輸送見積り", "お世話になっております。\n\nFRAからNRTまでの航空貨物のお見積りをお願いします。最速の便でお願いします。重量 210 kg、容積 1.1 cbm、2026-11-26 出荷可能です。FRA空港へは弊社で持ち込みます。\n\nよろしくお願いいたします。\n佐藤",
            j("FRA", "NRT", 210, 1.1, "fastest", false, null, "ja", r, null, false)),
        new V("Q6 not cheapest we need fastest", "RFQ FRA-LAX", "Hi,\n\nWe don't need the cheapest – we need the fastest. FRA to LAX, 90 kg, 0.3 cbm, ready 2026-11-26, delivered by us to FRA ourselves.\n\nThanks\nKim",
            j("FRA", "LAX", 90, 0.3, "fastest", false, null, "en", r, null, false)),
        new V("Q7 IT prezzo decisivo", "Quotazione MXP? no FRA-MIA", "Buongiorno,\n\nspedizione aerea FRA - MIA, 380 kg, 1,9 m³, pronta il 2026-11-26. Consegniamo noi la merce all'aeroporto di Francoforte. Il prezzo è il fattore decisivo.\n\nSaluti\nGiulia",
            j("FRA", "MIA", 380, 1.9, "cheapest", false, null, "it", r, null, false)),
        new V("Q8 KO cheapest", "FRA-ICN 항공 견적", "안녕하세요,\n\nFRA에서 ICN까지 항공 운송 견적 부탁드립니다. 가장 저렴한 옵션으로 부탁드립니다. 총중량 450 kg, 부피 2.0 cbm, 2026-11-26 출고 가능. FRA 공항까지는 저희가 직접 반입하겠습니다.\n\n감사합니다\n김민수",
            j("FRA", "ICN", 450, 2.0, "cheapest", false, null, "ko", r, null, false)),
        new V("D1 deliver by latest 28/11", "RFQ FRA-DXB", "Hi,\n\nplease quote FRA to DXB, 200 kg, 1 cbm, cheapest, ready 2026-11-24. We deliver to FRA ourselves. Must be delivered by latest 28/11.\n\nThanks\nAli",
            j("FRA", "DXB", 200, 1, "cheapest", false, null, "en", "2026-11-24", "2026-11-28", true)),
        new V("D2 arrive no later than 30.11.2026", "RFQ FRA-ORD", "Hi,\n\nFRA to ORD, 200 kg, 1 cbm, cheapest, ready on 2026-11-24. We deliver to FRA ourselves. The goods must arrive no later than 30.11.2026.\n\nThanks\nAli",
            j("FRA", "ORD", 200, 1, "cheapest", false, null, "en", "2026-11-24", "2026-11-30", true)),
        new V("D3 needs to be in Dubai by 11/30/2026", "RFQ FRA-DXB", "Hi,\n\nFRA to DXB, 200 kg, 1 cbm, cheapest, ready on 2026-11-24. We deliver to FRA ourselves. It needs to be in Dubai by 11/30/2026.\n\nThanks\nAli",
            j("FRA", "DXB", 200, 1, "cheapest", false, null, "en", "2026-11-24", "2026-11-30", true)),
        new V("D4 DE spätestens 30.11.2026", "Luftfracht FRA-SIN", "Hallo,\n\nLuftfracht FRA nach SIN, 200 kg, 1 cbm, günstigste Option, bereit ab 2026-11-24. Wir liefern selbst an den Flughafen. Die Sendung muss spätestens am 30.11.2026 in Singapur ankommen.\n\nGruß\nJan",
            j("FRA", "SIN", 200, 1, "cheapest", false, null, "de", "2026-11-24", "2026-11-30", true)),
        new V("D5 ISO deadline ok", "RFQ FRA-SIN", "Hi,\n\nFRA to SIN, 200 kg, 1 cbm, cheapest, ready on 2026-11-24. We deliver to FRA ourselves. Must reach SIN by 2026-12-01.\n\nThanks\nAli",
            j("FRA", "SIN", 200, 1, "cheapest", false, null, "en", "2026-11-24", "2026-12-01", true)),
        new V("D6 end of the month", "RFQ FRA-SIN", "Hi,\n\nFRA to SIN, 200 kg, 1 cbm, cheapest, ready on 2026-11-24. We deliver to FRA ourselves. It has to arrive by the end of the month.\n\nThanks\nAli",
            j("FRA", "SIN", 200, 1, "cheapest", false, null, "en", "2026-11-24", "2026-11-30", true)),
        new V("D7 ready 24.11.2026 no deadline", "RFQ FRA-SIN", "Hi,\n\nFRA to SIN, 200 kg, 1 cbm, cheapest option. Cargo ready on 24.11.2026. We deliver to FRA ourselves.\n\nThanks\nAli",
            j("FRA", "SIN", 200, 1, "cheapest", false, null, "en", "2026-11-24", null, false)),
        new V("D8 NL uiterlijk", "Offerte FRA-JFK", "Hallo,\n\nluchtvracht FRA naar JFK, 200 kg, 1 cbm, goedkoopste optie, klaar op 2026-11-24. Wij leveren zelf aan op FRA. De zending moet uiterlijk 2026-12-01 in New York zijn.\n\nGroet\nDaan",
            j("FRA", "JFK", 200, 1, "cheapest", false, null, "nl", "2026-11-24", "2026-12-01", true)),
        new V("U1 tonnes", "RFQ FRA-SIN", "Hi,\n\nFRA to SIN, 1.2 t, 6 cbm, cheapest, ready 2026-11-26. We deliver to FRA ourselves.\n\nThanks\nAli",
            j("FRA", "SIN", 1200, 6, "cheapest", false, null, "en", r, null, false)),
        new V("U2 2,500 lbs", "RFQ FRA-ORD", "Hi,\n\nFRA to ORD, 2,500 lbs, 150 cu ft, cheapest, ready 2026-11-26. We deliver to FRA ourselves.\n\nThanks\nAli",
            j("FRA", "ORD", 1133.98, 4.25, "cheapest", false, null, "en", r, null, false)),
        new V("U3 4 pallets à 250 kg", "Demande de devis FRA-YYZ", "Bonjour,\n\ndevis fret aérien FRA - YYZ, le moins cher. 4 palettes à 250 kg, 120x80x100 cm chacune, prêtes le 2026-11-26. Nous livrons nous-mêmes à l'aéroport de Francfort.\n\nCordialement\nPaul",
            j("FRA", "YYZ", 1000, 3.84, "cheapest", false, null, "fr", r, null, false)),
        new V("U4 4 Paletten je 250 kg", "Luftfrachtanfrage FRA-ORD", "Hallo,\n\nbitte Angebot Luftfracht FRA nach ORD, günstigste Variante. 4 Paletten je 250 kg, je 120x80x100 cm, bereit ab 2026-11-26. Wir bringen die Ware selbst zum Flughafen Frankfurt.\n\nGruß\nEva",
            j("FRA", "ORD", 1000, 3.84, "cheapest", false, null, "de", r, null, false)),
        new V("U5 dims in inches each", "RFQ FRA-JFK", "Hi,\n\nFRA to JFK, cheapest. 2 crates, 48 x 40 x 36 in each, total gross weight 300 kg, ready 2026-11-26. We deliver to FRA ourselves.\n\nThanks\nAli",
            j("FRA", "JFK", 300, 2.265, "cheapest", false, null, "en", r, null, false)),
        new V("U6 3 pallets of 200 kg", "RFQ FRA-SIN", "Hi,\n\nFRA to SIN, cheapest. 3 pallets of 200 kg each, 3.6 cbm in total, ready 2026-11-26. We deliver to FRA ourselves.\n\nThanks\nAli",
            j("FRA", "SIN", 600, 3.6, "cheapest", false, null, "en", r, null, false)),
        new V("U7 mm dims per piece", "RFQ MUC-ORD", "Hi,\n\nMUC to ORD, fastest. 5 boxes, 600 x 400 x 400 mm per piece, gross 75 kg total, ready 2026-11-26. We deliver to MUC ourselves.\n\nThanks\nAli",
            j("MUC", "ORD", 75, 0.48, "fastest", false, null, "en", r, null, false)),
        new V("M1 sea freight by sea", "Shipping quote HAM to SIN", "Hello,\n\nwe need to ship 2 pallets (800 kg, 3 cbm) by sea from Hamburg to Singapore, cheapest option, ready 2026-11-26.\n\nRegards\nOle",
            j("HAM", "SIN", 800, 3, "cheapest", false, null, "en", r, null, false)),
        new V("M2 ES transporte marítimo", "Cotización Hamburgo - Singapur", "Hola,\n\nnecesitamos cotización de transporte marítimo de Hamburgo a Singapur para 800 kg y 3 m3, la opción más barata. Listo el 2026-11-26.\n\nSaludos\nAna",
            j("HAM", "SIN", 800, 3, "cheapest", false, null, "es", r, null, false)),
        new V("M3 NL zeevracht", "Offerte zeevracht HAM - SIN", "Hallo,\n\ngraag een offerte voor zeevracht van Hamburg naar Singapore, 800 kg, 3 m3, goedkoopste optie, klaar op 2026-11-26.\n\nGroet\nBram",
            j("HAM", "SIN", 800, 3, "cheapest", false, null, "nl", r, null, false)),
        new V("M4 import PVG to FRA", "Import quote PVG-FRA", "Hello,\n\nplease quote air freight import from Shanghai (PVG) to Frankfurt (FRA), 500 kg, 2 cbm, cheapest, ready 2026-11-26 at origin.\n\nRegards\nLi",
            j("PVG", "FRA", 500, 2, "cheapest", false, null, "en", r, null, false)),
        new V("M5 DE Import nach MUC", "Importanfrage Luftfracht", "Hallo,\n\nbitte ein Angebot für einen Luftfracht-Import von ORD nach MUC, 300 kg, 1,5 cbm, günstigste Option, bereit 2026-11-26.\n\nGruß\nTim",
            j("ORD", "MUC", 300, 1.5, "cheapest", false, null, "de", r, null, false)),
        new V("G1 aerosol cans", "RFQ FRA-DXB", "Hi,\n\nFRA to DXB, 150 kg, 0.7 cbm, cheapest, ready 2026-11-26. We deliver to FRA ourselves. The cargo is cosmetics including aerosol spray cans.\n\nThanks\nAli",
            j("FRA", "DXB", 150, 0.7, "cheapest", false, null, "en", r, null, false)),
        new V("G2 paint DE Lackfarben", "Luftfracht FRA-SIN", "Hallo,\n\nLuftfracht FRA nach SIN, 150 kg, 0,7 cbm, günstigste Option, bereit ab 2026-11-26. Wir liefern selbst an. Ware: Lackfarben in Dosen (UN 1263).\n\nGruß\nJan",
            j("FRA", "SIN", 150, 0.7, "cheapest", false, null, "de", r, null, false)),
        new V("G3 Li-ion batteries", "RFQ FRA-LAX", "Hi,\n\nFRA to LAX, 150 kg, 0.7 cbm, cheapest, ready 2026-11-26. We deliver to FRA ourselves. The cargo is Li-ion battery packs for e-bikes.\n\nThanks\nAli",
            j("FRA", "LAX", 150, 0.7, "cheapest", false, null, "en", r, null, false)),
        new V("C1 quote in USD please", "RFQ FRA-ORD", "Hi,\n\nFRA to ORD, 200 kg, 1 cbm, cheapest, ready 2026-11-26. We deliver to FRA ourselves. Please quote in USD.\n\nThanks\nAli",
            j("FRA", "ORD", 200, 1, "cheapest", false, null, "en", r, null, false)),
        new V("C2 forwarded with misleading quote", "Fwd: RFQ", "Hi team,\n\nplease quote the fastest option to SIN for this, 300 kg, 1.5 cbm. We deliver to FRA ourselves, ready 2026-11-26.\n\nThanks\nMax\n\n---------- Forwarded message ----------\nFrom: old@example.com\nPlease quote cheapest FRA to ORD, 900 kg, 5 cbm, pickup from 60311 Frankfurt.",
            j("FRA", "SIN", 300, 1.5, "fastest", false, null, "en", r, null, false)),
        new V("C3 typos", "qoute FRA - SIN", "helo,\n\npls qoute cheapst airfreigth FRA to SIN, 250kg 1.2cbm, redy 2026-11-26, we bring to FRA ourselfs.\n\nthx\nBen",
            j("FRA", "SIN", 250, 1.2, "cheapest", false, null, "en", r, null, false)),
        new V("C4 table format", "RFQ", "Hello,\n\nPlease quote air freight, cheapest:\n\nOrigin      | FRA\nDestination | BKK\nWeight      | 410 kg\nVolume      | 2.1 cbm\nReady       | 2026-11-26\nPickup      | no, we deliver to FRA\n\nRegards\nIvo",
            j("FRA", "BKK", 410, 2.1, "cheapest", false, null, "en", r, null, false)),
        new V("C5 ZH fastest pickup", "空运询价 FRA-PVG", "您好，\n\n请报从法兰克福到上海浦东（PVG）的空运全包价，时效最短。上门提货地址：60311 Frankfurt。总毛重 330 公斤，体积 1.6 立方米，2026-11-26 备妥。\n\n谢谢\n王",
            j(null, "PVG", 330, 1.6, "fastest", true, "60", "zh", r, null, false)),
        new V("C6 EXW Incoterm with postcode", "RFQ EXW 85049 Ingolstadt to ORD", "Hello,\n\nIncoterm EXW 85049 Ingolstadt. Please quote cheapest air freight to ORD, 640 kg, 2.9 cbm, ready 2026-11-26.\n\nRegards\nFranz",
            j("MUC", "ORD", 640, 2.9, "cheapest", true, "85", "en", r, null, false)),
        new V("C7 FCA FRA no pickup", "RFQ FCA FRA to ORD", "Hello,\n\nIncoterm FCA Frankfurt Airport. Please quote cheapest air freight FRA to ORD, 640 kg, 2.9 cbm, ready 2026-11-26.\n\nRegards\nFranz",
            j("FRA", "ORD", 640, 2.9, "cheapest", false, null, "en", r, null, false)),
        new V("C8 dimensions cm each with pallets", "RFQ DUS-DXB", "Hi,\n\nDUS to DXB, fastest. 3 pallets, 120 x 80 x 150 cm each, 900 kg total. We deliver to DUS ourselves. Ready 2026-11-26.\n\nThanks\nAli",
            j("DUS", "DXB", 900, 4.32, "fastest", false, null, "en", r, null, false)),
        new V("C9 polite indirect pref", "RFQ FRA-SYD", "Dear all,\n\nwe would be grateful for an offer FRA to SYD, 260 kg, 1.3 cbm, ready 2026-11-26, delivered to FRA by ourselves. Since the budget for this project is tight, an economical routing would be appreciated.\n\nKind regards\nOlga",
            j("FRA", "SYD", 260, 1.3, "cheapest", false, null, "en", r, null, false)),
        new V("C10 we drop off at terminal no 'ourselves'", "RFQ HAM-DXB", "Hello,\n\ncheapest air freight HAM to DXB, 150 kg, 0.8 cbm, ready 2026-11-26. We drop it off at the terminal.\n\nRegards\nIvo",
            j("HAM", "DXB", 150, 0.8, "cheapest", false, null, "en", r, null, false)),
        new V("C11 DE Abholung in 50667 Köln", "Anfrage Luftfracht nach ORD", "Hallo,\n\nbitte Angebot für Luftfracht nach ORD, schnellste Verbindung. Abholung bei uns: Domstr. 1, 50667 Köln. 2 Paletten, 400 kg, 1,8 cbm, bereit ab 2026-11-26.\n\nGruß\nKlaus",
            j("CGN", "ORD", 400, 1.8, "fastest", true, "50", "de", r, null, false)),
        new V("C12 FR enlèvement chez nous", "Devis fret aérien vers JFK", "Bonjour,\n\nmerci de prévoir l'enlèvement chez nous, Hauptstraße 5, 70173 Stuttgart, et de nous faire un devis pour JFK, option la plus rapide. 220 kg, 1,0 m³, prêt le 2026-11-26.\n\nCordialement\nLuc",
            j("STR", "JFK", 220, 1.0, "fastest", true, "70", "fr", r, null, false)));
  }

  static java.util.stream.Stream<V> all() {
    return java.util.stream.Stream.concat(variants().stream(), structure().stream());
  }

  /** What the customer should get: a quote on the model's fields, a justified question, a decline or a DG request. */
  static String expected(V v) {
    if (v.name().startsWith("P13")) return "ask";
    if (v.name().startsWith("M")) return "decline";
    if (v.name().startsWith("G2")) return "dg";
    if (v.separate() != null) return "separate";
    return "quote";
  }

  /** Every variant runs through the live-like path for a whole quote window: never review, never two first replies. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("all")
  void variantReachesTheRightCustomerOutcome(V v) throws Exception {
    var h = new VariantHarness(temp, catalog, v.llm());
    if (v.separate() != null) h.separate = v.separate();
    h.receive(7, "customer@hackloop.mail", v.subject(), v.body());
    for (long t : new long[] {0, 5, 40, 80, 120, 200, 300, 330}) {
      h.clock.current = VariantHarness.START.plusSeconds(t);
      h.worker.work();
    }
    String stage = h.ledger.mail(7).get("stage").toString();
    var replies = h.outbound.stream().filter(m -> "customer@hackloop.mail".equals(m.get("to"))).toList();
    assertTrue(replies.size() <= 1, () -> "double send: " + h.outcome(7));
    assertNotEquals("review", stage, () -> h.outcome(7));
    switch (expected(v)) {
      case "ask" -> assertEquals(CustomerClarifications.WAITING, stage, () -> h.outcome(7));
      case "decline" -> assertEquals("sent", h.ledger.delivery("decline:7"), () -> h.outcome(7));
      case "dg" -> assertEquals("sent", h.ledger.delivery("dg-docs:7"), () -> h.outcome(7));
      case "separate" -> assertTrue(h.reason(7) != null && h.reason(7).startsWith("Waiting for a verified all-in rate"),
          () -> h.outcome(7));
      default -> {
        assertTrue(Set.of("sent", "collecting", "waiting for air price", "waiting for pickup").contains(stage),
            () -> h.outcome(7));
        assertTrue(h.ledger.delivery("quote:7") != null || replies.isEmpty(), () -> h.outcome(7));
        var llm = h.mapper.readTree(v.llm());
        var s = h.fields(7);
        assertEquals(llm.path("destination").asText(), s.destination(), () -> h.outcome(7));
        assertEquals(llm.path("weight_kg").asDouble(), s.weightKg().doubleValue(), 0.01, () -> h.outcome(7));
        assertEquals(llm.path("volume_cbm").asDouble(), s.volumeCbm().doubleValue(), 0.01, () -> h.outcome(7));
        assertEquals(llm.path("preference").asText(), s.preference(), () -> h.outcome(7));
        assertEquals(llm.path("pickup_zone").isNull() ? null : llm.path("pickup_zone").asText(), s.pickupZone(),
            () -> h.outcome(7));
      }
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Must be delivered by latest 28/11. Ready on 2026-11-24.|2026-11-24|2026-11-28",
      "Ready on 2026-11-24. The goods must arrive no later than 30.11.2026.|2026-11-24|2026-11-30",
      "Ready on 2026-11-24. It needs to be in Dubai by 11/30/2026.|2026-11-24|2026-11-30",
      "Bereit ab 2026-11-24. Die Sendung muss spätestens am 30.11.2026 in Singapur ankommen.|2026-11-24|2026-11-30",
      "Ready on 2026-11-24. It has to arrive by the end of the month.|2026-11-24|2026-11-30",
      "Cargo ready on 24.11.2026. Must arrive by 1 December 2026.|2026-11-24|2026-12-01",
      "Klaar op 2026-11-24. De zending moet uiterlijk 2026-12-01 in New York zijn.|2026-11-24|2026-12-01",
      "Ready on 2026-11-24. Delivery must reach ORD by Dec 2, 2026.|2026-11-24|2026-12-02"})
  void deadlinesInOtherFormatsAndLanguagesAreRead(String text, String ready, String arriveBy) {
    var window = MailParser.arrivalWindow(text);
    assertNotNull(window, text);
    assertEquals(java.time.LocalDate.parse(ready), window.readyOn());
    assertEquals(java.time.LocalDate.parse(arriveBy), window.arriveBy());
  }

  @ParameterizedTest
  @ValueSource(strings = {"We offer 24/7 service. Ready on 2026-11-24.", "Volume 1.5. Ready on 2026-11-24.",
      "Ready on 2026-11-24, 3/4 of the pallets are stackable."})
  void numbersThatOnlyLookLikeDatesStayNumbers(String text) {
    assertEquals("2026-11-24", RfqGateway.onlyIsoDate(MailParser.withIsoDates(text)));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', nullValues = "NULL", value = {
      "We need to ship 2 pallets by sea from Hamburg to Singapore.|sea freight",
      "Necesitamos transporte marítimo de Hamburgo a Singapur.|sea freight",
      "Graag een offerte voor zeevracht van Hamburg naar Singapore.|sea freight",
      "Please quote FRA to JFK, 200 kg, cheapest. Kind regards, Nordic Maritime GmbH|NULL",
      "Our sea freight forwarder let us down, so please quote air freight FRA to SIN.|NULL"})
  void otherTransportModesAreReadInMoreWords(String body, String mode) {
    assertEquals(mode, MailParser.nonAirMode("Quote request", body));
  }

  @ParameterizedTest
  @ValueSource(strings = {"We cannot deliver ourselves, so please arrange pickup at our Cologne plant.",
      "Please quote incl. pickup, not just airport-to-airport.", "No forklift on site. Please arrange collection.",
      "Pickup from our site, no loading ramp there."})
  void aPickupNextToAnUnrelatedNegationIsStillAPickup(String text) {
    assertTrue(MailParser.requestsPickup("RFQ", text), text);
  }
}
