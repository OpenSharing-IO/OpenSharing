package io.opensharing;

import io.opensharing.runtime.OpenSharing;

/** Runnable distribution of OpenSharing for standalone deployments. */
public final class OpenSharingServer {

  private OpenSharingServer() {}

  public static void main(String[] args) {
    OpenSharing.runStandalone(args);
  }
}
