package com.dqs.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8081}")
    private String port;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DQS Backend API")
                        .description("API del sistema de cotizaciones DQS — gestión de cotizaciones, ítems, membresías, pagos, entregas y validación fiscal.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("PriceSmart")
                                .email("juanjimenez@pricesmart.com")))
                .servers(List.of(
                        new Server().url("http://localhost:" + port).description("Local")));
    }
}
