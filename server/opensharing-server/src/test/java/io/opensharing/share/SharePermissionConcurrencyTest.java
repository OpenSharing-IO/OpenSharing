package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Concurrent permission updates on one share run one at a time, so each sees the other's. */
class SharePermissionConcurrencyTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";
  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";
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
  void concurrentGrantsOfTheSamePrivilegeBothSucceed() throws Exception {
    for (int i = 0; i < RUNS; i++) {
      String share = "racing-grants-" + i;
      createShareAndRecipient(share);

      List<Response> results = race(() -> change(share, "add"), () -> change(share, "add"));

      assertEquals(List.of(200, 200), statuses(results));
    }
  }

  @Test
  void concurrentRevokesOfTheSamePrivilegeFindItOnce() throws Exception {
    for (int i = 0; i < RUNS; i++) {
      String share = "racing-revokes-" + i;
      createShareAndRecipient(share);
      assertEquals(200, change(share, "add").status());

      List<Response> results = race(() -> change(share, "remove"), () -> change(share, "remove"));

      assertEquals(List.of(200, 404), statuses(results));
    }
  }

  // The recipient is named after the share.
  private static void createShareAndRecipient(String share) {
    assertEquals(201, server.post(SHARES, "alice-token", "{\"name\":\"" + share + "\"}").status());
    String body =
        "{\"name\":\"" + share + "\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":1}";
    assertEquals(201, server.post(RECIPIENTS, "alice-token", body).status());
  }

  private static Response change(String share, String action) {
    return server.patch(
        SHARES + "/" + share + "/permissions",
        "alice-token",
        "{\"changes\":[{\"recipientName\":\"" + share + "\",\"" + action + "\":[\"SELECT\"]}]}");
  }

  private static List<Integer> statuses(List<Response> results) {
    return results.stream().map(Response::status).sorted().toList();
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
