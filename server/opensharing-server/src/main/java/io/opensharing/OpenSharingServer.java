package io.opensharing;

import org.springframework.boot.SpringApplication;

/** Runnable standalone OpenSharing server. */
public final class OpenSharingServer {

  private OpenSharingServer() {}

  public static void main(String[] args) {
    SpringApplication.run(OpenSharingApplication.class, args);
  }
}
