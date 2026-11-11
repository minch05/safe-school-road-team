package com.safeschoolroad.config;

import com.safeschoolroad.external.TmapClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

// API(/api/...) 오류를 화면이 보여 줄 수 있는 {"error": "문장"} 으로 바꾼다
// 화면용 PageErrorHandler 보다 먼저 적용되게 순서를 가장 앞에 둔다 (@RestController 도 @Controller 이기 때문)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(com.safeschoolroad.route.DraftUnavailableException.class)
    public ResponseEntity<Map<String, String>> draftUnavailable(com.safeschoolroad.route.DraftUnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(), "code", "DRAFT_UNAVAILABLE"));
    }

    @ExceptionHandler(TmapClient.TmapException.class)
    public ResponseEntity<Map<String, String>> tmap(TmapClient.TmapException e) {
        log.warn("TMAP: {}", e.getMessage(), e.getCause());
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().hasFieldErrors("text")
                ? "안내문은 1~200자로 써 주세요." : "입력한 값을 확인해 주세요.";
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() {
        return body(HttpStatus.BAD_REQUEST, "입력 형식을 확인해 주세요. 장소를 다시 고른 뒤 시도해 주세요.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "볼 수 없는 경로예요.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "찾을 수 없어요.");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "문제가 생겼어요." : message));
    }
}
