package com.weartak.atak.weartak_companion.plugin;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class TakServerListAdapter extends RecyclerView.Adapter<TakServerListAdapter.Holder> {

    public interface Listener {
        void onSelected(int index);
        void onEnabledToggled(int index, boolean enabled);
        void onDeleteClicked(int index);
    }

    private final Listener listener;
    private final List<TakServerItem> items = new ArrayList<>();
    private int selectedIndex = -1;

    public TakServerListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<TakServerItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        if (selectedIndex >= items.size()) selectedIndex = -1;
        notifyDataSetChanged();
    }

    public List<TakServerItem> getItems() {
        return new ArrayList<>(items);
    }

    public void setSelectedIndex(int idx) {
        selectedIndex = idx;
        notifyDataSetChanged();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_tak_server, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, @SuppressLint("RecyclerView") int position) {
        TakServerItem item = items.get(position);

        h.name.setText((item.name == null || item.name.isEmpty()) ? "Unnamed Server" : item.name);
        String addr = (item.address == null) ? "" : item.address;
        String authLabel;
        if (item.isP12Cert) {
            authLabel = (item.p12Cert == null || item.p12Cert.isEmpty()) ? "P12 pending" : "P12 loaded";
        } else {
            authLabel = "Username/password";
        }
        h.sub.setText(addr + ":" + item.port + " | " + authLabel);

        h.enabled.setOnCheckedChangeListener(null);
        h.enabled.setChecked(item.isEnabled);
        h.enabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.isEnabled = isChecked;
            if (listener != null) listener.onEnabledToggled(position, isChecked);
        });

        boolean selected = (position == selectedIndex);
        h.root.setBackgroundResource(selected ? R.drawable.bg_row_selected : R.drawable.bg_row_normal);

        h.root.setOnClickListener(v -> {
            selectedIndex = position;
            notifyDataSetChanged();
            if (listener != null) listener.onSelected(position);
        });

        // NEW: delete button
        if (h.deleteBtn != null) {
            h.deleteBtn.setOnClickListener(v -> {
                int idx = h.getBindingAdapterPosition();
                if (idx == RecyclerView.NO_POSITION) return;
                if (listener != null) listener.onDeleteClicked(idx);
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        View root;
        TextView name;
        TextView sub;
        CheckBox enabled;
        ImageButton deleteBtn;
        Holder(@NonNull View itemView) {
            super(itemView);
            root = itemView;
            name = itemView.findViewById(R.id.serverName);
            sub = itemView.findViewById(R.id.serverSub);
            enabled = itemView.findViewById(R.id.serverEnabled);
            deleteBtn = itemView.findViewById(R.id.serverDelete);
        }
    }
}
