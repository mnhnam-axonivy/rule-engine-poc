package com.axonivy.utils.rule.engine.doc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The tutorial on a real Axon Ivy Engine: the process start "Rule Engine Tutorial" opens a page that has its
 * texts, its pages and every script of the interactive walkthroughs.
 * <p>Run with {@code mvn verify -Pweb.test}: the build starts an engine of its own and deploys this project
 * with the demo it requires. The walkthroughs themselves run in a browser and are tested there:
 * internal/interactive_app_builder, npm run smoke.</p>
 * <p>With {@code -Dtutorial.hold.seconds=300} the engine stays up that long after the tests and the link of
 * the tutorial is printed: to look at it in a browser, or to run {@code npm run smoke -- --url=<link>}.</p>
 */
class TutorialWebIT {

  private static final String START = "/pro/rule-engine-doc/19A8D1C0FFEE0003/tutorial.ivp";
  private static final Pattern RESOURCE = Pattern.compile("(?:src|href)=\"([^\"]*faces\\.resource/[^\"]*)\"");
  private static final List<String> PAGES = List.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11");

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .cookieHandler(new CookieManager())
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private static HttpResponse<String> page;
  private static String html;

  @BeforeAll
  static void openTutorial() throws Exception {
    page = get(start());
    html = page.body();
  }

  @AfterAll
  static void keepEngineForABrowser() throws InterruptedException {
    int seconds = Integer.getInteger("tutorial.hold.seconds", 0);
    if (seconds > 0) {
      System.out.println("Tutorial: " + start() + " (the engine stays up for " + seconds + " seconds)");
      Thread.sleep(seconds * 1000L);
    }
  }

  @Test
  void startOpensTheTutorial() {
    assertThat(page.statusCode()).as("%s%n%s", page.uri(), text(html)).isEqualTo(200);
    assertThat(html)
        .as("labels from the CMS of the tutorial")
        .contains("Axon Ivy Rules Engine Tutorial")
        .contains("What is DMN?")
        .contains("Handle errors")
        .contains("Edit rules in the Portal")
        .contains("Frequently asked questions");
    assertThat(html)
        .as("the overview: the path of the pages, from easy to hard")
        .contains("id=\"re-steps\"")
        .contains("data-page=\"01\"")
        .contains("data-page=\"11\"")
        .contains("Basics")
        .contains("id=\"view-detail\"");
  }

  @Test
  void pagesComeFromTheCms() {
    for (String id : PAGES) {
      assertThat(markdown(id)).as("Markdown of page " + id).startsWith("# ").contains("\n## ");
    }
    assertThat(markdown("03"))
        .as("page 03 is the setup")
        .contains("## Prerequisites")
        .contains("docker compose up -d");
    assertThat(markdown("04"))
        .as("Markdown is escaped, so the browser shows the XML in it as written")
        .contains("&lt;dependency&gt;")
        .doesNotContain("<dependency>");
  }

  @Test
  void scriptsOfTheWalkthroughsAreServed() throws Exception {
    var resources = new ArrayList<String>();
    Matcher matcher = RESOURCE.matcher(html);
    while (matcher.find()) {
      resources.add(matcher.group(1).replace("&amp;", "&"));
    }
    for (String name : List.of("tutorial.css", "highlight-github-dark.min.css", "highlight.min.js", "marked.min.js",
        "tutorial.js", "interactive-runtime.min.js", "guide-01.js", "guide-02.js", "guide-03.js",
        "guide-04.js", "guide-05.js", "guide-06.js", "guide-07.js", "guide-08.js", "guide-11-3.js")) {
      String link = resources.stream().filter(resource -> resource.contains("/" + name)).findFirst().orElse(null);
      assertThat(link).as("the page loads " + name + ", it has " + resources).isNotNull();
      HttpResponse<String> file = get(page.uri().resolve(link).toString());
      assertThat(file.statusCode()).as(link).isEqualTo(200);
      assertThat(file.body()).as(name).isNotBlank();
      if (name.startsWith("guide-")) {
        assertThat(file.body()).as(name + " registers its lesson").contains(name.replace(".js", ""));
      }
    }
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),
        BodyHandlers.ofString());
  }

  /** @return the link of the process start "Rule Engine Tutorial" on the test engine */
  private static String start() {
    return engine() + System.getProperty("test.engine.app", "ruleenginedoc") + START;
  }

  /** @return where the test engine listens, with a slash at its end */
  private static String engine() {
    String url = System.getProperty("test.engine.url", "http://localhost:8080/");
    return url.endsWith("/") ? url : url + "/";
  }

  /** The Markdown of a page, as the page hands it to the browser. */
  private static String markdown(String id) {
    String start = "<div id=\"re-md-" + id + "\">";
    int from = html.indexOf(start);
    assertThat(from).as("the page contains " + start + ": " + text(html)).isGreaterThanOrEqualTo(0);
    return html.substring(from + start.length(), html.indexOf("</div>", from)).strip();
  }

  /** The text of a page without its markup: enough to read the message of an error page. */
  private static String text(String body) {
    if (body == null) {
      return "";
    }
    String text = body.replaceAll("(?s)<(script|style)[^>]*>.*?</(script|style)>", " ").replaceAll("<[^>]+>", " ")
        .replaceAll("[ \\t\\r\\n]+", " ").strip();
    return text.substring(0, Math.min(1500, text.length()));
  }
}
