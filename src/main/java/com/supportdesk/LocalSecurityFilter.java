package com.supportdesk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** Local-only Host and Origin boundary in addition to Spring session CSRF. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalSecurityFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'");
        if(!Set.of("127.0.0.1","localhost").contains(request.getServerName())){SecurityConfig.error(response,403,"invalid_host","仅允许本机访问");return;}
        if(Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())&&request.getHeader("Origin")!=null&&!sameOrigin(request)){SecurityConfig.error(response,403,"origin_rejected","不允许跨来源写入");return;}
        if(Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())&&request.getHeader("Transfer-Encoding")!=null){SecurityConfig.error(response,413,"request_too_large","不接受分块写入，请使用已知长度的请求");return;}
        if(request.getContentLengthLong()>24576){SecurityConfig.error(response,413,"request_too_large","请求内容超过大小限制");return;}
        chain.doFilter(request,response);
    }
    private boolean sameOrigin(HttpServletRequest request) {
        try { URI origin=URI.create(request.getHeader("Origin"));int port=origin.getPort()==-1?("https".equals(origin.getScheme())?443:80):origin.getPort();return request.getScheme().equals(origin.getScheme())&&request.getServerName().equals(origin.getHost())&&request.getServerPort()==port&&origin.getUserInfo()==null&&origin.getRawQuery()==null&&origin.getRawFragment()==null&&(origin.getPath()==null||origin.getPath().isEmpty()); }
        catch(IllegalArgumentException e){return false;}
    }
}
