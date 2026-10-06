package io.opensharing;

import io.opensharing.config.OpenSharingProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OpenSharingProperties.class)
public class OpenSharingApplication {}
