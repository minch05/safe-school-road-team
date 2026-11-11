package com.safeschoolroad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

// 로그인 설정: 세션 로그인 + 자동 로그인(remember-me). JWT는 쓰지 않는다.
@Configuration
public class SecurityConfig {

    // 자동 로그인 쿠키 유지 기간. "한 번 로그인하면 계속 유지"(HANDOFF 3장)
    private static final int REMEMBER_ME_SECONDS = (int) Duration.ofDays(365).toSeconds();

    // 자동 로그인 토큰은 DB(persistent_logins)에 저장하므로 이 키는 켤 때마다 새로 만들어도 된다.
    private static final String REMEMBER_ME_KEY = UUID.randomUUID().toString();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   PersistentTokenBasedRememberMeServices rememberMeServices,
                                                   SecurityContextRepository securityContextRepository) throws Exception {
        // Swagger UI는 쿠키 값을 헤더에 붙인다. 기존 Thymeleaf 폼도 같은 CSRF 토큰을 쓴다.
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new BrowserCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        // 로그인 없이 볼 수 있는 곳: 메인, 로그인·가입, 사용 방법, 비로그인 경로 살펴보기(FR-17), 정적 파일·PWA
                        .requestMatchers("/", "/login", "/signup", "/signup/**", "/guide",
                                "/explore", "/map", "/data-info", "/health/ready", "/api/session", "/api/facilities", "/api/facilities/catalog", "/api/explore/**", "/api/risk-map/**", "/api/places/search",
                                "/css/**", "/js/**", "/icons/**", "/manifest.webmanifest", "/sw.js",
                                "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .securityContext(ctx -> ctx.securityContextRepository(securityContextRepository))
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                                request -> !request.getRequestURI().startsWith("/api/"))
                        .defaultAuthenticationEntryPointFor((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":\"AUTH\",\"error\":\"로그인 시간이 끝났어요. 로그인한 뒤 다시 시도해 주세요.\"}");
                        }, request -> request.getRequestURI().startsWith("/api/"))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (!request.getRequestURI().startsWith("/api/")) {
                                new org.springframework.security.web.access.AccessDeniedHandlerImpl().handle(request, response, exception);
                                return;
                            }
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            boolean csrf = exception instanceof org.springframework.security.web.csrf.CsrfException;
                            response.getWriter().write(csrf
                                    ? "{\"code\":\"CSRF\",\"error\":\"보안 확인 정보가 바뀌었어요. 다시 시도해 주세요.\"}"
                                    : "{\"code\":\"FORBIDDEN\",\"error\":\"이 작업을 할 권한이 없어요.\"}");
                        }))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .rememberMe(remember -> remember
                        .rememberMeServices(rememberMeServices)
                        .key(REMEMBER_ME_KEY))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .deleteCookies("JSESSIONID"));
        return http.build();
    }

    // 비밀번호 암호화. 저장 값 앞에 {bcrypt}가 붙는다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // 자동 로그인 토큰 저장소. 테이블은 resources/schema.sql 에서 만든다.
    @Bean
    public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    // 가입 직후 바로 로그인시킬 때도 같은 것을 쓰려고 따로 만든다.
    @Bean
    public PersistentTokenBasedRememberMeServices rememberMeServices(UserDetailsService userDetailsService,
                                                                     PersistentTokenRepository tokenRepository) {
        PersistentTokenBasedRememberMeServices services =
                new PersistentTokenBasedRememberMeServices(REMEMBER_ME_KEY, userDetailsService, tokenRepository);
        services.setAlwaysRemember(true); // 체크 칸 없이 항상 자동 로그인
        services.setTokenValiditySeconds(REMEMBER_ME_SECONDS);
        return services;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
