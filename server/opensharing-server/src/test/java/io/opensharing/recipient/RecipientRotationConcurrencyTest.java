package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RecipientRotationConcurrencyTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";
  private static final int ROTATIONS = 8;

  @Test
  void concurrentRotationsLeaveOnlyOneRedeemableActivation() throws Exception {
    try (TestServer server = TestServer.start()) {
      Response created =
          server.post(
              RECIPIENTS,
              "alice-token",
              "{\"name\":\"racer\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":1}");
      assertEquals(201, created.status());

      ExecutorService pool = Executors.newFixedThreadPool(ROTATIONS);
      List<String> activations = new ArrayList<>();
      try {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> rotations = new ArrayList<>();
        for (int i = 0; i < ROTATIONS; i++) {
          rotations.add(
              pool.submit(
                  () -> {
                    start.await();
                    return server.post(
                        RECIPIENTS + "/racer/rotate-token",
                        "alice-token",
                        "{\"tokenExpirationDays\":1}");
                  }));
        }
        start.countDown();
        for (Future<Response> rotation : rotations) {
          Response rotated = rotation.get();
          assertEquals(201, rotated.status());
          activations.add(URI.create(rotated.body().path("activationUrl").asText()).getPath());
        }
      } finally {
        pool.shutdownNow();
      }

      // Each rotation supersedes the one before it, so only the last activation still redeems.
      int redeemed = 0;
      for (String activation : activations) {
        if (server.get(activation, null).status() == 200) {
          redeemed++;
        }
      }
      assertEquals(1, redeemed);
    }
  }
}
