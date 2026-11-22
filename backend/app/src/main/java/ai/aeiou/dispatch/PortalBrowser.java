package ai.aeiou.dispatch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Sends portal page requests from a real Chromium so they pass the Cloudflare managed challenge in
 * front of the hosted rate page. The browser starts with a {@code cf_clearance} cookie issued to
 * the team and the User-Agent it was issued under (Cloudflare binds the two), opens the page once,
 * and then posts with {@code fetch()} from that origin, so every request carries the browser's own
 * cookies and TLS. Cloudflare's refreshed clearance cookies stay in the browser context.
 */
final class PortalBrowser implements AutoCloseable {
  record Reply(int status, String body, String retryAfter, boolean challenged) {}

  private static final String FETCH = """
      async ([url, headers, body]) => {
        const r = await fetch(url, {method: 'POST', headers, body, credentials: 'include',
          signal: AbortSignal.timeout(8000)});
        return {status: r.status, body: await r.text(), retryAfter: r.headers.get('retry-after') || '',
          mitigated: r.headers.get('cf-mitigated') || ''};
      }""";

  private final String url;
  private final String clearance;
  private final String userAgent;
  private final String channel;
  private final boolean headless;
  private Playwright playwright;
  private Browser browser;
  private Page page;

  PortalBrowser(String url, String clearance, String userAgent, String channel, boolean headless) {
    this.url = url;
    this.clearance = clearance;
    this.userAgent = userAgent;
    this.channel = channel;
    this.headless = headless;
  }

  /** POSTs to the portal page; throws {@link PortalPage.Down} when the browser cannot get through. */
  synchronized Reply post(Map<String, String> headers, String body) throws PortalPage.Down {
    try {
      open();
      @SuppressWarnings("unchecked")
      var reply = (Map<String, Object>) page.evaluate(FETCH, List.of(url, headers, body));
      int status = ((Number) reply.get("status")).intValue();
      return new Reply(status, String.valueOf(reply.get("body")), String.valueOf(reply.get("retryAfter")),
          "challenge".equals(reply.get("mitigated")));
    } catch (PlaywrightException ex) {
      // A crashed or wedged browser is rebuilt on the next request.
      close();
      throw new PortalPage.Down("Portal browser: " + ex.getMessage().lines().findFirst().orElse(""));
    }
  }

  private void open() throws PortalPage.Down {
    if (page != null && !page.isClosed()) return;
    if (playwright == null) playwright = Playwright.create();
    var launch = new BrowserType.LaunchOptions().setHeadless(headless);
    if (!channel.isBlank()) launch.setChannel(channel);
    browser = playwright.chromium().launch(launch);
    var options = new Browser.NewContextOptions();
    if (!userAgent.isBlank()) options.setUserAgent(userAgent);
    BrowserContext context = browser.newContext(options);
    var origin = URI.create(url);
    context.addCookies(List.of(new Cookie("cf_clearance", clearance)
        .setDomain(origin.getHost()).setPath("/").setSecure(true).setHttpOnly(true)
        .setSameSite(SameSiteAttribute.NONE)));
    page = context.newPage();
    var response = page.navigate(url);
    if (response == null || "challenge".equals(response.headerValue("cf-mitigated"))) {
      close();
      throw new PortalPage.Down("Cloudflare did not accept cf_clearance; it expired or does not match "
          + "this User-Agent and IP");
    }
  }

  @Override
  public synchronized void close() {
    try {
      if (browser != null) browser.close();
    } catch (PlaywrightException ignored) {
      // Already gone.
    }
    browser = null;
    page = null;
  }
}
