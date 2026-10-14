package com.supportdesk;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT username,password_hash FROM users WHERE username=?", (rs,n) -> User.withUsername(rs.getString("username")).password(rs.getString("password_hash")).roles("USER").build(),username)
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/auth/session","/api/v1/auth/login","/","/index.html","/assets/**","/favicon.ico","/error").permitAll().anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,ex) -> error(res,401,"unauthorized","请先登录"))
                .accessDeniedHandler((req,res,ex) -> error(res,403,"forbidden","权限不足或会话验证已失效，请刷新重试")))
            .formLogin(login -> login.loginProcessingUrl("/api/v1/auth/login").successHandler((req,res,auth) -> json(res,"{\"data\":{\"authenticated\":true}}"))
                .failureHandler((req,res,ex) -> error(res,401,"invalid_credentials","账号或密码不正确")))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("JSESSIONID").logoutSuccessHandler((req,res,auth) -> json(res,"{\"data\":{\"authenticated\":false}}")))
            .requestCache(cache -> cache.disable());
        return http.build();
    }
    static void json(HttpServletResponse response,String body) throws IOException { response.setContentType("application/json"); response.setCharacterEncoding("UTF-8"); response.getWriter().write(body); }
    static void error(HttpServletResponse response,int status,String code,String message) throws IOException { response.setStatus(status); json(response,"{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\",\"details\":[]}}"); }
}

@RestController
class SessionController {
    private final SupportService service;
    SessionController(SupportService service) { this.service=service; }
    @GetMapping("/api/v1/auth/session") Map<String,Object> session(Principal principal,CsrfToken token) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("data",principal==null?null:service.actor(principal.getName()));
        result.put("csrf",Map.of("headerName",token.getHeaderName(),"token",token.getToken())); return result;
    }
}
