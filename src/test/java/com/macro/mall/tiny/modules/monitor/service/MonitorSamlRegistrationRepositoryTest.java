package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorTenantSamlConfigMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenantSamlConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.metadata.OpenSamlMetadataResolver;
import org.springframework.security.saml2.provider.service.metadata.RequestMatcherMetadataResponseResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.authentication.logout.OpenSaml4LogoutRequestResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitorSamlRegistrationRepositoryTest {
    private final MonitorTenantSamlConfigMapper mapper = mock(MonitorTenantSamlConfigMapper.class);
    private final MonitorSamlRegistrationRepository repository = new MonitorSamlRegistrationRepository(
            mapper, new MonitorSamlSigningCredential("", ""));

    @Test
    void buildsTenantRegistrationFromStoredMetadata() {
        MonitorTenantSamlConfig config = new MonitorTenantSamlConfig();
        config.setTenantKey("acme");
        config.setEnabled(1);
        config.setUpdateTime(new Date(1_000));
        config.setMetadataXml(metadata("https://idp.example.com"));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(config);

        RelyingPartyRegistration registration = repository.findByRegistrationId("acme");

        assertNotNull(registration);
        assertEquals("acme", registration.getRegistrationId());
        assertEquals("https://idp.example.com", registration.getAssertingPartyMetadata().getEntityId());
        assertEquals("https://idp.example.com/slo", registration.getAssertingPartyMetadata().getSingleLogoutServiceLocation());
        assertEquals("{baseUrl}/logout/saml2/slo", registration.getSingleLogoutServiceLocation());
        assertEquals("{baseUrl}/logout/saml2/slo", registration.getSingleLogoutServiceResponseLocation());
        assertEquals(org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding.POST,
                registration.getSingleLogoutServiceBinding());
        String metadata = new org.springframework.security.saml2.provider.service.metadata.OpenSamlMetadataResolver()
                .resolve(registration);
        assertTrue(metadata.contains("SingleLogoutService"));
        assertTrue(metadata.contains("{baseUrl}/logout/saml2/slo"));
        assertNull(repository.findByRegistrationId("invalid/key"));
    }

    @Test
    void disabledTenantHasNoRegistration() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertNull(repository.findByRegistrationId("acme"));
    }

    @Test
    void publishesTenantSloEndpointAndSignsRpInitiatedLogoutRequest() throws Exception {
        MonitorTenantSamlConfig config = new MonitorTenantSamlConfig();
        config.setTenantKey("acme");
        config.setEnabled(1);
        config.setUpdateTime(new Date(1_000));
        config.setMetadataXml(metadata("https://idp.example.com"));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(config);

        MonitorSamlSigningCredential signingCredential = signingCredentialFromEphemeralKeyPair();
        MonitorSamlRegistrationRepository signedRepository = new MonitorSamlRegistrationRepository(mapper, signingCredential);
        RelyingPartyRegistration registration = signedRepository.findByRegistrationId("acme");

        assertNotNull(registration);
        assertEquals("https://idp.example.com/slo", registration.getAssertingPartyMetadata().getSingleLogoutServiceLocation());
        assertEquals(Saml2MessageBinding.POST,
                registration.getAssertingPartyMetadata().getSingleLogoutServiceBinding());
        assertEquals("{baseUrl}/logout/saml2/slo", registration.getSingleLogoutServiceLocation());
        assertEquals("{baseUrl}/logout/saml2/slo", registration.getSingleLogoutServiceResponseLocation());
        assertEquals(Saml2MessageBinding.POST, registration.getSingleLogoutServiceBinding());
        assertEquals(1, registration.getSigningX509Credentials().size());
        assertTrue(registration.isAuthnRequestsSigned());

        MockHttpServletRequest metadataRequest = request("GET", "/saml2/metadata/acme");
        var metadata = new RequestMatcherMetadataResponseResolver(signedRepository, new OpenSamlMetadataResolver())
                .resolve(metadataRequest);
        assertNotNull(metadata);
        assertTrue(metadata.getMetadata().contains("https://admin.example.test/logout/saml2/slo"));
        assertTrue(metadata.getMetadata().contains("SingleLogoutService"));
        assertTrue(metadata.getMetadata().contains("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"));
        assertTrue(metadata.getMetadata().contains("KeyDescriptor"));

        Saml2AuthenticatedPrincipal principal = mock(Saml2AuthenticatedPrincipal.class);
        when(principal.getName()).thenReturn("alice@example.test");
        when(principal.getRelyingPartyRegistrationId()).thenReturn("acme");
        var authentication = new TestingAuthenticationToken(principal, "", "ROLE_USER");
        var logoutRequest = new OpenSaml4LogoutRequestResolver(signedRepository)
                .resolve(request("POST", "/logout"), authentication);

        assertNotNull(logoutRequest);
        assertEquals("https://idp.example.com/slo", logoutRequest.getLocation());
        assertEquals(Saml2MessageBinding.POST, logoutRequest.getBinding());
        String xml = new String(Base64.getDecoder().decode(logoutRequest.getSamlRequest()), StandardCharsets.UTF_8);
        assertTrue(xml.contains("LogoutRequest"));
        assertTrue(xml.contains("Signature"));
    }

    private static MonitorSamlSigningCredential signingCredentialFromEphemeralKeyPair() throws Exception {
        Path tempDirectory = Files.createTempDirectory("monitor-saml-signing-test-");
        Path keyStorePath = tempDirectory.resolve("sp.p12");
        char[] password = UUID.randomUUID().toString().replace("-", "").toCharArray();
        try {
            Process keytool = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "sp", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", keyStorePath.toString(),
                    "-storepass", new String(password), "-keypass", new String(password),
                    "-dname", "CN=SAML SLO test SP", "-validity", "7")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!keytool.waitFor(30, TimeUnit.SECONDS)) {
                keytool.destroyForcibly();
                throw new IllegalStateException("keytool timed out while generating the test certificate");
            }
            if (keytool.exitValue() != 0) {
                throw new IllegalStateException("keytool could not generate the test certificate");
            }

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStorePath)) {
                keyStore.load(input, password);
            }
            PrivateKey privateKey = (PrivateKey) keyStore.getKey("sp", password);
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate("sp");
            MonitorSamlSigningCredential credential = new MonitorSamlSigningCredential(
                    Base64.getEncoder().encodeToString(privateKey.getEncoded()),
                    Base64.getEncoder().encodeToString(certificate.getEncoded()));
            credential.initialize();
            return credential;
        } finally {
            Files.deleteIfExists(keyStorePath);
            Files.deleteIfExists(tempDirectory);
        }
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setScheme("https");
        request.setServerName("admin.example.test");
        request.setServerPort(443);
        request.setRequestURI(path);
        return request;
    }

    static String metadata(String entityId) {
        return """
                <EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <KeyDescriptor use="signing">
                      <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                        <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
                      </ds:KeyInfo>
                    </KeyDescriptor>
                    <SingleLogoutService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="https://idp.example.com/slo"/>
                    <SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="https://idp.example.com/sso"/>
                    <SingleLogoutService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="https://idp.example.com/slo"/>
                  </IDPSSODescriptor>
                </EntityDescriptor>
                """.formatted(entityId, "MIIDETCCAfmgAwIBAgIUWAnm9/02lnVYsLZ7xm8InT+/wh8wDQYJKoZIhvcNAQELBQAwGDEWMBQGA1UEAwwNVGVzdCBTQU1MIElkUDAeFw0yNjEwMDIxODU4MzhaFw0zNjA5MjkxODU4MzhaMBgxFjAUBgNVBAMMDVRlc3QgU0FNTCBJZFAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCkAER8GR3l34LtlHB7VU82As/GCNQjweug8hpIXP03agjM3vDb8dHVmiF4ZFxKYHrHeFAEBJjjtTprCKlFwM7NBAu5t2Peq1ogeomXkFn9+I/KgnFU2R4bcaz9jjPfdkVkk8xcGCsyw9tWfzRw2sljjogweS0v6zySDKa+c1E1aIV4Z5QRgr4uexr6ai+IB5Bw9iRH/gSnVtPqeJrDGoSjUAv3CKlMM3TQor6FvijzkBKQAgbVrrBSjHemZMjrHUkDVo54s5xBLVCcIMGxK6Q9QW20hT/RBW/SovHGeszxsG6/bFsYeHCPnMO2Ao5fl3CKZQaVD51wg/SeQlQlEBuBAgMBAAGjUzBRMB0GA1UdDgQWBBRvqly+J0o36Y6qCIEQv6D/oj4W1jAfBgNVHSMEGDAWgBRvqly+J0o36Y6qCIEQv6D/oj4W1jAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBjWulYV7rl+0CY5qK5BdcbEnkEZI3VDxUu3cpGleQAj5H9pSTL8lYFu/6QlRIVAX+vcQ4EXJ7ByvkmwMpxC5EMEgIWJ1BS4L957zRrdEiZNynqaLpru2POWxbbptMNuCNDDzdxe087M1ME+xCbtgJKOIG4SPfFtbPWSSCSo5ey0gBswZkn0rWeop2/iRJa4wWlwDkD5pWIBOScnsdDNi69/Swgrz85JqZjYCdGqYzyz9G5RbkqamXjsY4PnmydtWyqtu2r/qw6pklPnjqM2xSnOh18D5ShCXzW40Y/yoGd/Sbvjd/fgmsyTpmcyWQrpSseQz3t9Lpx4y8I0ZBwlajw");
    }
}
