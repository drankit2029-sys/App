package com.example.hello;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class TakesAdapter extends RecyclerView.Adapter<TakesAdapter.Vh> {

    public interface Listener {
        void onPlay(Take take);
        void onFavorite(Take take);
        void onMore(Take take, View anchor);
    }

    private final Listener listener;
    private List<Take> takes = new ArrayList<>();
    private long playingId = -1;

    public TakesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setTakes(List<Take> takes) {
        this.takes = takes;
        notifyDataSetChanged();
    }

    public void setPlayingId(long id) {
        long old = playingId;
        playingId = id;
        if (old != -1) notifyForId(old);
        if (id != -1) notifyForId(id);
    }

    private void notifyForId(long id) {
        for (int i = 0; i < takes.size(); i++) {
            if (takes.get(i).id == id) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public Vh onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_take, parent, false);
        return new Vh(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Vh h, int position) {
        Take t = takes.get(position);
        h.title.setText(t.title);
        h.meta.setText(Take.formatDuration(t.durationMs) + "  •  " + t.dateLabel + "  •  " + t.sizeLabel);
        boolean playing = t.id == playingId;
        h.play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        h.play.setContentDescription(h.itemView.getContext().getString(R.string.cd_play));
        h.star.setImageResource(t.favorite ? R.drawable.ic_star : R.drawable.ic_heart);
        h.star.setAlpha(t.favorite ? 1f : 0.45f);
        h.star.setColorFilter(t.favorite ? 0xFFFF9F0A : 0xFFFFFFFF);
        h.playBtn.setOnClickListener(v -> listener.onPlay(t));
        h.star.setOnClickListener(v -> listener.onFavorite(t));
        h.more.setOnClickListener(v -> listener.onMore(t, v));
        h.itemView.setOnClickListener(v -> listener.onPlay(t));
    }

    @Override
    public int getItemCount() {
        return takes.size();
    }

    static class Vh extends RecyclerView.ViewHolder {
        final View playBtn;
        final ImageView play;
        final TextView title;
        final TextView meta;
        final ImageView star;
        final ImageView more;

        Vh(@NonNull View itemView) {
            super(itemView);
            playBtn = itemView.findViewById(R.id.playBtn);
            play = itemView.findViewById(R.id.playIcon);
            title = itemView.findViewById(R.id.takeTitle);
            meta = itemView.findViewById(R.id.takeMeta);
            star = itemView.findViewById(R.id.favIcon);
            more = itemView.findViewById(R.id.moreIcon);
        }
    }
}
