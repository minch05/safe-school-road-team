package com.safeschoolroad.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.*;
import org.springframework.util.StringUtils;
import java.util.function.Supplier;

// HTML 폼의 마스킹된 토큰은 유지하고, JS·Swagger의 쿠키 토큰 헤더도 받는다.
final class BrowserCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
        xor.handle(request, response, token);
        token.get(); // 읽기 가능한 XSRF-TOKEN 쿠키를 응답에 만든다.
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        return StringUtils.hasText(request.getHeader(token.getHeaderName()))
                ? plain.resolveCsrfTokenValue(request, token) : xor.resolveCsrfTokenValue(request, token);
    }
}
