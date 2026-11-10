package com.dunghaiquyen.sales.api;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import org.springframework.web.filter.OncePerRequestFilter;
abstract class OncePerRequestFilterBase extends OncePerRequestFilter{
 protected final void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws ServletException,IOException{filter(r,s,c);}protected abstract void filter(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws IOException,ServletException;
}
