package com.devin.jarvis.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.devin.jarvis.R;

import java.util.ArrayList;
import java.util.List;

public class TranscriptAdapter extends RecyclerView.Adapter<TranscriptAdapter.VH> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_JARVIS = 1;
    private static final int MAX_ITEMS = 200;

    private static class Line {
        final String text;
        final boolean fromUser;
        Line(String t, boolean u) { text = t; fromUser = u; }
    }

    private final List<Line> data = new ArrayList<>();

    public void append(String text, boolean fromUser) {
        if (text == null || text.isEmpty()) return;
        // Coalesce: if last line is same author, replace if user-partial.
        if (!data.isEmpty()) {
            Line last = data.get(data.size() - 1);
            if (last.fromUser && fromUser) {
                data.set(data.size() - 1, new Line(text, true));
                notifyItemChanged(data.size() - 1);
                return;
            }
        }
        data.add(new Line(text, fromUser));
        notifyItemInserted(data.size() - 1);
        while (data.size() > MAX_ITEMS) {
            data.remove(0);
            notifyItemRemoved(0);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return data.get(position).fromUser ? TYPE_USER : TYPE_JARVIS;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = (viewType == TYPE_USER)
                ? R.layout.item_transcript_user
                : R.layout.item_transcript_jarvis;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.text.setText(data.get(position).text);
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;
        VH(@NonNull View v) { super(v); text = v.findViewById(R.id.lineText); }
    }
}
