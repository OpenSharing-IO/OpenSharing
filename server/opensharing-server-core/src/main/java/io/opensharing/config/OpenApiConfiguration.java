package io.opensharing.config;

import io.opensharing.principal.Caller;
import io.opensharing.recipient.RecipientPrincipal;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes both APIs as OpenAPI, read from the controllers and the records they return.
 *
 * <p>The document is derived rather than written, so it cannot promise something the server does
 * not serve. What it is for is the other side of the wire: a recipient's engineer can point a
 * generator at it and get a typed client instead of hand-writing calls from {@code spec/protocols}.
 * The spec stays the normative definition of the protocol — this describes one rendering of it.
 *
 * <p>The two APIs are separate documents because they answer to different contracts and even spell
 * their fields differently: the protocol is camelCase and fixed by the spec, while the admin API is
 * snake_case and this server's own. Reading them as one would suggest a uniformity that is not
 * there.
 *
 * <p>Neither document is authenticated, since the filters cover only the two API prefixes. What a
 * server accepts is not a secret — every route in them is in the published spec — but a deployment
 * that disagrees can turn them off with {@code springdoc.api-docs.enabled=false}.
 *
 * <p>One thing OpenAPI describes poorly: the table read operations answer with newline-delimited
 * JSON, a stream of actions rather than one object, so their bodies appear as text. The README says
 * what a line holds.
 */
@Configuration
public class OpenApiConfiguration {

  private static final String RECIPIENT_TOKEN = "recipientToken";
  private static final String PRINCIPAL_TOKEN = "principalToken";

  /*
   * Both APIs take the authenticated caller as a method argument, filled in by an argument resolver
   * from the bearer token. Nothing about it is part of the request, so it is hidden rather than
   * described: left alone, it would be documented as a required query parameter, and a generated
   * client would dutifully send one.
   */
  static {
    SpringDocUtils.getConfig()
        .addRequestWrapperToIgnore(RecipientPrincipal.class)
        .addRequestWrapperToIgnore(Caller.class);
  }

  @Bean
  public OpenAPI openSharingOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("OpenSharing Reference Server")
                .version("0.1.0")
                .description(
                    "Reference implementation of the OpenSharing protocol. The recipient-facing "
                        + "protocol follows spec/protocols/; the provider-admin API is this "
                        + "server's own.")
                .license(
                    new License()
                        .name("Apache License, Version 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")))
        .components(
            new Components()
                .addSecuritySchemes(RECIPIENT_TOKEN, bearer("A recipient's token."))
                .addSecuritySchemes(
                    PRINCIPAL_TOKEN,
                    bearer("A provider principal's token from the configured allowlist.")));
  }

  /**
   * The protocol a recipient calls, mounted wherever {@code protocol-prefix} points. Excludes the
   * provider and activation APIs explicitly: by default they are mounted underneath the protocol
   * prefix too ({@code /api/2.1/opensharing/provider}, {@code /api/2.1/opensharing/activation}
   * under {@code /api/2.1/opensharing}), which {@code pathsToMatch} alone cannot tell apart from the
   * protocol's own routes.
   */
  @Bean
  public GroupedOpenApi protocolApi(OpenSharingProperties properties) {
    return GroupedOpenApi.builder()
        .group("protocol")
        .displayName("Recipient protocol")
        .pathsToMatch(properties.getProtocolPrefix() + "/**")
        .pathsToExclude(
            properties.getProvider().getBasePath() + "/**",
            properties.getActivation().getBasePath() + "/**")
        .addOpenApiCustomizer(api -> api.addSecurityItem(requirement(RECIPIENT_TOKEN)))
        .build();
  }

  /** The provider-admin API, which is where shares, recipients and grants are managed. */
  @Bean
  public GroupedOpenApi adminApi(OpenSharingProperties properties) {
    return GroupedOpenApi.builder()
        .group("admin")
        .displayName("Provider admin")
        .pathsToMatch(properties.getProvider().getBasePath() + "/**")
        .addOpenApiCustomizer(api -> api.addSecurityItem(requirement(PRINCIPAL_TOKEN)))
        .build();
  }

  private static SecurityScheme bearer(String description) {
    return new SecurityScheme()
        .type(SecurityScheme.Type.HTTP)
        .scheme("bearer")
        .description(description);
  }

  private static SecurityRequirement requirement(String scheme) {
    return new SecurityRequirement().addList(scheme);
  }
}
