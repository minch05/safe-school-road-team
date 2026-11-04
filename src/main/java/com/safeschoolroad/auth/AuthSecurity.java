package com.safeschoolroad.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Configuration
public class AuthSecurity {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return login -> jdbc.query("SELECT login_id, password_hash FROM member WHERE login_id = ?",
                (rs, row) -> User.withUsername(rs.getString(1)).password(rs.getString(2)).roles("USER").build(), login)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다"));
    }

    @Bean JdbcTokenRepositoryImpl persistentTokenRepository(JdbcTemplate jdbc) {
        var repo = new JdbcTokenRepositoryImpl();
        repo.setDataSource(jdbc.getDataSource());
        return repo;
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTokenRepositoryImpl tokens,
            UserDetailsService users, @Value("${app.remember-me-key}") String rememberKey) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/login.html", "/signup.html", "/guide.html", "/css/**", "/js/**", "/health/**",
                        "/api/auth/signup", "/api/auth/csrf", "/api/auth/me", "/api/map-config", "/api/places/search",
                        "/api/facilities/**").permitAll()
                .anyRequest().authenticated());
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) ->
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "로그인이 필요합니다")));
        http.formLogin(login -> login.loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"authenticated\":true}");
                })
                .failureHandler((request, response, exception) -> response.sendError(401, "로그인에 실패했습니다")));
        http.rememberMe(remember -> remember.key(rememberKey).tokenRepository(tokens)
                .userDetailsService(users).rememberMeParameter("remember-me").tokenValiditySeconds(7 * 24 * 60 * 60));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
                .deleteCookies("JSESSIONID", "remember-me")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }
}
