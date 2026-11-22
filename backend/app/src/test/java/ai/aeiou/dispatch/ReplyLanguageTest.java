package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Real mailbox cases that were answered in the wrong language. */
class ReplyLanguageTest {
  private static final String FORWARDED = """


      -----Original Message-----
      Sent: 2026-10-31
      Subject: VIENNA AIRPORT - TNR / 123 CRATES 433 KGS

      Dear team,

      pls quote air freight ex VIENNA AIRPORT to TNR:
      123 CRATES / 433 KGS / 14.1 CBM, printed marketing material
      FCA, cargo ready next week.

      Thanks
      """;

  private static Map<String, Object> mail(String subject, String body) {
    return Map.of("subject", subject, "body", body);
  }

  @Test
  void request48586KoreanWithHanjaAndGermanSignature() {
    var request = mail("DFW행 항공운임 견적 요청", """
        안녕하세요.

        Germany Dortmund 44780에 있는 저희 사업장에서 픽업하여 DFW (Dallas, US)까지 보내는 항공화물 견적을 부탁드립니다. 출발 공항까지의 트럭 운송도 귀사에서安排해 주시기 바랍니다.

        화물은 스포츠 장비 4개이며, 총중량은 159 kg, 부피는 1.44 cbm입니다. 2026-11-24부터 픽업 가능합니다.

        가장 저렴한 옵션으로 부탁드립니다. 픽업과 공항까지의 트럭 운송을 포함해 모든 비용이 포함된 총액을 EUR로 알려주시기 바랍니다.

        Viele Gruesse / Many greetings
        Deniz Yilmaz
        Rheintal Elektronik GmbH
        """);
    assertEquals("ko", CustomerLanguage.replyTag(request));
    // quote:48586 went out in English although the rendering call answered "ko".
    String sent = """
        Good morning Deniz,

        The lowest rate we found for your 159 kg shipment from DUS to DFW is with Meridian Freightways at EUR 1051.33 all in.

        That includes collection from postcode 44780. Allow about 4 days and 22 hours from collection to arrival.

        Would you like us to book it for you?

        Best regards,
        Team AEIOU""";
    assertFalse(RfqGateway.inLanguage(sent, "ko", List.of("Meridian Freightways")));
    String korean = """
        안녕하세요 Deniz 님,

        DUS에서 DFW까지 159 kg 화물의 최저가 옵션은 Meridian Freightways이며 총액은 EUR 1051.33입니다.

        우편번호 44780에서의 픽업이 포함됩니다. 픽업부터 도착까지 약 4일 22시간이 소요됩니다.

        예약을 진행해 드릴까요?

        감사합니다.
        AEIOU 팀""";
    assertTrue(RfqGateway.inLanguage(korean, "ko", List.of("Meridian Freightways")));
    assertTrue(RfqGateway.sameFacts(sent, korean, List.of("Meridian Freightways")));
  }

  @Test
  void request44313KoreanSubjectAndBody() {
    var request = mail("KUL행 항공 운송 견적 요청", """
        안녕하세요,

        2026-11-27에 독일 Fulda 36030에 있는 저희 사업장에서 픽업 가능합니다. 공항까지 트럭 운송도 함께 주선해 주세요.

        화물은 기계 부품 12개이며, 총중량은 2802.08 lb, 부피는 150.45 cubic feet입니다.

        Mira Petersen
        Nordlicht Audio
        """);
    assertEquals("ko", CustomerLanguage.replyTag(request));
    assertEquals(CustomerLanguage.ENGLISH, CustomerLanguage.of(request), "Korean is rendered from English");
    // A follow-up reply such as the customer's "픽업지의 5자리 우편번호는 36030입니다." stays Korean.
    assertEquals("ko", MessageLanguage.detect("안녕하세요.\n\n픽업지의 5자리 우편번호는 36030입니다(독일).\n\nMira Petersen\nNordlicht Audio"));
  }

  @Test
  void request39210KoreanBehindEnglishForwardedSubject() {
    var request = mail("RE: FW: BRUSSELS AIRPORT - TLV / 19 CTN 2,796 KGS", """
        안녕하세요,

        Germany Mainz 55274 소재 당사 사업장에서 픽업해 RUH (Riyadh, SA)까지 보내는 항공운송 견적 부탁드립니다. 픽업은 2026-11-24부터 가능하며, 공항까지의 픽업 트럭도 배차해 주세요.

        Sara
        Lindqvist Medtech
        """ + FORWARDED);
    assertEquals("ko", CustomerLanguage.replyTag(request));
  }

  @Test
  void request38889TurkishWithEnglishSignatureAndForward() {
    var request = mail("RE: FW: VIENNA AIRPORT - TNR / 123 CRATES 433 KGS", """
        Merhaba,

        BKK (Bangkok, TH) varışlı bir hava kargo gönderimiz için teklif rica ederim. Yükün depomuzdan alınması ve kalkış havalimanına kamyonla taşınması tarafınızca organize edilmeli. Yük 2026-12-04 tarihinde alıma hazır olacak.

        En ucuz seçeneği istiyoruz. Depomuzdan alım dahil, tüm masrafları kapsayan fiyatınızı EUR cinsinden iletebilir misiniz?

        Teşekkürler.

        Best greetings from Regensburg
        Tobias Auer
        Versand / Shipping
        Auer Maschinenbau GmbH
        """ + FORWARDED);
    assertEquals("tr", CustomerLanguage.replyTag(request));
    assertEquals(CustomerLanguage.TURKISH, CustomerLanguage.of(request));
    // pickup-postcode:38889 mixed a Turkish greeting with an English question.
    String sent = "Merhaba,\n\nCould you please confirm the full five-digit pickup postcode?\n\nSaygılarımızla,\nAEIOU Ekibi";
    assertFalse(RfqGateway.inLanguage(sent, "tr", List.of()));
  }

  @Test
  void request19529FrenchWithEnglishSignOff() {
    var request = mail("Devis fret aérien DUS–SYD – option la plus rapide", """
        Bonjour,

        J’espère que tout va bien de votre côté. J’ai un envoi de pièces détachées pour machines d’emballage à faire partir vers Sydney, AU (SYD). La marchandise sera prête le 2026-11-26 et nous la déposerons nous-mêmes à l’aéroport de Duesseldorf (DUS).

        Nous souhaitons l’option la plus rapide. Pouvez-vous me communiquer votre tarif tout compris en EUR, ainsi que le premier départ possible et le délai de transit ?

        Merci d’avance et bonne journée !

        Cheers,
        Klaus Weidner
        Weidner Spielwaren GmbH, Nuremberg
        """);
    assertEquals("fr", CustomerLanguage.replyTag(request));
    assertEquals(CustomerLanguage.FRENCH, CustomerLanguage.of(request));
    String sent = "Good evening Klaus,\n\nCould you please confirm the full five-digit pickup postcode and town?\n\nBest regards,\nTeam AEIOU";
    assertFalse(RfqGateway.inLanguage(sent, "fr", List.of()));
  }

  @Test
  void requests19873And20148GermanAndChinese() {
    assertEquals("de", CustomerLanguage.replyTag(mail("Luftfrachtanfrage nach RUH", """
        Guten Tag,

        bitte bieten Sie uns die günstigste Luftfrachtoption nach RUH (Riyadh, SA) an. Die Sendung steht ab dem 2026-11-25 bei uns in 36551 (Fulda), Deutschland, zur Abholung bereit.

        Vielen Dank.

        Viele Gruesse / Many greetings
        Deniz Yilmaz
        """)));
    assertEquals("zh", CustomerLanguage.replyTag(mail("空运询价：Cologne → LIM", """
        您好，

        希望您这周过得顺利！想请您帮忙看看一票发往 LIM（Lima, PE）的空运。这票货我们想走最便宜的方案。

        能否请您报一个包含上门提货和空运的 EUR 全包价？谢谢！

        Thanks a lot and have a lovely day!
        Petra Hofmann
        Export Desk, Hofmann Textilwerk KG
        """)));
  }

  @Test
  void request41536EnglishFromGermanSender() {
    // The rendering call chose German for this English request because of the signature.
    var request = mail("Air freight quote to RUH", """
        Hello,

        Could you please quote the cheapest air freight option to RUH (Riyadh, SA)? The shipment will be ready for pickup on 2026-11-28 at our site in 45806 (Essen), Germany.

        Please attach your offer as a PDF to your reply, as our purchasing department needs it for filing.

        Thank you.

        Best greetings from Regensburg
        Tobias Auer
        Versand / Shipping
        Auer Maschinenbau GmbH
        """);
    assertEquals("en", CustomerLanguage.replyTag(request));
    assertFalse(RfqGateway.inLanguage("Guten Morgen Tobias,\n\nDer niedrigste Preis, den wir für Ihre Sendung von DUS nach RUH gefunden haben, ist mit Riverbend Airlines Cargo zu EUR 3029.32 alles inklusive.", "en", List.of("Riverbend Airlines Cargo")));
  }

  @Test
  void otherScriptsAndLanguages() {
    assertEquals("ja", MessageLanguage.detect("お世話になっております。成田空港までの航空貨物の見積もりをお願いいたします。貨物は12個、総重量は540kgです。"));
    assertEquals("zh", MessageLanguage.detect("您好，请为我们报一个从法兰克福到上海的空运全包价，货物共十二件。"));
    assertEquals("ru", MessageLanguage.detect("Здравствуйте, просим предоставить ставку на авиаперевозку из Франкфурта в Москву, груз двенадцать мест."));
    assertEquals("ar", MessageLanguage.detect("مرحبا، نرجو تزويدنا بعرض سعر للشحن الجوي من فرانكفورت إلى دبي لشحنة من اثنتي عشرة قطعة."));
    assertEquals("th", MessageLanguage.detect("สวัสดีครับ ขอใบเสนอราคาค่าขนส่งทางอากาศจากแฟรงก์เฟิร์ตไปกรุงเทพครับ"));
    assertEquals("es", MessageLanguage.detect("Hola, ¿podrían cotizarnos la opción más económica de carga aérea a Ciudad de México? La mercancía estará lista para recoger la próxima semana."));
    assertEquals("it", MessageLanguage.detect("Buongiorno, potreste inviarci un preventivo per la spedizione aerea più economica verso Milano? La merce sarà pronta per il ritiro la prossima settimana."));
    assertEquals("pt", MessageLanguage.detect("Olá, poderiam enviar uma cotação para o frete aéreo mais barato até São Paulo? A mercadoria estará pronta para coleta na próxima semana."));
  }

  @Test
  void explicitRequestWins() {
    assertEquals("en", CustomerLanguage.replyTag(mail("Luftfrachtanfrage", """
        Guten Tag,

        bitte senden Sie uns ein Angebot für Luftfracht nach JFK. Please reply in English, our buyer reads along.
        """)));
  }

  @Test
  void factsMustSurviveTheRendering() {
    String approved = "The lowest rate is with Meridian Freightways at EUR 1051.33 all in, 159 kg from DUS to DFW.";
    assertTrue(RfqGateway.sameFacts(approved,
        "최저가는 Meridian Freightways이며 총액은 EUR 1051.33, DUS에서 DFW까지 159 kg입니다.", List.of("Meridian Freightways")));
    assertFalse(RfqGateway.sameFacts(approved,
        "최저가는 Meridian Freightways이며 총액은 EUR 1015.33, DUS에서 DFW까지 159 kg입니다.", List.of("Meridian Freightways")));
    assertFalse(RfqGateway.sameFacts(approved,
        "최저가는 Meridian이며 총액은 EUR 1051.33, DUS에서 DFW까지 159 kg입니다.", List.of("Meridian Freightways")));
  }
}
