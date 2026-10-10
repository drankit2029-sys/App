package com.example.hello;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TakesAdapter extends RecyclerView.Adapter<TakesAdapter.Vh> {

    public interface Listener {
        void onCardClick(Take take);
        void onCardLongPress(Take take);
        void onPlay(Take take);
        void onFavorite(Take take);
        void onRename(Take take);
        void onExport(Take take);
        void onDelete(Take take);
        void onSeek(Take take, long posMs);
    }

    private final Listener listener;
    private List<Take> takes = new ArrayList<>();
    private long expandedId = -1;
    private long playingId = -1;
    private boolean paused = false;
    private boolean selectionMode = false;
    private Set<Long> selected = new HashSet<>();
    private final Map<Long, Long> progressMap = new HashMap<>();

    public TakesAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return takes.get(position).id;
    }

    public void setTakes(List<Take> takes) {
        this.takes = takes;
        // Drop stale expanded/playing refs that are no longer visible.
        boolean expAlive = false;
        for (Take t : takes) {
            if (t.id == expandedId) expAlive = true;
        }
        if (!expAlive) expandedId = -1;
        notifyDataSetChanged();
    }

    public void setExpandedId(long id) {
        long old = expandedId;
        expandedId = id;
        notifyForId(old);
        notifyForId(id);
    }

    public long getExpandedId() {
        return expandedId;
    }

    public void collapseAll() {
        long old = expandedId;
        expandedId = -1;
        notifyForId(old);
    }

    public void setPlayingId(long id) {
        long old = playingId;
        playingId = id;
        notifyForId(old);
        notifyForId(id);
    }

    public void setPaused(boolean p) {
        paused = p;
        notifyForId(playingId);
    }

    public void refreshItem(long id) {
        notifyForId(id);
    }

    public void setSelection(Set<Long> selected, boolean mode) {
        this.selected = new HashSet<>(selected);
        boolean modeChanged = this.selectionMode != mode;
        this.selectionMode = mode;
        if (modeChanged) {
            notifyDataSetChanged();
        } else {
            // Selection toggles handled by caller via notifySelection(id).
        }
    }

    public void notifySelection(long id) {
        notifyForId(id);
    }

    private void notifyForId(long id) {
        if (id == -1) return;
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
        boolean expanded = t.id == expandedId;
        boolean playing = t.id == playingId;
        boolean isSel = selectionMode && selected.contains(t.id);

        h.cardRoot.setBackgroundResource(isSel ? R.drawable.bg_card_selected : R.drawable.bg_card);
        h.selectBadge.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.selectBadge.setImageResource(isSel ? R.drawable.ic_checked : R.drawable.bg_select_ring);
        h.selectBadge.setAlpha(isSel ? 1f : 0.5f);

        h.title.setText(t.title);
        h.meta.setText(Take.formatDuration(t.durationMs) + "  •  " + t.dateLabel + "  •  " + t.sizeLabel);
        h.formatBadge.setText(t.format);
        h.play.setImageResource((playing && !paused) ? R.drawable.ic_pause : R.drawable.ic_play);
        h.play.setContentDescription(h.itemView.getContext().getString(R.string.cd_play));

        // Professional heart: outline idle, filled red favorite, with a pop on tap.
        h.fav.setImageResource(t.favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        h.fav.setAlpha(t.favorite ? 1f : 0.55f);
        h.fav.setColorFilter(null);

        h.chevron.setRotation(expanded ? 180f : 0f);
        h.expanded.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expanded) {
            h.totalTime.setText(Take.formatDuration(t.durationMs));
            long shown = playing ? progressMap.getOrDefault(t.id, 0L) : 0L;
            if (!h.tracking) {
                h.curTime.setText(Take.formatDuration(shown));
                h.scrubber.setProgress(t.durationMs <= 0 ? 0
                        : (int) (shown * 1000L / t.durationMs));
            }
            h.btnExport.setVisibility(t.hasFile() ? View.VISIBLE : View.GONE);
        }

        h.cardRoot.setOnClickListener(v -> listener.onCardClick(t));
        h.cardRoot.setOnLongClickListener(v -> {
            listener.onCardLongPress(t);
            return true;
        });
        h.playBtn.setOnClickListener(v -> listener.onPlay(t));
        h.fav.setOnClickListener(v -> {
            v.animate().scaleX(1.4f).scaleY(1.4f).setDuration(110)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(140).start())
                    .start();
            listener.onFavorite(t);
        });
        h.chevron.setOnClickListener(v -> listener.onCardClick(t));
        h.btnRename.setOnClickListener(v -> listener.onRename(t));
        h.btnExport.setOnClickListener(v -> listener.onExport(t));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(t));

        h.scrubber.setOnSeekBarChangeListener(null);
        h.scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && h.tracking) {
                    long pos = t.durationMs * progress / 1000L;
                    h.curTime.setText(Take.formatDuration(pos));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
                h.tracking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
                h.tracking = false;
                long pos = t.durationMs * s.getProgress() / 1000L;
                listener.onSeek(t, pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return takes.size();
    }

    /** Direct progress paint without rebind (called ~4x/sec while playing). */
    public void paintProgress(RecyclerView list, long id, long posMs) {
        RecyclerView.ViewHolder vh = list.findViewHolderForItemId(id);
        if (vh instanceof Vh) {
            ((Vh) vh).applyProgress(posMs);
        }
    }

    public class Vh extends RecyclerView.ViewHolder {
        final View cardRoot;
        final ImageView selectBadge;
        final View playBtn;
        final ImageView play;
        final TextView title;
        final TextView meta;
        final TextView formatBadge;
        final ImageView fav;
        final ImageView chevron;
        final LinearLayout expanded;
        final TextView curTime;
        final TextView totalTime;
        final SeekBar scrubber;
        final View btnRename;
        final View btnExport;
        final View btnDelete;
        boolean tracking = false;

        Vh(@NonNull View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.cardRoot);
            selectBadge = itemView.findViewById(R.id.selectBadge);
            playBtn = itemView.findViewById(R.id.playBtn);
            play = itemView.findViewById(R.id.playIcon);
            title = itemView.findViewById(R.id.takeTitle);
            meta = itemView.findViewById(R.id.takeMeta);
            formatBadge = itemView.findViewById(R.id.formatBadge);
            fav = itemView.findViewById(R.id.favIcon);
            chevron = itemView.findViewById(R.id.chevron);
            expanded = itemView.findViewById(R.id.expanded);
            curTime = itemView.findViewById(R.id.curTime);
            totalTime = itemView.findViewById(R.id.totalTime);
            scrubber = itemView.findViewById(R.id.scrubber);
            btnRename = itemView.findViewById(R.id.btnRename);
            btnExport = itemView.findViewById(R.id.btnExport);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }

        void applyProgress(long posMs) {
            int pos = getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= takes.size()) return;
            Take t = takes.get(pos);
            progressMap.put(t.id, posMs);
            if (tracking) return;
            if (t.durationMs > 0) {
                scrubber.setProgress((int) (posMs * 1000L / t.durationMs));
            }
            curTime.setText(Take.formatDuration(posMs));
        }
    }
}
