package ai.aeiou.dispatch;
import ai.aeiou.freight.MailParser;
import java.io.File;

class Diagnose {
  public static void main(String[] a) throws Exception {
    var rounds = RoundReplay.JSON.readTree(new File(a[0])).path("rounds");
    var catalog = new DispatchApplication().catalog();
    for (int n : new int[]{7, 12, 28}) {
      var raw = rounds.get(n-1).get("rfq");
      System.out.println(n + " " + MailParser.request(raw.path("subject").asText(), raw.path("body").asText(), false, catalog));
    }
  }
}
