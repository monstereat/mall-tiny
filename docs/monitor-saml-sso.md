# 租户 SAML SSO

项目使用 Spring Security SAML 2.0 Service Provider 支持租户级 SSO。依赖由 Maven 的 Shibboleth Releases 仓库提供 OpenSAML 构件。

## 配置身份提供方

1. 以 Tenant Owner 登录 Admin，在“组织管理 → SAML SSO”粘贴 IdP 导出的 SAML 2.0 metadata XML，并填写邮箱属性名。
2. 保存后，把页面展示的 SP Metadata URL 提供给 IdP 配置。将 `MONITOR_ADMIN_BASE_URL` 设为用户实际访问 Admin 的 HTTPS 根地址。
3. IdP 配置完成后启用 SAML。用户在登录页填写组织 Key，平台会跳转到该组织的 IdP。

### 配置 SP 请求签名

生产环境建议配置 RSA SP 签名凭据。将 PKCS#8 RSA 私钥 DER 和 X.509 证书 DER 分别做标准 Base64 编码，通过服务器 `.env` 设置 `MONITOR_SAML_SP_PRIVATE_KEY_BASE64` 与 `MONITOR_SAML_SP_CERTIFICATE_BASE64`，随后重启 Server 并重新下载 SP metadata 更新 IdP。私钥不会写入数据库或返回给管理端；启用后平台会签名 AuthnRequest，并在 SP metadata 发布对应证书。两个变量必须同时设置，证书必须有效且与私钥匹配。

```sh
openssl pkcs8 -topk8 -nocrypt -in saml-sp-private-key.pem -outform DER \
  | base64 | tr -d '\n'
openssl x509 -in saml-sp-certificate.pem -outform DER \
  | base64 | tr -d '\n'
```

metadata XML 保存在该租户的 MySQL SAML 配置行中，不会由服务端请求外部 URL。保存时会校验 XML、IdP SSO endpoint 和签名验证证书。配置更新写入组织审计日志。

## 登录与授权

登录断言中的配置邮箱属性必须唯一匹配一个已启用的 `ums_admin.email`。该管理员还必须是已启用的组织成员；SAML 不会自动创建账号或授予组织访问权限。成功后通过 60 秒、单次兑换码换取平台 JWT；登录兑换接口返回 `Cache-Control: no-store`。

SP 发起登录地址为 `/saml2/authenticate/{tenantKey}`，Assertion Consumer Service 为 `/login/saml2/sso/{tenantKey}`，服务提供方 metadata 为 `/saml2/metadata/{tenantKey}`。部署反向代理时需将 `/saml2/` 和 `/login/saml2/` 转发到 Server，并传递 `Host`、`X-Forwarded-Host` 与 `X-Forwarded-Proto`。

## 单点退出

租户 SP metadata 会发布 POST binding 的 Single Logout Service，地址为 `/logout/saml2/slo`。RP 发起退出时，带有 SAML 登录会话的客户端向 `/logout` 发起 POST；Spring Security 会清理本地会话，并向该租户 IdP metadata 中声明的 SLO 地址发送 LogoutRequest。IdP 返回的 LogoutResponse 也由 `/logout/saml2/slo` 接收。AP 发起退出时，IdP 将 LogoutRequest POST 到同一 SLO 地址，服务端验证请求后清理会话并返回 LogoutResponse。

平台 JWT 不能由浏览器会话失效自动作废。SAML 退出处理器会按租户和 SAML subject 写入 Redis 撤销时间；JWT 过滤器拒绝该时间点之前签发的 SAML JWT，撤销标记保留至 token 生命周期结束。

配置可用的 SP RSA 私钥和证书后，Spring Security 会用它签名 AuthnRequest、LogoutRequest 和 LogoutResponse，SP metadata 同时发布签名证书。未配置这对凭据时，登录和 SLO endpoint 仍存在，但 SAML 消息不带 SP 签名；生产 IdP 通常需要据其策略配置 SP 签名证书。IdP metadata 需要声明 SingleLogoutService 才能完成 RP 发起的远端退出。真实 IdP 的端点、签名和 NameID 兼容仍需在目标 IdP 上验收。

当前实现支持基于已验证 IdP metadata 的 Web Browser SSO、平台 JWT 兑换和 Spring Security SAML Single Logout。账号自动创建、供应商特定属性映射和真实 IdP 互通验收仍待补充。
