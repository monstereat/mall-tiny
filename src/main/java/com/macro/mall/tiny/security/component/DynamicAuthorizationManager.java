package com.macro.mall.tiny.security.component;

import cn.hutool.core.collection.CollUtil;
import com.macro.mall.tiny.security.config.IgnoreUrlsConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DynamicAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    @Autowired
    private DynamicSecurityService dynamicSecurityService;
    @Autowired
    private IgnoreUrlsConfig ignoreUrlsConfig;

    @Override
    public void verify(Supplier<Authentication> authentication, RequestAuthorizationContext object) {
        AuthorizationManager.super.verify(authentication, object);
    }

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authentication,
            RequestAuthorizationContext requestAuthorizationContext) {
        HttpServletRequest request = requestAuthorizationContext.getRequest();
        String path = request.getRequestURI();
        PathMatcher pathMatcher = new AntPathMatcher();

        for (String ignoreUrl : ignoreUrlsConfig.getUrls()) {
            if (pathMatcher.match(ignoreUrl, path)) {
                return new AuthorizationDecision(true);
            }
        }
        if (request.getMethod().equals(HttpMethod.OPTIONS.name())) {
            return new AuthorizationDecision(true);
        }

        Authentication currentAuth = authentication.get();
        if (!currentAuth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        Map<String, String> dataSource = dynamicSecurityService.getDataSource();
        List<String> needAuthorities = dataSource.entrySet().stream()
                .filter(entry -> pathMatcher.match(entry.getKey(), path))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        // 没有显式登记为 RBAC 资源的接口，至少要求已登录。
        // 监控项目 API 会在业务层继续执行 projectKey 范围校验。
        if (CollUtil.isEmpty(needAuthorities)) {
            return new AuthorizationDecision(true);
        }

        Collection<? extends GrantedAuthority> grantedAuthorities = currentAuth.getAuthorities();
        List<String> hasAuth = grantedAuthorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(needAuthorities::contains)
                .collect(Collectors.toList());
        return new AuthorizationDecision(CollUtil.isNotEmpty(hasAuth));
    }
}
