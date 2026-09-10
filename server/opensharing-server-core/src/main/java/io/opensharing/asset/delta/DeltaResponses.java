package io.opensharing.asset.delta;

import io.opensharing.asset.storage.UrlSigners;
import io.opensharing.config.OpenSharingProperties;
import org.springframework.stereotype.Component;

/**
 * The two ways a Delta read is written down, and the signing they share.
 *
 * <p>Which one a request gets is the client's to ask for and the table's to constrain, so it is
 * settled once — by {@link DeltaSharingCapabilities#chooseFormat} against the table itself — and
 * everything after it is the same work said two ways. The operations only have to name the format
 * that was settled on.
 */
@Component
public class DeltaResponses {

  private final DeltaLines parquet;
  private final DeltaLines delta;

  public DeltaResponses(UrlSigners signers, OpenSharingProperties properties) {
    SignedFiles files = new SignedFiles(signers, properties.getDelta().getUrlTtl());
    this.parquet = new ParquetLines(files);
    this.delta = new DeltaFormatLines(files);
  }

  DeltaLines in(DeltaResponseFormat format) {
    return format == DeltaResponseFormat.DELTA ? delta : parquet;
  }
}
