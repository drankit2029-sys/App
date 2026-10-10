package com.example.hello;

import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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

    /** Selected cards shrink so multi-select reads at a glance. */
    private static final float SEL_SCALE = 0.92f;
    /** SeekBar resolution — high enough that progress looks continuous. */
    private static final int SCRUB_MAX = 10000;

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

    /** Single-notify reset of the playing state (avoids double-rebind flicker). */
    public void clearPlaying() {
        long old = playingId;
        playingId = -1;
        paused = false;
        notifyForId(old);
    }

    public void refreshItem(long id) {
        notifyForId(id);
    }

    /** Update only the heart artwork, no rebind (no flicker on favorite taps). */
    public void paintFavorite(RecyclerView rv, long id, boolean fav) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForItemId(id);
        if (vh instanceof Vh) {
            Vh h = (Vh) vh;
            h.fav.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            h.fav.setAlpha(fav ? 1f : 0.55f);
        }
    }

    /** Toggle the expanded section with a smooth height + fade animation. */
    public void toggleExpandedDirect(RecyclerView rv, long id) {
        boolean expand = expandedId != id;
        // Smoothly collapse any previously open card.
        if (expandedId != -1 && expandedId != id) {
            RecyclerView.ViewHolder oldVh = rv.findViewHolderForItemId(expandedId);
            if (oldVh instanceof Vh) {
                Vh old = (Vh) oldVh;
                animateCollapse(old.expanded, old.chevron);
            }
        }
        expandedId = expand ? id : -1;
        RecyclerView.ViewHolder vh = rv.findViewHolderForItemId(id);
        if (vh instanceof Vh) {
            Vh h = (Vh) vh;
            // Drop any stuck pressed/focus glow on the scrubber when it (re)appears.
            h.scrubber.setPressed(false);
            h.scrubber.clearFocus();
            h.scrubber.jumpDrawablesToCurrentState();
            if (expand) {
                Take t = findTake(id);
                if (t != null) {
                    h.totalTime.setText(Take.formatDuration(t.durationMs));
                    if (t.id != playingId) {
                        h.curTime.setText(Take.formatDuration(0));
                        if (!h.tracking) h.scrubber.setProgress(0);
                    }
                    h.btnExport.setVisibility(t.hasFile() ? View.VISIBLE : View.GONE);
                }
                animateExpand(h.expanded, h.chevron);
            } else {
                animateCollapse(h.expanded, h.chevron);
            }
        }
    }

    private void animateExpand(final View expanded, final View chevron) {
        chevron.animate().cancel();
        chevron.animate().rotation(180f).setDuration(250)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        // Cancel any in-flight height animation.
        Object tag = expanded.getTag();
        if (tag instanceof ValueAnimator) ((ValueAnimator) tag).cancel();
        expanded.setVisibility(View.VISIBLE);
        expanded.measure(
                View.MeasureSpec.makeMeasureSpec(
                        ((View) expanded.getParent()).getWidth(),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int targetH = expanded.getMeasuredHeight();
        expanded.getLayoutParams().height = 0;
        expanded.setAlpha(0f);
        expanded.requestLayout();
        ValueAnimator anim = ValueAnimator.ofInt(0, targetH);
        anim.setDuration(300);
        anim.setInterpolator(new DecelerateInterpolator(1.3f));
        anim.addUpdateListener(a -> {
            int val = (int) a.getAnimatedValue();
            expanded.getLayoutParams().height = val;
            expanded.setAlpha(Math.min(1f, val / (float) Math.max(1, targetH)));
            expanded.requestLayout();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                expanded.getLayoutParams().height =
                        ViewGroup.LayoutParams.WRAP_CONTENT;
                expanded.setAlpha(1f);
                expanded.requestLayout();
                expanded.setTag(null);
            }
        });
        expanded.setTag(anim);
        anim.start();
    }

    private void animateCollapse(final View expanded, final View chevron) {
        chevron.animate().cancel();
        chevron.animate().rotation(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        if (expanded.getVisibility() != View.VISIBLE) return;
        Object tag = expanded.getTag();
        if (tag instanceof ValueAnimator) ((ValueAnimator) tag).cancel();
        final int startH = expanded.getHeight() > 0
                ? expanded.getHeight() : expanded.getMeasuredHeight();
        if (startH <= 0) {
            expanded.setVisibility(View.GONE);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofInt(startH, 0);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            int val = (int) a.getAnimatedValue();
            expanded.getLayoutParams().height = val;
            expanded.setAlpha(val / (float) Math.max(1, startH));
            expanded.requestLayout();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                expanded.setVisibility(View.GONE);
                expanded.getLayoutParams().height =
                        ViewGroup.LayoutParams.WRAP_CONTENT;
                expanded.setAlpha(1f);
                expanded.requestLayout();
                expanded.setTag(null);
            }
        });
        expanded.setTag(anim);
        anim.start();
    }

    /** True while the user is actively dragging this take's scrubber. */
    public boolean isTracking(long id, RecyclerView rv) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForItemId(id);
        return vh instanceof Vh && ((Vh) vh).tracking;
    }

    private Take findTake(long id) {
        for (Take t : takes) {
            if (t.id == id) return t;
        }
        return null;
    }

    public void setSelection(Set<Long> selected, boolean mode) {
        Set<Long> copy = new HashSet<>(selected);
        boolean modeChanged = this.selectionMode != mode;
        boolean contentChanged = !this.selected.equals(copy);
        this.selected = copy;
        this.selectionMode = mode;
        // Any change — mode flip, single toggle, or select-all — rebinds every
        // visible row, so badges, backgrounds and the shrink always converge
        // on the tapped state in one pass. No walk-then-rebind double driver,
        // so effects can never land a tap late or on the wrong card.
        if (modeChanged || contentChanged) {
            notifyDataSetChanged();
        }
    }

    private void applySelectionScale(Vh h, boolean isSel) {
        float target = isSel ? SEL_SCALE : 1f;
        View card = h.cardRoot;
        // Smooth shrink/grow so selection visibly animates. Any in-flight
        // animation is cancelled first, then we glide to the target on a
        // decelerate curve. Rebinds converge on the same target, so recycled
        // holders can never carry a stale size.
        Float current = (Float) card.getTag(R.id.cardRoot);
        if (current != null && Math.abs(current - target) < 0.001f) {
            card.setScaleX(target);
            card.setScaleY(target);
            return;
        }
        card.setTag(R.id.cardRoot, target);
        card.animate().cancel();
        card.animate()
                .scaleX(target).scaleY(target)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
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

    private void pressBounce(View v) {
        v.animate().cancel();
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                .start();
    }

    @NonNull
    @Override
    public Vh onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_take, parent, false);
        Vh holder = new Vh(v);
        // Press feedback on everything tappable (scrubber excluded — it drags).
        // Icons get the softer variant so they give without flashing; the card
        // uses a ripple foreground so it never fights the selection scale.
        PressAnim.applySoft(holder.fav, 0.9f);
        PressAnim.applySoft(holder.chevron, 0.88f);
        PressAnim.applySoft(holder.playBtn, 0.92f);
        PressAnim.apply(holder.btnRename);
        PressAnim.apply(holder.btnExport);
        PressAnim.apply(holder.btnDelete);
        return holder;
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

        // Deterministic selection shrink. Every bind converges the card to the
        // exact scale its selection state demands, so recycled holders can
        // never carry a stale size.
        applySelectionScale(h, isSel);

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
        // Reset any mid-animation height leftovers from recycled holders.
        Object pending = h.expanded.getTag();
        if (pending instanceof ValueAnimator) ((ValueAnimator) pending).cancel();
        h.expanded.setTag(null);
        h.expanded.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        h.expanded.setAlpha(1f);
        h.expanded.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expanded) {
            h.totalTime.setText(Take.formatDuration(t.durationMs));
            long shown = playing ? progressMap.getOrDefault(t.id, 0L) : 0L;
            if (!h.tracking) {
                h.curTime.setText(Take.formatDuration(shown));
                h.scrubber.setProgress(t.durationMs <= 0 ? 0
                        : (int) (shown * SCRUB_MAX / t.durationMs));
            }
            h.btnExport.setVisibility(t.hasFile() ? View.VISIBLE : View.GONE);
        }

        h.cardRoot.setOnClickListener(v -> listener.onCardClick(t));
        h.cardRoot.setOnLongClickListener(v -> {
            listener.onCardLongPress(t);
            return true;
        });
        h.playBtn.setOnClickListener(v -> {
            v.animate().cancel();
            v.setScaleX(0.9f);
            v.setScaleY(0.9f);
            v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                    .start();
            listener.onPlay(t);
        });
        h.fav.setOnClickListener(v -> {
            // Soft nudge on a falloff curve — no hard overshoot, no flash.
            PressAnim.nudge(v, 1.14f);
            listener.onFavorite(t);
        });
        h.chevron.setOnClickListener(v -> listener.onCardClick(t));
        h.btnRename.setOnClickListener(v -> {
            pressBounce(v);
            listener.onRename(t);
        });
        h.btnExport.setOnClickListener(v -> {
            pressBounce(v);
            listener.onExport(t);
        });
        h.btnDelete.setOnClickListener(v -> {
            pressBounce(v);
            listener.onDelete(t);
        });

        h.scrubber.setOnSeekBarChangeListener(null);
        h.scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && h.tracking) {
                    long pos = t.durationMs * progress / SCRUB_MAX;
                    h.curTime.setText(Take.formatDuration(pos));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
                h.tracking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
                h.tracking = false;
                long pos = t.durationMs * s.getProgress() / SCRUB_MAX;
                listener.onSeek(t, pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return takes.size();
    }

    /** Direct progress paint without rebind (called ~10x/sec while playing). */
    public void paintProgress(RecyclerView list, long id, long posMs) {
        RecyclerView.ViewHolder vh = list.findViewHolderForItemId(id);
        if (vh instanceof Vh) {
            ((Vh) vh).applyProgress(posMs);
        }
    }

    /**
     * Set the stored position for a take (e.g. 0 when (re)starting playback)
     * so a rebind never snaps to a stale near-end value. Also paints it live
     * when the holder is visible and the user isn't dragging.
     */
    public void setProgress(RecyclerView list, long id, long posMs) {
        progressMap.put(id, posMs);
        if (list == null) return;
        RecyclerView.ViewHolder vh = list.findViewHolderForItemId(id);
        if (vh instanceof Vh && !((Vh) vh).tracking) {
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
                // Clamp so completion always lands exactly on the end.
                int p = (int) (posMs * SCRUB_MAX / t.durationMs);
                scrubber.setProgress(Math.min(p, SCRUB_MAX));
            }
            curTime.setText(Take.formatDuration(posMs));
        }
    }
}
