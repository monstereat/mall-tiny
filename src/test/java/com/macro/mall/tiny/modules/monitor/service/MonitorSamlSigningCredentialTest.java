package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MonitorSamlSigningCredentialTest {
    @Test
    void signingRemainsOptionalWhenNoKeyPairIsConfigured() {
        MonitorSamlSigningCredential credential = new MonitorSamlSigningCredential("", "");

        credential.initialize();

        assertNull(credential.get());
    }

    @Test
    void rejectsOnlyOneConfiguredPartOfTheKeyPair() {
        MonitorSamlSigningCredential credential = new MonitorSamlSigningCredential("private-key", "");

        assertThrows(IllegalStateException.class, credential::initialize);
    }

    @Test
    void rejectsInvalidEncodedKeyMaterial() {
        MonitorSamlSigningCredential credential = new MonitorSamlSigningCredential("not-base64", "also-invalid");

        assertThrows(IllegalStateException.class, credential::initialize);
    }
}
