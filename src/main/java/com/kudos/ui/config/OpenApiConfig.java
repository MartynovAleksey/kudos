/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kudos.ui.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the Swagger UI served at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI apiDefinition() {
    return new OpenAPI()
        .info(
            new Info()
                .title("kudos API")
                .version("1.0")
                .description(
                    "Kerberized data-path API for HDFS, Ozone, HBase, Kyuubi SQL and Spark "
                        + "History. Every call runs as the signed-in user's own Kerberos "
                        + "identity. Authenticate with HTTP Basic (LDAP credentials) or the "
                        + "browser session from the login form."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "basic",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
        .addSecurityItem(new SecurityRequirement().addList("basic"));
  }
}
