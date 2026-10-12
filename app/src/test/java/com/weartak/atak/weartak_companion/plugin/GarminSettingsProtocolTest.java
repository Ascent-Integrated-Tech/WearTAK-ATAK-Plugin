package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class GarminSettingsProtocolTest {
    private static final String TRANSACTION = "12345678-1234-1234-1234-123456789abc";

    @Test public void generatedTransactionsAreBoundedAndStrictlyValidated() {
        String generated = GarminSettingsProtocol.newTransactionId();
        assertTrue(GarminSettingsProtocol.isValidTransactionId(generated));
        assertTrue(GarminSettingsProtocol.isValidTransactionId(TRANSACTION));
        assertFalse(GarminSettingsProtocol.isValidTransactionId("legacy"));
        assertFalse(GarminSettingsProtocol.isValidTransactionId(
                "12345678-1234-1234-1234-123456789abg"));
        assertFalse(GarminSettingsProtocol.isValidTransactionId(null));
        assertEquals(TRANSACTION, GarminSettingsProtocol.request(TRANSACTION).get("transactionId"));
    }

    @Test public void outboundGarminSettingsProjectOnlySupportedProfileFields() {
        Map<String, Object> oneTime = GarminSettingsProtocol.phoneSettings(
                TRANSACTION, "  WATCH  ", 30, "Blue", "Medic", false);
        assertEquals(4, oneTime.size());
        assertEquals(TRANSACTION, oneTime.get("transactionId"));
        assertEquals("WATCH", oneTime.get("callsign"));
        assertEquals(30, oneTime.get("reportIntSecs"));
        assertEquals(Boolean.FALSE, oneTime.get("phoneManagedSettings"));
        assertFalse(oneTime.containsKey("team"));
        assertFalse(oneTime.containsKey("takServerList"));
        assertFalse(oneTime.containsKey("p12Cert"));

        Map<String, Object> managed = GarminSettingsProtocol.phoneSettings(
                TRANSACTION, "WATCH", 5, "Blue", "Medic", true);
        assertEquals(6, managed.size());
        assertEquals("Blue", managed.get("team"));
        assertEquals("Medic", managed.get("role"));
        assertEquals(Boolean.TRUE, managed.get("phoneManagedSettings"));
        assertThrows(IllegalArgumentException.class, () -> GarminSettingsProtocol.phoneSettings(
                TRANSACTION, "WATCH", 5, "", "Medic", true));
        assertThrows(IllegalArgumentException.class, () -> GarminSettingsProtocol.phoneSettings(
                TRANSACTION, "WATCH", 3601, "Blue", "Medic", true));
    }

    @Test public void watchSnapshotRequiresTypedProfileFields() throws Exception {
        JSONObject complete = new JSONObject()
                .put("callsign", "WATCH")
                .put("team", "Blue")
                .put("role", "Medic")
                .put("reportIntSecs", 30)
                .put("dynamicReporting", false)
                .put("phoneManagedSettings", true);
        assertTrue(GarminSettingsProtocol.isCompleteSnapshot(complete));
        assertFalse(GarminSettingsProtocol.isCompleteSnapshot(
                complete.put("reportIntSecs", 30.5)));
        assertFalse(GarminSettingsProtocol.isCompleteSnapshot(
                complete.put("dynamicReporting", "false")));
    }
}
