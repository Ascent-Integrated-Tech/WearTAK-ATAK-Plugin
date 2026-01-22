package com.weartak.atak.weartak_companion.plugin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BleDeviceAdapter extends RecyclerView.Adapter<BleDeviceAdapter.VH> {

    public interface Listener {
        void onConnectClicked(WearTakBleClient.DiscoveredDevice device);
        void onSettingsClicked(WearTakBleClient.DiscoveredDevice device);
    }

    private final Listener listener;
    private final List<WearTakBleClient.DiscoveredDevice> items = new ArrayList<>();

    private String connectingAddress = null;
    private String connectedAddress = null;

    public BleDeviceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setDevices(List<WearTakBleClient.DiscoveredDevice> devices) {
        items.clear();
        if (devices != null) items.addAll(devices);
        notifyDataSetChanged();
    }

    public void setConnecting(String address) {
        connectingAddress = address;
        notifyDataSetChanged();
    }

    public void setConnected(String address) {
        connectedAddress = address;
        connectingAddress = null;
        notifyDataSetChanged();
    }

    public void clearConnectionMarkers() {
        connectingAddress = null;
        connectedAddress = null;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ble_device, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        WearTakBleClient.DiscoveredDevice d = items.get(position);

        String name = (d.name != null && !d.name.isEmpty()) ? d.name : "Unknown";
        h.deviceName.setText(name);
        h.deviceMac.setText(d.address);
        h.rssiBadge.setText(d.rssi + " dBm");

        boolean isConnected = d.address != null && d.address.equals(connectedAddress);
        boolean isConnecting = d.address != null && d.address.equals(connectingAddress);

        h.actionButton.setOnClickListener(null);

        if (isConnected) {
            h.deviceState.setText("Connected");
            h.actionButton.setText("Settings");
            h.actionButton.setEnabled(true);
            h.actionButton.setOnClickListener(v -> {
                if (listener != null) listener.onSettingsClicked(d);
            });
        } else if (isConnecting) {
            h.deviceState.setText("Connecting…");
            h.actionButton.setText("Connecting");
            h.actionButton.setEnabled(false);
        } else {
            h.deviceState.setText("");
            h.actionButton.setText("Connect");
            h.actionButton.setEnabled(true);
            h.actionButton.setOnClickListener(v -> {
                if (listener != null) listener.onConnectClicked(d);
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView deviceName, deviceMac, rssiBadge, deviceState;
        Button actionButton;

        VH(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.deviceName);
            deviceMac = itemView.findViewById(R.id.deviceMac);
            rssiBadge = itemView.findViewById(R.id.rssiBadge);
            deviceState = itemView.findViewById(R.id.deviceState);
            actionButton = itemView.findViewById(R.id.actionButton);
        }
    }
}