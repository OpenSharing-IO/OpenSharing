package io.opensharing.recipient;

import io.opensharing.http.AdminJson;
import java.util.List;
import java.util.Map;

/** Only non-null fields are applied. A given list replaces the stored one wholesale. */
@AdminJson
public record UpdateRecipientRequest(List<String> ipAccessList, Map<String, String> properties) {}
