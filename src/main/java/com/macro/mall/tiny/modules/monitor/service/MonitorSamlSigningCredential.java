package com.macro.mall.tiny.modules.monitor.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

@Component
public class MonitorSamlSigningCredential {
    private final String privateKeyBase64;
    private final String certificateBase64;
    private Saml2X509Credential signingCredential;

    public MonitorSamlSigningCredential(
            @Value("${monitor.sso.sp-private-key-base64:}") String privateKeyBase64,
            @Value("${monitor.sso.sp-certificate-base64:}") String certificateBase64) {
        this.privateKeyBase64 = privateKeyBase64;
        this.certificateBase64 = certificateBase64;
    }

    @PostConstruct
    void initialize() {
        boolean hasPrivateKey = StringUtils.hasText(privateKeyBase64);
        boolean hasCertificate = StringUtils.hasText(certificateBase64);
        if (!hasPrivateKey && !hasCertificate) return;
        if (hasPrivateKey != hasCertificate) {
            throw new IllegalStateException("Both SAML SP signing key and certificate must be configured");
        }
        try {
            byte[] privateKeyDer = Base64.getDecoder().decode(privateKeyBase64.trim());
            byte[] certificateDer = Base64.getDecoder().decode(certificateBase64.trim());
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificateDer));
            certificate.checkValidity();
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));

            byte[] challenge = "monitor-saml-sp-key-pair-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(certificate.getPublicKey());
            signature.update(challenge);
            if (!signature.verify(signed)) {
                throw new IllegalArgumentException("SAML SP signing key does not match its certificate");
            }
            this.signingCredential = Saml2X509Credential.signing(privateKey, certificate);
        } catch (Exception e) {
            throw new IllegalStateException("SAML SP signing key or certificate is invalid", e);
        }
    }

    public Saml2X509Credential get() {
        return signingCredential;
    }
}
