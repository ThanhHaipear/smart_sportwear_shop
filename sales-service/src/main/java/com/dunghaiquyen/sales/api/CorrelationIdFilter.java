package com.dunghaiquyen.sales.api;

import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.UUID;
import org.slf4j.MDC;import org.springframework.stereotype.Component;
@Component public class CorrelationIdFilter extends OncePerRequestFilterBase{
 static final String HEADER="X-Correlation-Id";
 @Override protected void filter(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  String id=req.getHeader(HEADER);if(id==null||id.isBlank())id=UUID.randomUUID().toString();res.setHeader(HEADER,id);MDC.put("correlationId",id);try{chain.doFilter(req,res);}finally{MDC.remove("correlationId");}
 }
}
