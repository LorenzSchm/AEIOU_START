package ai.aeiou.forwarder.domain;

import java.util.regex.Pattern;

public final class BookingApproval {
  private BookingApproval() {}

  private static final Pattern APPROVAL =
      Pattern.compile(
          "(?iu)(?:(?:hi|hello|dear team)[ ,!.]*\\s+)?(?:(?:thank you for (?:your|the)"
              + " (?:offer|quote)|thanks|thank you|yes|that works for us|we accept (?:the|your)"
              + " (?:offer|quote))[,.! ]+)*(?:(?:please )?(?:go ahead and |proceed to )?book(?:"
              + " (?:it|this|this shipment|the shipment|this option|the quoted option))?|we"
              + " accept(?: (?:the|your) (?:offer|quote))?|wir akzeptieren(?: (?:das|ihr)"
              + " angebot)?|please proceed with (?:the |this )?booking|bitte (?:so |wie angeboten"
              + " )?buchen)[.! ]*");
  private static final Pattern SIGNOFF =
      Pattern.compile(
          "(?im)^\\s*(?:best (?:greetings|regards|wishes)|kind regards|regards|cheers|bestens|mit"
              + " freundlichen gr(?:ü|ue|u)(?:ss|ß)en|viele gr(?:ü|ue|u)(?:ss|ß)e|--)(?:[,!"
              + " .]|$).*|^\\h*thanks(?: a lot| again)? and have a"
              + " (?:lovely|great|nice|good|wonderful) (?:day|evening|weekend)[.!]*\\h*$");
  private static final Pattern NAME_SIGNATURE =
      Pattern.compile(
          "[\\p{Lu}][\\p{L}’'&.-]*(?: [\\p{Lu}][\\p{L}’'&.-]*){0,5}(?:\\R[\\p{Lu}][\\p{L}’'&.-]*(?:"
              + " [\\p{Lu}][\\p{L}’'&.-]*){0,5}){0,3}");

  public static boolean accepted(String body) {
    String authored = ShipmentFacts.fresh(body);
    if (Pattern.compile(
            "(?iu)\\b(?:if|unless|until|hold|wait|cancel|not|never|provided|subject to|only"
                + " when|wenn|falls|sofern|nicht|stornieren)\\b")
        .matcher(authored)
        .find()) return false;
    String text = SIGNOFF.split(authored, 2)[0].strip();
    String[] blocks = text.split("\\R\\h*\\R", 2);
    if (blocks.length > 1 && NAME_SIGNATURE.matcher(blocks[1].strip()).matches()) text = blocks[0];
    return APPROVAL.matcher(text.replaceAll("\\s+", " ").strip()).matches();
  }
}
