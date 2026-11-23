# Tables 
A table can be of different formats, both [Delta Lake](https://delta.io/) tables and [Iceberg](https://iceberg.apache.org/) tables are supported.

- [REST APIs](#rest-apis)
    - [List Tables in a Schema](#list-tables-in-a-schema)
    - [List all Tables in a Share](#list-all-tables-in-a-share)
    - [Get Table](#get-table)
- [Access Iceberg Tables](#access-iceberg-tables)
- [Access Delta Tables](#access-delta-tables)

## REST APIs

Here are the list of APIs to access tables in OpenSharing Protocol. All of the REST APIs use [bearer tokens for authorization](https://tools.ietf.org/html/rfc6750). The `{prefix}` of each API is configurable, and servers hosted by different providers may pick up different prefixes.

### List Tables in a Schema

This is the API to list tables in a schema.

HTTP Request | Value
-|-
Method | `GET`
Header | `Authorization: Bearer {token}`
URL | `{prefix}/shares/{share}/schemas/{schema}/tables`
URL Parameters | **{share}**: The share name to query. It's case-insensitive.<br>**{schema}**: The schema name to query. It's case-insensitive.
Query Parameters | **maxResults** (type: Int32, optional): The maximum number of results per page that should be returned. If the number of available results is larger than `maxResults`, the response will provide a `nextPageToken` that can be used to get the next page of results in subsequent list requests. The server may return fewer than `maxResults` items even if there are more available. The client should check `nextPageToken` in the response to determine if there are more available. Must be non-negative. 0 will return no results but `nextPageToken` may be populated.<br><br>**pageToken** (type: String, optional): Specifies a page token to use. Set `pageToken` to the `nextPageToken` returned by a previous list request to get the next page of results. `nextPageToken` will not be returned in a response if there are no more results available.

<details open>
<summary><b>200: The tables were successfully returned.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json; charset=utf-8`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "items": [
    {
      "name": "string",
      "schema": "string",
      "share": "string",
      "shareId": "string",
      "location": "string",
      "auxiliaryLocations": [
        "string"
      ],
      "accessModes": ["url","dir"],
      "id": "string",
      "format": ["delta","iceberg"]
    }
  ],
  "nextPageToken": "string"
}
```

Note: the `items` field may be an empty array or missing when no results are found. The client must handle both cases.

Note: the `id` field is optional. If `id` is populated for a table, its value should be unique within the share and stay immutable through the table's lifecycle. The format recommendation of `id` is UUID.

Note: check the format of the `name`, `schema`, and `share` fields in the sharing service. Object names must not exceed 255 characters and must not contain restricted characters. 

Note: the `shareId` field is optional. If `shareId` is populated for a table, its value should be unique across the sharing server and immutable through the table's lifecycle.

Note: `location` if present must point to the root directory of the table: where the delta log exists for a Delta table, or the Iceberg table location (the `location` in its metadata JSON) for an Iceberg table. If the server supports `dir` based access for the table, this field must be present (see `accessModes`).

Note: `auxiliaryLocations` is optional and lists extra storage locations for table files (usually no more than one). These should be supported in the `location` field of the [GenerateTemporaryTableCredential](#generate-temporary-table-credential) request body. Most tables use only the root directory, but if some files are stored elsewhere, the delta log in the root will include absolute paths to them. If a client can't read from an auxiliary location, it should fall back to URL access (if available) or fail the request.

Note: `format` is optional and lists the formats the table can be read in: `delta`, `iceberg`, or both, the same values as the comma-separated `format` of [Get Table](#get-table). It tells the client which set of APIs to read each table with, without calling Get Table for every table.

Note: `accessModes` represents the supported access modes for the table. This can be `url`, `dir`, or both. If `url` is present, the [QueryTable](#read-data-from-a-table) API should be implemented for the table. If `dir` is present, the [GenerateTemporaryTableCredential](#generate-temporary-table-credential) API should be implemented for the table. If this field is not present, the client will assume that the server only supports url based access. A table whose only format is `iceberg` has no delta log to serve files from, so its `accessModes` must be `["dir"]` and its `location` must be present; clients read its metadata through the [Iceberg REST catalog](#access-iceberg-tables).

Note: the `nextPageToken` field may be an empty string or missing when there are no additional results. The client must handle both cases.
</td>
</tr>
</table>
</details>
<details>
<summary><b>400: The request is malformed.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>401: The request is unauthenticated. The bearer token is missing or incorrect.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>403: The request is forbidden from being fulfilled.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>404: The requested resource does not exist.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>500: The request is not handled correctly due to a server error.</b></summary>
<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>

Example:

`GET {prefix}/shares/vaccine_share/schemas/acme_vaccine_data/tables?maxResults=10&pageToken=...`

```json
{
  "items" : [
    {
      "share" : "vaccine_share",
      "schema" : "acme_vaccine_data",
      "name" : "vaccine_ingredients",
      "shareId": "edacc4a7-6600-4fbb-85f3-a62a5ce6761f",
      "id": "dcb1e680-7da4-4041-9be8-88aff508d001"
    },
    {
      "share" : "vaccine_share",
      "schema" : "acme_vaccine_data",
      "name" : "vaccine_patients",
      "shareId": "edacc4a7-6600-4fbb-85f3-a62a5ce6761f",
      "id": "c48f3e19-2c29-4ea3-b6f7-3899e53338fa"
    }
  ],
  "nextPageToken" : "..."
}
```

### List all Tables in a Share

This is the API to list all the tables under all schemas in a share.

HTTP Request | Value
-|-
Method | `GET`
Header | `Authorization: Bearer {token}`
URL | `{prefix}/shares/{share}/all-tables`
URL Parameters | **{share}**: The share name to query. It's case-insensitive.
Query Parameters | **maxResults** (type: Int32, optional): The maximum number of results per page that should be returned. If the number of available results is larger than `maxResults`, the response will provide a `nextPageToken` that can be used to get the next page of results in subsequent list requests. The server may return fewer than `maxResults` items even if there are more available. The client should check `nextPageToken` in the response to determine if there are more available. Must be non-negative. 0 will return no results but `nextPageToken` may be populated.<br><br>**pageToken** (type: String, optional): Specifies a page token to use. Set `pageToken` to the `nextPageToken` returned by a previous list request to get the next page of results. `nextPageToken` will not be returned in a response if there are no more results available.

<details open>
<summary><b>200: The tables were successfully returned.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json; charset=utf-8`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "items": [
    {
      "name": "string",
      "schema": "string",
      "share": "string",
      "shareId": "string",
      "id": "string",
      "location": "string",
      "auxiliaryLocations": [
        "string"
      ],
      "accessModes": ["url","dir"],
      "format": ["delta","iceberg"]
    }
  ],
  "nextPageToken": "string"
}
```

Note: the `items` field may be an empty array or missing when no results are found. The client must handle both cases.

Note: the `id` field is optional. If `id` is populated for a table, its value should be unique within the share and stay immutable through the table's lifecycle. The format recommendation of `id` is UUID.

Note: check the format of the `name`, `schema`, and `share` fields in the sharing service. Object names must not exceed 255 characters and must not contain restricted characters. 

Note: the `shareId` field is optional. If `shareId` is populated for a table, its value should be unique across the sharing server and immutable through the table's lifecycle.

Note: `location` if present must point to the root directory of the table: where the delta log exists for a Delta table, or the Iceberg table location (the `location` in its metadata JSON) for an Iceberg table. If the server supports `dir` based access for the table, this field must be present (see `accessModes`).

Note: `auxiliaryLocations` is optional and lists extra storage locations for table files (usually no more than one). These should be supported in the `location` field of the [GenerateTemporaryTableCredential](#generate-temporary-table-credential) request body. Most tables use only the root directory, but if some files are stored elsewhere, the delta log in the root will include absolute paths to them. If a client can't read from an auxiliary location, it should fall back to URL access (if available) or fail the request. 

Note: `format` is optional and lists the formats the table can be read in: `delta`, `iceberg`, or both, the same values as the comma-separated `format` of [Get Table](#get-table). It tells the client which set of APIs to read each table with, without calling Get Table for every table.

Note: `accessModes` represents the supported access modes for the table. This can be `url`, `dir`, or both. If `url` is present, the [QueryTable](#read-data-from-a-table) API should be implemented for the table. If `dir` is present, the [GenerateTemporaryTableCredential](#generate-temporary-table-credential) API should be implemented for the table. If this field is not present, the client will assume that the server only supports url based access. A table whose only format is `iceberg` has no delta log to serve files from, so its `accessModes` must be `["dir"]` and its `location` must be present; clients read its metadata through the [Iceberg REST catalog](#access-iceberg-tables).

Note: the `nextPageToken` field may be an empty string or missing when there are no additional results. The client must handle both cases.
</td>
</tr>
</table>
</details>
<details>
<summary><b>400: The request is malformed.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>401: The request is unauthenticated. The bearer token is missing or incorrect.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>403: The request is forbidden from being fulfilled.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>404: The requested resource does not exist.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>
<details>
<summary><b>500: The request is not handled correctly due to a server error.</b></summary>
<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>

Example:

`GET {prefix}/shares/vaccine_share/all-tables`

```json
{
  "items" : [
    {
      "share" : "vaccine_share",
      "schema" : "acme_vaccine_ingredient_data",
      "name" : "vaccine_ingredients",
      "shareId": "edacc4a7-6600-4fbb-85f3-a62a5ce6761f",
      "id": "2f9729e9-6fcf-4d34-96df-bf72b26dfbe9",
      "location": "s3://deltasharing/vaccine_share/acme_vaccine_ingredient_data/vaccine_ingredients",
      "accessModes": ["url","dir"],
      "format": ["delta"]
    },
    {
      "share": "vaccine_share",
      "schema": "acme_vaccine_patient_data",
      "name": "vaccine_patients",
      "shareId": "edacc4a7-6600-4fbb-85f3-a62a5ce6761f",
      "id": "74be6365-0fc8-4a2f-8720-0de125bb5832",
      "location": "s3://deltasharing/vaccine_share/acme_vaccine_patient_data/vaccine_patients",
      "accessModes": ["dir"],
      "format": ["iceberg"]
    }
  ],
  "nextPageToken": "..."
}
```

### Get Table

This is the API to get the basic info about a table in the share.

HTTP Request | Value
-|-
Method | `GET`
Header | `Authorization: Bearer {token}`
URL | `{prefix}/shares/{share}/schemas/{schema}/tables/{table}`
URL Parameters | **{share}**: The share name to query. It's case-insensitive.<br><br>**{schema}**: The schema name to query. It's case-insensitive.<br><br>**{table}**: The table name to query. It's case-insensitive.

<details open>
<summary><b>200: The table was successfully returned.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json; charset=utf-8`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "name": "string",
  "schema": "string",
  "share": "string",
  "shareId": "string",
  "id": "string",
  "format": "string"
}
```

Note: Supported values for `format` are `delta` and `iceberg` for now, which indicates which set of APIs should be used to access the shared table. Can be a list of supported formats separated by comma `,`.

</td>
</tr>
</table>
</details>

<details>
<summary><b>400: The request is malformed.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>

<details>
<summary><b>401: The request is unauthenticated.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>

<details>
<summary><b>403: The request is forbidden from being fulfilled.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>

<details>
<summary><b>404: The requested resource does not exist.</b></summary>

<table>
<tr>
<th>HTTP Response</th>
<th>Value</th>
</tr>
<tr>
<td>Header</td>
<td>

`Content-Type: application/json`

</td>
</tr>
<tr>
<td>Body</td>
<td>

```json
{
  "errorCode": "string",
  "message": "string"
}
```

</td>
</tr>
</table>
</details>

## Access Iceberg Tables
OpenSharing provides an implementation of the Iceberg REST catalog API specification. Refer to the [Iceberg REST API spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) for details on using this REST API.

The catalog is served at the profile file's `icebergEndpoint`, and the client authenticates with the profile's `bearerToken` as on every other endpoint. A share is a warehouse: `getConfig` takes the share name as `warehouse` and returns the `prefix` under which the client addresses it, and the share's schemas are its namespaces. Failures are returned in the Iceberg REST catalog's error model, `{"error": {"message", "type", "code"}}`.

Here is a list of REST APIs supported:
- <b>getConfig</b>, GET /v1/config
- <b>listNamespaces</b>, GET /v1/{prefix}/namespaces
- <b>loadNamespaceMetadata</b>, GET /v1/{prefix}/namespaces/{namespace}
- <b>listTables</b>, GET /v1/{prefix}/namespaces/{namespace}/tables
- <b>loadTable</b>, GET /v1/{prefix}/namespaces/{namespace}/tables/{table}
- <b>loadCredentials</b>, GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials
- <b>reportMetrics</b>, POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics

`listTables` lists only the namespace's tables whose `format` includes `iceberg`, the ones `loadTable` can load. `reportMetrics` accepts scan reports and may discard them.

Example:

`GET {icebergEndpoint}/v1/config?warehouse=<share>`

```json
{
  "defaults": {},
  "overrides": {
    "prefix": "shares/<share>"
  },
  "endpoints": [
    "GET /v1/{prefix}/namespaces",
    "GET /v1/{prefix}/namespaces/{namespace}",
    "GET /v1/{prefix}/namespaces/{namespace}/tables",
    "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
    "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
    "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics"
  ]
}
```

### Load Table

`loadTable` returns the table's current `metadata-location` and the `metadata` it points to. A recipient has no access of its own to the provider's storage, so the response also carries `storage-credentials`: read-only credentials covering the table's `location`, in the property names of the Iceberg REST spec, such as `s3.access-key-id`, `adls.sas-token.<account>` or `gcs.oauth2.token`, with their expiration time.

### Load Credentials

`loadCredentials` returns only the `storage-credentials` of `loadTable`, without reading the table's metadata. A client that already has the table's metadata, or keeps its own copy of it, calls this to read the table's files, and again to refresh credentials before they expire.

`GET {icebergEndpoint}/v1/shares/<share>/namespaces/<schema>/tables/<table>/credentials`

```json
{
  "storage-credentials": [
    {
      "prefix": "s3://bucket/path/to/table/",
      "config": {
        "s3.access-key-id": "<access key id>",
        "s3.secret-access-key": "<secret access key>",
        "s3.session-token": "<session token>",
        "s3.session-token-expires-at-ms": "1700000000000"
      }
    }
  ]
}
```

## Access Delta Tables
Refer to the [Delta Sharing Protocol](https://github.com/delta-io/delta-sharing/blob/main/PROTOCOL.md) for details on accessing the shared delta tables in different ways.
