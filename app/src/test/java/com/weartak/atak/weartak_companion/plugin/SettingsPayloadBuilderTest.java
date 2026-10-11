package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class SettingsPayloadBuilderTest {
    @Test
    public void oneTimeUploadPreservesP12AndCustomWatchCallsignWithoutPhoneManagement() throws Exception {
        TakServerItem server = TakServerItem.fromJson(new JSONObject()
                .put("name", "TAK").put("address", "tak.example").put("port", 8089)
                .put("isEnabled", true).put("isP12Cert", true)
                .put("p12Cert", "dGVzdC1jZXJ0").put("p12CertPassword", "test-password"));
        JSONObject envelope = SettingsPayloadBuilder.build(Collections.singletonList(server),
                30, "ODIN-WEARTAK", "Blue", "Medic", false);
        assertEquals("set_settings", envelope.getString("msg_type"));
        JSONObject payload = envelope.getJSONObject("payload");
        assertFalse(payload.getBoolean("phoneManagedSettings"));
        assertEquals("ODIN-WEARTAK", payload.getString("callsign"));
        assertFalse(payload.has("team"));
        assertFalse(payload.has("role"));
        TakServerItem sent = TakServerItem.fromJson(payload.getJSONArray("takServerList").getJSONObject(0));
        assertTrue(sent.isP12Cert);
        assertEquals(server.p12Cert, sent.p12Cert);
        assertEquals(server.p12CertPassword, sent.p12CertPassword);
    }

    @Test
    public void oneTimeProvisioningDoesNotRequirePhoneIdentityOrReporting() throws Exception {
        JSONObject payload = SettingsPayloadBuilder.build(Collections.emptyList(),
                null, null, null, null, false).getJSONObject("payload");
        assertFalse(payload.getBoolean("phoneManagedSettings"));
        assertFalse(payload.has("callsign"));
        assertFalse(payload.has("reportIntSecs"));
    }

    @Test
    public void managementMustBeExplicitAndIncludesPhoneIdentity() throws Exception {
        JSONObject payload = SettingsPayloadBuilder.build(Collections.emptyList(),
                5, "PHONE", "Dark Green", "K9", true).getJSONObject("payload");
        assertTrue(payload.getBoolean("phoneManagedSettings"));
        assertEquals("PHONE", payload.getString("callsign"));
        assertEquals("Dark Green", payload.getString("team"));
        assertEquals("K9", payload.getString("role"));
        assertThrows(IllegalArgumentException.class, () -> SettingsPayloadBuilder.build(
                Collections.emptyList(), null, null, null, null, true));
    }

    @Test
    public void rejectsMissingP12AndInvalidReportingBeforeSending() throws Exception {
        TakServerItem server = TakServerItem.fromJson(new JSONObject()
                .put("name", "TAK").put("isP12Cert", true).put("p12Cert", ""));
        assertThrows(IllegalArgumentException.class, () -> SettingsPayloadBuilder.build(
                Collections.singletonList(server), 30, "WATCH", null, null, false));
        assertThrows(IllegalArgumentException.class, () -> SettingsPayloadBuilder.build(
                Collections.emptyList(), -1, "WATCH", null, null, false));
    }
}
