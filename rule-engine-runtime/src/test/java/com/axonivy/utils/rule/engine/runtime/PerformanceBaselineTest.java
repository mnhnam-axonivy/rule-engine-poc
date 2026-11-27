package com.axonivy.utils.rule.engine.runtime;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.runtime.dmn.DmnCompiler;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Performance baseline for a decision table with 1,000 rules (hit policy FIRST).
 * Run with <code>mvn test -Pperformance</code>, the numbers are written to target/performance-baseline.md.
 */
@QuarkusTest
@Tag("performance")
class PerformanceBaselineTest {

  private static final int RULES = 1000;
  private static final int WARMUP = 3000;
  private static final int MEASURED = 10000;
  private static final int THREADS = 8;

  @Inject
  DmnCompiler compiler;

  @Inject
  DmnEvaluator evaluator;

  @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
  int port;

  private final List<String> report = new ArrayList<>();

  @Test
  void decisionTableWith1000Rules() throws Exception {
    String dmn = TestModels.decisionTable(RULES);
    report.add("# Performance baseline: decision table with " + RULES + " rules");
    report.add("");
    report.add("- Model: 2 inputs (number range, string), 1 output, hit policy FIRST, " + RULES + " rules, "
        + (dmn.length() / 1024) + " KB of DMN");
    report.add("- Machine: " + Runtime.getRuntime().availableProcessors() + " cores, Java "
        + Runtime.version().feature() + ", " + System.getProperty("os.name"));
    report.add("");

    long start = System.nanoTime();
    var findings = compiler.validate(dmn);
    long validated = System.nanoTime();
    var model = compiler.compile("large", 1, dmn);
    long compiled = System.nanoTime();
    assertThat(findings).noneMatch(message -> message.isError());
    report.add("| Step | Time |");
    report.add("|---|---|");
    report.add(row("Validate (schema, model, compilation)", millis(validated - start)));
    report.add(row("Compile (load a version)", millis(compiled - validated)));

    // in the engine, without HTTP
    for (int i = 0; i < WARMUP; i++) {
      evaluator.evaluate(model, null, input(i % RULES));
    }
    report.add("");
    report.add("**In the engine** (no HTTP), " + MEASURED + " evaluations per scenario");
    report.add("");
    report.add("| Scenario | mean | p50 | p95 | p99 |");
    report.add("|---|---|---|---|---|");
    report.add(latencies("First rule matches", () -> evaluator.evaluate(model, null, input(0))));
    report.add(latencies("Rule 500 matches", () -> evaluator.evaluate(model, null, input(499))));
    report.add(latencies("Last rule matches", () -> evaluator.evaluate(model, null, input(RULES - 1))));
    report.add(latencies("No rule matches", () -> evaluator.evaluate(model, null, Map.of("amount", -1, "segment", "none"))));
    var last = evaluator.evaluate(model, null, input(RULES - 1));
    assertThat(last.results().get("band").toString()).isEqualTo(String.valueOf(RULES - 1));

    // through the REST API
    given().contentType(ContentType.XML).body(dmn).put("/api/models/perf-large/versions/1").then().statusCode(200);
    var http = HttpClient.newHttpClient();
    Callable<Object> call = () -> post(http, RULES - 1);
    for (int i = 0; i < WARMUP; i++) {
      call.call();
    }
    report.add("");
    report.add("**Through the REST API** (localhost, JSON, last rule matches)");
    report.add("");
    report.add("| Scenario | mean | p50 | p95 | p99 |");
    report.add("|---|---|---|---|---|");
    report.add(latencies("1 client, sequential", call));

    var pool = Executors.newFixedThreadPool(THREADS);
    try {
      int perThread = MEASURED / THREADS;
      long begin = System.nanoTime();
      var futures = new ArrayList<Future<Object>>();
      for (int thread = 0; thread < THREADS; thread++) {
        futures.add(pool.submit(() -> {
          for (int i = 0; i < perThread; i++) {
            post(http, RULES - 1);
          }
          return null;
        }));
      }
      for (var future : futures) {
        future.get();
      }
      double seconds = (System.nanoTime() - begin) / 1_000_000_000d;
      report.add("");
      report.add(String.format(Locale.ROOT, "Throughput with %d parallel clients: **%.0f evaluations/s**",
          THREADS, (perThread * THREADS) / seconds));
    } finally {
      pool.shutdown();
    }

    Path file = Path.of("target", "performance-baseline.md");
    Files.write(file, report);
    report.forEach(System.out::println);
  }

  private String post(HttpClient http, int rule) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/models/perf-large/versions/1/evaluate"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"input\":{\"amount\":" + (rule * 100 + 50) + ",\"segment\":\"S" + (rule % 50) + "\"}}"))
        .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(response.statusCode() + " " + response.body());
    }
    return response.body();
  }

  private static Map<String, Object> input(int rule) {
    return Map.of("amount", rule * 100 + 50, "segment", "S" + (rule % 50));
  }

  private static String latencies(String scenario, Callable<?> action) throws Exception {
    long[] nanos = new long[MEASURED];
    for (int i = 0; i < MEASURED; i++) {
      long start = System.nanoTime();
      action.call();
      nanos[i] = System.nanoTime() - start;
    }
    Arrays.sort(nanos);
    double mean = Arrays.stream(nanos).average().orElse(0);
    return "| " + scenario + " | " + millis((long) mean) + " | " + millis(nanos[MEASURED / 2]) + " | "
        + millis(nanos[(int) (MEASURED * 0.95)]) + " | " + millis(nanos[(int) (MEASURED * 0.99)]) + " |";
  }

  private static String row(String step, String time) {
    return "| " + step + " | " + time + " |";
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000d);
  }
}
