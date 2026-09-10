<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
# OpenSharing Protocol

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

This document describes the OpenSharing Protocol's general concepts, including the profile file format and naming requirements.

## Profile File Format

A profile file is a JSON file that contains the information for a recipient to access shared data on a OpenSharing server. There are a few fields in this file as listed below.

Field Name | Descrption
-|-
shareCredentialsVersion | The file format version of the profile file. This version will be increased whenever non-forward-compatible changes are made to the profile format. When a client is running an unsupported profile file format version, it should show an error message instructing the user to upgrade to a newer version of their client.
endpoint | The url of the sharing server.
icebergEndpoint | The url of the sharing server that supports the iceberg table endpoints.
bearerToken | The [bearer token](https://tools.ietf.org/html/rfc6750) to access the server.
expirationTime | The expiration time of the bearer token in [ISO 8601 format](https://www.w3.org/TR/NOTE-datetime). This field is optional and if it is not provided, the bearer token can be seen as never expire.

Example:

```json
{
  "shareCredentialsVersion": 1,
  "endpoint": "https://sharing.opensharing.io/opensharing/",
  "icebergEndpoint": "https://sharing.opensharing.io/opensharing/iceberg/",
  "bearerToken": "<token>",
  "expirationTime": "2021-11-12T00:12:29.0Z"
}
```

## Names

Share, Schema, and shared objects are identifiable by names. To ensure compatibility and avoid issues across different sharing servers, the following limitations apply for object names:

- Object names cannot exceed 255 characters.
- The following special characters are not allowed for all object names:
  - Space (` `)
  - Forward slash (`/`)
  - All ASCII control characters (`00-1F` hex)
  - The DELETE character (`7f` hex)
- Schema and shared object names additionally do not allow special character Period (`.`)
- Object names are case-insensitive
