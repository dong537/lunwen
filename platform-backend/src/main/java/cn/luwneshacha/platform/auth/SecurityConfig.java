package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.account.UserRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
  @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
  @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
  @Bean AuthenticationManager authenticationManager(AuthService auth) {
    return request -> SessionAuthentication.forUser(auth.login(request.getName(), (String) request.getCredentials()));
  }
  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users, SecurityResponses responses,
      SecurityContextRepository contexts, CsrfTokenRepository csrf) throws Exception {
    return http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
        .securityContext(context -> context.securityContextRepository(contexts))
        .csrf(config -> config.csrfTokenRepository(csrf))
        .addFilterAfter(new SessionGuardFilter(users, responses), SecurityContextHolderFilter.class)
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, error) -> responses.write(response, 401, "UNAUTHENTICATED", "请先登录"))
            .accessDeniedHandler((request, response, error) -> responses.write(response, 403, "FORBIDDEN", "没有权限执行此操作，或操作凭证已过期")))
        .authorizeHttpRequests(auth -> auth
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers("/api/auth/**").authenticated()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/teacher/**").hasRole("TEACHER")
            .requestMatchers("/api/submissions").hasRole("STUDENT")
            .requestMatchers("/api/submissions/**").hasAnyRole("STUDENT", "TEACHER")
            .anyRequest().denyAll()).build();
  }
}
