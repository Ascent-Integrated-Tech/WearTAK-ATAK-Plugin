package com.weartak.atak.weartak_companion.plugin;

public class TakServerItem {
    public boolean isEnabled;
    public String name;
    public String address;
    public int port;
    public boolean isP12Cert;
    public String username;
    public String password;
    public String p12Cert;
    public String p12CertPassword;

    public TakServerItem() {}

    public static TakServerItem fromJson(org.json.JSONObject o) {
        TakServerItem t = new TakServerItem();
        if (o == null) return t;
        t.isEnabled = o.optBoolean("isEnabled", false);
        t.name = o.optString("name", "");
        t.address = o.optString("address", "");
        t.port = o.optInt("port", 0);
        t.isP12Cert = o.optBoolean("isP12Cert", false);
        t.username = o.optString("username", "");
        t.password = o.optString("password", "");
        t.p12Cert = o.optString("p12Cert", "");
        t.p12CertPassword = o.optString("p12CertPassword", "");
        return t;
    }

    public org.json.JSONObject toJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("isEnabled", isEnabled);
            o.put("name", name);
            o.put("address", address);
            o.put("port", port);
            o.put("isP12Cert", isP12Cert);
            o.put("username", username);
            o.put("password", password);
            o.put("p12Cert", p12Cert);
            o.put("p12CertPassword", p12CertPassword);
        } catch (Throwable ignored) {}
        return o;
    }
}