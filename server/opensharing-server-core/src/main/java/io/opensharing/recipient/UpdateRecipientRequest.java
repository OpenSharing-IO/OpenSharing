package io.opensharing.recipient;

/** PATCH body for recipient metadata. Null fields are left unchanged. */
public record UpdateRecipientRequest(String comment) {}
