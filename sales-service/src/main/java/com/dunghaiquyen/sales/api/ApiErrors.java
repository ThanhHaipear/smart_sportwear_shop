package com.dunghaiquyen.sales.api;
import java.util.Map;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class ApiErrors{
 @ExceptionHandler(SecurityException.class) ResponseEntity<Map<String,Object>> security(SecurityException e){return ResponseEntity.status(401).body(Map.of("error","UNAUTHORIZED","message",e.getMessage()));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,Object>> bad(IllegalArgumentException e){return ResponseEntity.unprocessableEntity().body(Map.of("error","BUSINESS_RULE","message",e.getMessage()));}
}
