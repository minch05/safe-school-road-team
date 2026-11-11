package com.safeschoolroad.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI apiDescription() {
        return new OpenAPI().info(new Info().title("안전한 통학길 API").version("1.0")
                .description("세션 로그인과 자동 로그인을 사용합니다. 먼저 /login에서 로그인한 뒤 같은 브라우저에서 사용하세요. "
                        + "POST·DELETE·PATCH에는 CSRF 토큰이 필요하며 Swagger UI가 자동으로 붙입니다. "
                        + "사용자의 현재 위치를 받는 API는 없습니다. 장소 좌표는 사용자가 선택한 출발지·도착지입니다."))
                .components(new Components().addSecuritySchemes("session", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("JSESSIONID")));
    }

    @Bean
    GroupedOpenApi serviceApi() {
        return GroupedOpenApi.builder().group("service").pathsToMatch("/api/**")
                .addOperationCustomizer((operation, handler) -> {
                    if (!handler.getBeanType().getPackageName().endsWith(".explore")
                            && !handler.getBeanType().getSimpleName().equals("RiskMapController")
                            && !handler.getBeanType().getSimpleName().equals("FacilityApiController")
                            && !handler.getMethod().getName().equals("searchPlaces")) {
                        operation.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("session"));
                    }
                    return operation;
                }).build();
    }
}
