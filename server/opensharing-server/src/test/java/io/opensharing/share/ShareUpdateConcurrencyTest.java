package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Concurrent changes to one share run one at a time, so each sees what the other wrote. */
class ShareUpdateConcurrencyTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";
  private static final int RUNS = 5;

  private static TestServer server;
  private static ExecutorService pool;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
    pool = Executors.newFixedThreadPool(2);
  }

  @AfterAll
  static void stopServer() {
    pool.shutdownNow();
    server.close();
  }

  @Test
  void concurrentAddsUnderOneAliasCannotBothSucceed() throws Exception {
    for (int i = 0; i < RUNS; i++) {
      String share = "racing-adds-" + i;
      createShare(share);

      List<Response> results =
          race(
              () ->
                  server.patch(
                      SHARES + "/" + share, "alice-token", add("main.sales.table1", "sales.t")),
              () ->
                  server.patch(
                      SHARES + "/" + share, "alice-token", add("main.sales.orders", "sales.t")));

      assertEquals(
          List.of(200, 409), results.stream().map(Response::status).sorted().toList());
      Response got = server.get(SHARES + "/" + share + "?include_shared_data=true", "alice-token");
      assertEquals(1, got.body().path("objects").size());
    }
  }

  @Test
  void anUpdateRacingADeleteFindsNoShare() throws Exception {
    for (int i = 0; i < RUNS; i++) {
      String share = "racing-delete-" + i;
      createShare(share);

      List<Response> results =
          race(
              () ->
                  server.patch(
                      SHARES + "/" + share, "alice-token", add("main.sales.table1", "sales.t")),
              () -> server.delete(SHARES + "/" + share, "alice-token"));

      assertTrue(Set.of(200, 404).contains(results.get(0).status()));
      assertEquals(204, results.get(1).status());
    }
  }

  private static void createShare(String share) {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"" + share + "\"}").status());
  }

  private static String add(String name, String sharedAs) {
    return "{\"updates\":[{\"action\":\"ADD\",\"dataObject\":{\"name\":\""
        + name
        + "\",\"type\":\"TABLE\",\"sharedAs\":\""
        + sharedAs
        + "\"}}]}";
  }

  private static List<Response> race(Callable<Response> first, Callable<Response> second)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Future<Response> a = pool.submit(() -> awaitThen(start, first));
    Future<Response> b = pool.submit(() -> awaitThen(start, second));
    start.countDown();
    return List.of(a.get(), b.get());
  }

  private static Response awaitThen(CountDownLatch start, Callable<Response> call)
      throws Exception {
    start.await();
    return call.call();
  }
}
