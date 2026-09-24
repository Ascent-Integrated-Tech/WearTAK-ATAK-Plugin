/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class TakServerItemTest {
    @Test
    public void roundTripPreservesServerId() throws Exception {
        JSONObject json = serverJson();
        json.put("serverId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

        TakServerItem item = TakServerItem.fromJson(json);

        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", item.serverId);
        assertEquals(item.serverId, item.toJson().getString("serverId"));
    }

    @Test
    public void legacyPayloadWithoutServerIdRemainsSupported() throws Exception {
        TakServerItem item = TakServerItem.fromJson(serverJson());

        assertNull(item.serverId);
        assertFalse(item.toJson().has("serverId"));
    }

    private static JSONObject serverJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("isEnabled", true);
        json.put("name", "TAK");
        json.put("address", "tak.example");
        json.put("port", 8089);
        json.put("isP12Cert", false);
        json.put("username", "user");
        json.put("password", "password");
        json.put("p12Cert", "");
        json.put("p12CertPassword", "");
        return json;
    }
}
