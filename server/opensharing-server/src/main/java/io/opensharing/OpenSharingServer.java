package io.opensharing;

import io.opensharing.runtime.OpenSharing;

/** Runnable standalone OpenSharing server. */
public final class OpenSharingServer {

  private OpenSharingServer() {}

  public static void main(String[] args) {
    OpenSharing.runStandalone(args);
  }
}
