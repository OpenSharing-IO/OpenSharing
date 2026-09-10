package io.opensharing.serving;

import io.opensharing.protocol.TableAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A table read operation's answer: the actions to put on the wire, one per line, and the headers that
 * belong with them.
 *
 * <p>Headers travel with the body because what they say is the format's business — which version was
 * read, which capabilities were honoured — while writing them out is the endpoint's.
 */
public record ActionStream(List<TableAction> actions, Map<String, String> headers) {

  public ActionStream {
    actions = actions == null ? List.of() : List.copyOf(actions);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  public static Builder of(List<TableAction> actions) {
    return new Builder(actions);
  }

  public static final class Builder {
    private final List<TableAction> actions;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Builder(List<TableAction> actions) {
      this.actions = actions;
    }

    /** Ignores a null value, so an optional header is one call rather than a branch. */
    public Builder header(String name, String value) {
      if (value != null) {
        headers.put(name, value);
      }
      return this;
    }

    public ActionStream build() {
      return new ActionStream(actions, headers);
    }
  }
}
