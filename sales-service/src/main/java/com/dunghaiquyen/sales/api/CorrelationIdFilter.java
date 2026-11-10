package com.dunghaiquyen.sales.api;

import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.UUID;
import org.slf4j.MDC;import org.springframework.stereotype.Component;
@Component public class CorrelationIdFilter extends OncePerRequestFilterBase{
 static final String HEADER="X-Correlation-Id";
 @Override protected void filter(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  String id=req.getHeader(HEADER);if(id==null||id.isBlank()||id.length()>100)id=UUID.randomUUID().toString();String trace=req.getHeader("traceparent");if(trace==null||!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"))trace="";res.setHeader(HEADER,id);if(!trace.isEmpty())res.setHeader("traceparent",trace);MDC.put("correlationId",id);MDC.put("traceparent",trace);try{chain.doFilter(req,res);}finally{MDC.remove("correlationId");MDC.remove("traceparent");}
 }
}
