package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GarminConnectIqClientTest {
    private final FakeSdk sdk = new FakeSdk();
    private final RecordingListener listener = new RecordingListener();
    private final GarminConnectIqClient client = new GarminConnectIqClient(null, listener, sdk);
    private final IQDevice watch = new IQDevice(1, "Watch");

    private void connect() {
        sdk.known = Collections.singletonList(watch);
        client.start();
        sdk.lifecycle.onSdkReady();
        assertTrue(client.isConnected());
    }

    @Test
    public void staleLifecycleCallbacksDoNotBreakReenabledSession() {
        connect();
        ConnectIQ.ConnectIQListener old = sdk.lifecycle;
        client.stop();
        connect();
        String status = client.getStatus();
        old.onSdkShutDown();
        old.onInitializeError(ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED);
        old.onSdkReady();
        assertEquals(status, client.getStatus());
        assertTrue(client.isConnected());
        assertTrue(client.sendMessage("entities", null));
    }

    @Test
    public void callbacksAfterStopDoNotRestartOrPublish() {
        connect();
        ConnectIQ.ConnectIQListener old = sdk.lifecycle;
        client.stop();
        int notifications = listener.notifications;
        old.onSdkReady();
        old.onSdkShutDown();
        old.onInitializeError(ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED);
        assertEquals(notifications, listener.notifications);
        assertFalse(client.isConnected());
        assertFalse(client.sendMessage("entities", null));
    }

    @Test
    public void staleDeviceAppAndSendCallbacksDoNotAffectNewSession() {
        connect();
        ConnectIQ.IQDeviceEventListener oldDevice = sdk.deviceListeners.get(1L);
        ConnectIQ.IQApplicationEventListener oldApp = sdk.appListeners.get(1L);
        assertTrue(client.sendMessage("entities", null));
        ConnectIQ.IQSendMessageListener oldSend = sdk.sendListener;
        client.stop();
        connect();
        String status = client.getStatus();
        int notifications = listener.notifications;
        oldDevice.onDeviceStatusChanged(watch, IQDevice.IQDeviceStatus.NOT_CONNECTED);
        oldApp.onMessageReceived(watch, sdk.app,
                Collections.singletonList(Collections.singletonMap("msgType", "chat")),
                ConnectIQ.IQMessageStatus.SUCCESS);
        oldSend.onMessageStatus(watch, sdk.app, ConnectIQ.IQMessageStatus.FAILURE_UNKNOWN);
        assertEquals(status, client.getStatus());
        assertEquals(notifications, listener.notifications);
        assertEquals(0, listener.messages);
        assertTrue(client.isConnected());
    }

    @Test
    public void emptyAndNullPairedListsRemoveDevicesAndIgnoreQueuedEvents() {
        connect();
        ConnectIQ.IQDeviceEventListener oldDevice = sdk.deviceListeners.get(1L);
        ConnectIQ.IQApplicationEventListener oldApp = sdk.appListeners.get(1L);
        sdk.known = Collections.emptyList();
        client.refreshDevices();
        assertEquals(Collections.singletonList(1L), sdk.unregistered);
        assertFalse(client.isConnected());
        assertFalse(client.sendMessage("entities", null));
        String status = client.getStatus();
        oldDevice.onDeviceStatusChanged(watch, IQDevice.IQDeviceStatus.CONNECTED);
        oldApp.onMessageReceived(watch, sdk.app,
                Collections.singletonList(Collections.singletonMap("msgType", "chat")),
                ConnectIQ.IQMessageStatus.SUCCESS);
        assertEquals(status, client.getStatus());
        assertEquals(0, listener.messages);
        assertFalse(client.isConnected());
        connect();
        sdk.known = null;
        client.refreshDevices();
        assertFalse(client.isConnected());
    }

    @Test
    public void partialPairedListKeepsOnlyRemainingSendTarget() {
        sdk.known = Arrays.asList(watch, new IQDevice(2, "Other"));
        client.start();
        sdk.lifecycle.onSdkReady();
        sdk.known = Collections.singletonList(watch);
        client.refreshDevices();
        assertEquals(Collections.singletonList(2L), sdk.unregistered);
        assertTrue(client.sendMessage("entities", null));
        assertEquals(Collections.singletonList(1L), sdk.sent);
    }

    @Test
    public void lateAppLookupCannotRegisterRemovedOrPreviousSessionDevice() {
        sdk.deferAppInfo = true;
        connect();
        ConnectIQ.IQApplicationInfoListener old = sdk.appInfo;
        sdk.known = Collections.emptyList();
        client.refreshDevices();
        String status = client.getStatus();
        old.onApplicationInfoReceived(sdk.app);
        old.onApplicationNotInstalled("weartak");
        assertEquals(status, client.getStatus());
        assertTrue(sdk.appListeners.isEmpty());
        connect();
        ConnectIQ.IQApplicationInfoListener previous = sdk.appInfo;
        client.stop();
        connect();
        previous.onApplicationInfoReceived(sdk.app);
        assertTrue(sdk.appListeners.isEmpty());
        sdk.appInfo.onApplicationInfoReceived(sdk.app);
        assertTrue(client.sendMessage("entities", null));
    }

    @Test
    public void sendReturnsFalseWhenEverySdkCallThrows() {
        connect();
        sdk.runtimeFailure = new IllegalArgumentException("bad message");
        assertFalse(client.sendMessage("entities", null));
        sdk.runtimeFailure = null;
        sdk.invalidState = true;
        assertFalse(client.sendMessage("entities", null));
        sdk.invalidState = false;
        sdk.unavailable = true;
        assertFalse(client.sendMessage("entities", null));
    }

    @Test
    public void sendReturnsTrueWhenAtLeastOneTargetAcceptsMessage() {
        sdk.known = Arrays.asList(watch, new IQDevice(2, "Other"));
        sdk.failDevice = 1;
        client.start();
        sdk.lifecycle.onSdkReady();
        assertTrue(client.sendMessage("entities", null));
        assertEquals(Collections.singletonList(2L), sdk.sent);
    }

    @Test
    public void refreshRetriesFailedEventRegistrationForCachedApplication() {
        sdk.failAppRegistration = true;
        connect();
        assertTrue(sdk.appListeners.isEmpty());
        assertEquals(1, sdk.appInfoRequests);
        sdk.failAppRegistration = false;
        client.refreshDevices();
        assertEquals(1, sdk.appInfoRequests);
        assertEquals(2, sdk.appRegistrationAttempts);
        assertTrue(sdk.appListeners.containsKey(1L));
        client.refreshDevices();
        assertEquals(2, sdk.appRegistrationAttempts);
    }

    @Test
    public void reconnectRetriesFailedEventRegistrationForCachedApplication() {
        sdk.failAppRegistration = true;
        connect();
        sdk.failAppRegistration = false;
        sdk.deviceListeners.get(1L).onDeviceStatusChanged(
                watch, IQDevice.IQDeviceStatus.CONNECTED);
        assertEquals(1, sdk.appInfoRequests);
        assertEquals(2, sdk.appRegistrationAttempts);
        assertTrue(sdk.appListeners.containsKey(1L));
    }

    private static class RecordingListener implements GarminConnectIqClient.Listener {
        int notifications;
        int messages;
        public void onStatusChanged(String status) { notifications++; }
        public void onConnectionChanged(boolean connected) { notifications++; }
        public void onMessageReceived(JSONObject envelope) { messages++; }
    }

    private static class FakeSdk extends ConnectIQ {
        ConnectIQListener lifecycle;
        IQApplicationInfoListener appInfo;
        IQSendMessageListener sendListener;
        List<IQDevice> known = Collections.emptyList();
        final IQApp app = new IQApp("5721f67e-bcc4-47e8-b337-2ad96ee77c0a");
        final Map<Long, IQDeviceEventListener> deviceListeners = new HashMap<>();
        final Map<Long, IQApplicationEventListener> appListeners = new HashMap<>();
        final List<Long> unregistered = new ArrayList<>();
        final List<Long> sent = new ArrayList<>();
        boolean deferAppInfo;
        boolean failAppRegistration;
        int appInfoRequests;
        int appRegistrationAttempts;
        boolean invalidState;
        boolean unavailable;
        long failDevice = -1;
        RuntimeException runtimeFailure;

        @Override public void initialize(Context context, boolean autoUI, ConnectIQListener callback) {
            lifecycle = callback;
        }
        @Override public void shutdown(Context context) { lifecycle.onSdkShutDown(); }
        @Override public void unregisterAllForEvents() {
            deviceListeners.clear();
            appListeners.clear();
        }
        @Override public void unregisterForEvents(IQDevice device) {
            long id = device.getDeviceIdentifier();
            unregistered.add(id);
            deviceListeners.remove(id);
            appListeners.remove(id);
        }
        @Override public List<IQDevice> getKnownDevices() { return known; }
        @Override public List<IQDevice> getConnectedDevices() { return known; }
        @Override public List<IQDevice> getLinkedCompanionDevices() { return known; }
        @Override public IQDevice.IQDeviceStatus getDeviceStatus(IQDevice device) {
            return IQDevice.IQDeviceStatus.CONNECTED;
        }
        @Override public String getDevicePartNumber(IQDevice device) { return "test"; }
        @Override public void registerForDeviceEvents(IQDevice device, IQDeviceEventListener callback) {
            deviceListeners.put(device.getDeviceIdentifier(), callback);
        }
        @Override public void getApplicationInfo(String id, IQDevice device,
                                                IQApplicationInfoListener callback) {
            appInfoRequests++;
            appInfo = callback;
            if (!deferAppInfo) callback.onApplicationInfoReceived(app);
        }
        @Override public void registerForAppEvents(IQDevice device, IQApp app,
                                                  IQApplicationEventListener callback)
                throws ServiceUnavailableException {
            appRegistrationAttempts++;
            if (failAppRegistration) throw new ServiceUnavailableException("test");
            appListeners.put(device.getDeviceIdentifier(), callback);
        }
        @Override public void sendMessage(IQDevice device, IQApp app, Object message,
                                          IQSendMessageListener callback)
                throws InvalidStateException, ServiceUnavailableException {
            if (runtimeFailure != null) throw runtimeFailure;
            if (invalidState) throw new InvalidStateException("test");
            if (unavailable || device.getDeviceIdentifier() == failDevice) {
                throw new ServiceUnavailableException("test");
            }
            sent.add(device.getDeviceIdentifier());
            sendListener = callback;
        }
        @Override protected void sendMessageProtocol(IQDevice d, IQApp a, byte[] b,
                                                      IQSendMessageListener l, boolean r) {}
        @Override protected void sendImageProtocol(IQDevice d, IQApp a, byte[] b,
                                                    IQSendImageListener l) {}
        @Override protected void registerForRemoteAppEvents(IQApp app) {}
        @Override protected void unregisterForRemoteAppEvents(IQApp app) {}
        @Override protected void registerAppWithBindingService(String id) {}
        @Override protected void unregisterAppWithBindingService(String id) {}
    }
}
