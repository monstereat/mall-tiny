package com.macro.mall.tiny.security.config;

import com.macro.mall.tiny.security.component.*;
import com.macro.mall.tiny.modules.monitor.service.MonitorSamlAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.FilterSecurityInterceptor;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.saml2.provider.service.metadata.OpenSamlMetadataResolver;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.springframework.security.saml2.provider.service.metadata.RequestMatcherMetadataResponseResolver;
import com.macro.mall.tiny.modules.monitor.service.MonitorSamlRegistrationRepository;
import com.macro.mall.tiny.modules.monitor.service.MonitorSamlTokenRevocationService;
import com.macro.mall.tiny.security.component.MonitorSamlSecurityContextRepository;


/**
 * SpringSecurity 6.x以上新用法配置
 * 为避免循环依赖，仅用于配置HttpSecurity
 * Created by macro on 2019/11/5.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private IgnoreUrlsConfig ignoreUrlsConfig;
    @Autowired
    private RestfulAccessDeniedHandler restfulAccessDeniedHandler;
    @Autowired
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    @Autowired
    private JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;
    @Autowired
    private DynamicAuthorizationManager dynamicAuthorizationManager;
    @Autowired
    private MonitorSamlAuthenticationSuccessHandler samlAuthenticationSuccessHandler;
    @Autowired
    private MonitorSamlRegistrationRepository samlRegistrationRepository;
    @Autowired
    private MonitorSamlSecurityContextRepository samlSecurityContextRepository;
    @Autowired
    private MonitorSamlTokenRevocationService samlTokenRevocationService;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry = httpSecurity
                .authorizeHttpRequests();
        //不需要保护的资源路径允许访问
        for (String url : ignoreUrlsConfig.getUrls()) {
            registry.requestMatchers(url).permitAll();
        }
        registry.requestMatchers(
                "/saml2/metadata/**",
                "/saml2/authenticate/**",
                "/login/saml2/sso/**",
                "/logout/saml2/slo"
        ).permitAll();
        //允许跨域请求的OPTIONS请求
        registry.requestMatchers(HttpMethod.OPTIONS)
                .permitAll();
        // 任何请求需要身份认证
        registry.and()
                .authorizeHttpRequests()
                .anyRequest()
                .access(dynamicAuthorizationManager)
                // 关闭 CSRF，并仅为 SAML 请求关联创建会话
                .and()
                .csrf()
                .disable()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .and()
                .securityContext()
                .securityContextRepository(samlSecurityContextRepository)
                // 自定义权限拒绝处理类
                .and()
                .exceptionHandling()
                .accessDeniedHandler(restfulAccessDeniedHandler)
                .authenticationEntryPoint(restAuthenticationEntryPoint)
                // 自定义权限拦截器JWT过滤器
                .and()
                .addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class);
        httpSecurity.saml2Login(saml -> saml
                .relyingPartyRegistrationRepository(samlRegistrationRepository)
                .successHandler(samlAuthenticationSuccessHandler)
                .failureHandler(samlAuthenticationSuccessHandler));
        httpSecurity.logout(logout -> logout
                .addLogoutHandler(samlTokenRevocationService));
        httpSecurity.saml2Logout(saml -> saml
                .relyingPartyRegistrationRepository(samlRegistrationRepository)
                .logoutRequest(request -> request.logoutUrl("/logout/saml2/slo"))
                .logoutResponse(response -> response.logoutUrl("/logout/saml2/slo")));
        OpenSamlMetadataResolver metadataResolver = new OpenSamlMetadataResolver();
        metadataResolver.setEntityDescriptorCustomizer(parameters -> parameters.getEntityDescriptor()
                .getRoleDescriptors(SPSSODescriptor.DEFAULT_ELEMENT_NAME).stream()
                .filter(SPSSODescriptor.class::isInstance)
                .map(SPSSODescriptor.class::cast)
                .forEach(descriptor -> descriptor.setAuthnRequestsSigned(
                        parameters.getRelyingPartyRegistration().isAuthnRequestsSigned())));
        RequestMatcherMetadataResponseResolver metadataResponseResolver =
                new RequestMatcherMetadataResponseResolver(samlRegistrationRepository, metadataResolver);
        httpSecurity.saml2Metadata(metadata -> metadata.metadataResponseResolver(metadataResponseResolver));
        return httpSecurity.build();
    }
}
