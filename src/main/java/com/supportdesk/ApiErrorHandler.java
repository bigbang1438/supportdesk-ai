package com.supportdesk;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.*;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> application(ApiException e) { return ResponseEntity.status(e.status()).body(error(e.code(),e.getMessage(),List.of())); }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var fields=e.getBindingResult().getFieldErrors().stream().map(f->Map.of("field",f.getField(),"message",f.getDefaultMessage()==null?"输入不合法":f.getDefaultMessage())).toList();return ResponseEntity.badRequest().body(error("validation_error","请检查输入内容",fields));
    }
    @ExceptionHandler({ConstraintViolationException.class,MethodArgumentTypeMismatchException.class,HandlerMethodValidationException.class}) ResponseEntity<?> parameters(Exception e) { return ResponseEntity.badRequest().body(error("validation_error","查询参数或资源编号不合法",List.of())); }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> json(Exception e) { return ResponseEntity.badRequest().body(error("invalid_json","请求格式或字段不合法",List.of())); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e) { return ResponseEntity.status(405).body(error("method_not_allowed","该资源不支持此操作",List.of())); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class) ResponseEntity<?> media(Exception e) { return ResponseEntity.status(415).body(error("unsupported_media_type","请使用 JSON 格式提交请求",List.of())); }
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(error("not_found","资源不存在",List.of())); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) { org.slf4j.LoggerFactory.getLogger(ApiErrorHandler.class).error("Unhandled API error type: {}",e.getClass().getSimpleName());return ResponseEntity.internalServerError().body(error("internal_error","服务暂时无法完成操作，请稍后再试",List.of())); }
    private static Map<String,Object> error(String code,String message,Object details) { return Map.of("error",Map.of("code",code,"message",message,"details",details)); }
}
