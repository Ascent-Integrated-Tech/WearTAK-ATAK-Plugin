package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class AutoReconnectPreferenceTest {
    private final PrefContext plugin = new PrefContext();
    private final PrefContext host = new PrefContext();

    private AutoReconnectPreference load() {
        return new AutoReconnectPreference(PluginPrefs.get(plugin, host, AutoReconnectPreference.NAME));
    }

    @Test public void defaultOffAndExplicitChoiceSurviveReload() {
        assertFalse(load().enabled());
        load().setEnabled(true);
        assertTrue(load().enabled());
        load().setEnabled(false);
        assertFalse(load().enabled());
    }

    @Test public void usesHostDataDirectoryNotPluginPackage() {
        load().setEnabled(true);
        assertTrue(host.files.containsKey(AutoReconnectPreference.NAME));
        assertTrue(plugin.files.isEmpty());
    }

    @Test public void experimentalAndSamsungOptInsAreNotMigrated() {
        host.getSharedPreferences("wtk_pairing", 0).edit()
                .putBoolean("auto_reconnect_enabled", true).apply();
        plugin.getSharedPreferences(AutoReconnectPreference.NAME, 0).edit()
                .putBoolean("auto_reconnect_enabled", true).apply();
        host.getSharedPreferences("weartak_plugin", 0).edit()
                .putBoolean("system_bonded_enabled", true).apply();
        assertFalse(load().enabled());
    }

    @Test public void fallbackContextUsesItsApplicationDataDirectory() {
        SharedPreferences p = PluginPrefs.get(plugin, null, AutoReconnectPreference.NAME);
        new AutoReconnectPreference(p).setEnabled(true);
        assertTrue(new AutoReconnectPreference(
                PluginPrefs.get(plugin, null, AutoReconnectPreference.NAME)).enabled());
    }

    @Test public void readyPeerSurvivesReloadAndManualDisconnectDisarmsIt() {
        assertNull(load().armedAddress());
        load().arm("bonded-watch");
        assertEquals("bonded-watch", load().armedAddress());
        load().setEnabled(false);
        assertEquals("bonded-watch", load().armedAddress());
        load().disarm();
        assertNull(load().armedAddress());
    }

    private static final class PrefContext extends ContextWrapper {
        final Map<String, Map<String, Object>> files = new HashMap<>();
        PrefContext() { super(null); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            Map<String, Object> values = files.computeIfAbsent(name, n -> new HashMap<>());
            SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        if ("putBoolean".equals(method.getName()) || "putString".equals(method.getName())) {
                            values.put((String) args[0], args[1]);
                            return proxy;
                        }
                        if ("remove".equals(method.getName())) {
                            values.remove(args[0]);
                            return proxy;
                        }
                        if ("apply".equals(method.getName())) return null;
                        throw new AssertionError(method.getName());
                    });
            return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if ("getBoolean".equals(method.getName()) || "getString".equals(method.getName()))
                            return values.getOrDefault(args[0], args[1]);
                        if ("edit".equals(method.getName())) return editor;
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
