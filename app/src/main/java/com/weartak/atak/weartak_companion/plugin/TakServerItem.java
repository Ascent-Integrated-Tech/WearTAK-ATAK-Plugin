package com.weartak.atak.weartak_companion.plugin;

public class TakServerItem {
    public static final String DEFAULT_P12_PASSWORD = "atakatak";

    public boolean isEnabled;
    public String name;
    public String address;
    public int port;
    public boolean isP12Cert;
    public String username;
    public String password;
    public String p12Cert;
    public String p12CertPassword;
    public transient String p12DisplayName;

    public TakServerItem() {}

    public static String normalizeP12Password(String password) {
        if (password == null) return DEFAULT_P12_PASSWORD;
        String trimmed = password.trim();
        return trimmed.isEmpty() ? DEFAULT_P12_PASSWORD : trimmed;
    }

    public static String normalizeP12Cert(String cert) {
        if (cert == null) return "";
        return cert
                .replace("\\/", "/")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

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
        t.p12Cert = normalizeP12Cert(o.optString("p12Cert", ""));
        t.p12CertPassword = o.optString("p12CertPassword", "");
        if (t.isP12Cert) {
            t.p12CertPassword = normalizeP12Password(t.p12CertPassword);
        }
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
            o.put("p12Cert", normalizeP12Cert(p12Cert));
            o.put("p12CertPassword", isP12Cert ? normalizeP12Password(p12CertPassword) : p12CertPassword);
        } catch (Throwable ignored) {}
        return o;
    }
}
