package com.dunghaiquyen.identity.api;

import com.dunghaiquyen.identity.domain.IdentityService.ApiFailure;
import com.dunghaiquyen.identity.persistence.IdentityRepository.NotFound;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

public final class ApiInfrastructure { private ApiInfrastructure() {} }

@Component
class CorrelationFilter extends OncePerRequestFilter {
    static final String HEADER="X-Correlation-Id";
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String id=request.getHeader(HEADER); if(id==null||id.isBlank()||id.length()>100) id=UUID.randomUUID().toString();
        MDC.put("correlationId",id); response.setHeader(HEADER,id); try{chain.doFilter(request,response);}finally{MDC.remove("correlationId");}
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiFailure.class) ResponseEntity<?> failure(ApiFailure e,HttpServletRequest r){return error(e.status(),e.getMessage(),r);}
    @ExceptionHandler(NotFound.class) ResponseEntity<?> missing(NotFound e,HttpServletRequest r){return error(HttpStatus.NOT_FOUND,e.getMessage(),r);}
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(MethodArgumentNotValidException e,HttpServletRequest r){return error(HttpStatus.BAD_REQUEST,"Invalid request",r);}
    private ResponseEntity<?> error(HttpStatus status,String message,HttpServletRequest request){return ResponseEntity.status(status).body(Map.of("timestamp",Instant.now().toString(),"status",status.value(),"message",message,"path",request.getRequestURI(),"correlationId",MDC.get("correlationId")));}
}
