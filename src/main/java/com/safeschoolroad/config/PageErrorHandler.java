package com.safeschoolroad.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.NoSuchElementException;

// 화면(Thymeleaf) 오류를 쉬운 안내 화면(error.html)으로 바꾼다. 없는 대상은 404, 잘못된 요청은 400.
// 처리하지 않으면 500 "문제가 생겼어요"로 보여서, 없는 아이·경로 주소를 열었을 때 원인을 알 수 없었다 (10-10 화면 검수)
// API 오류는 ApiErrorHandler 가 먼저 처리한다.
@Order(Ordered.LOWEST_PRECEDENCE)
@ControllerAdvice(annotations = Controller.class)
public class PageErrorHandler {

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Model model) {
        model.addAttribute("status", 404);
        return "error";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(Model model) {
        model.addAttribute("status", 400);
        return "error";
    }
}
