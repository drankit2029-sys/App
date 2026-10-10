package com.example.hello;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryFragment extends Fragment implements TakesAdapter.Listener {

    private enum Filter { ALL, FAV, DAY, WEEK, SHORT, WAV, M4A }
    private enum Sort { NEWEST, OLDEST, NAME, LONGEST, SHORTEST }

    private TakesAdapter adapter;
    private RecyclerView list;
    private TextView subtitle;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptySub;
    private LinearLayout searchBar;
    private EditText searchInput;
    private ImageView searchClear;
    private LinearLayout normalHeader;
    private LinearLayout selectionBar;
    private TextView selCount;
    private TextView selAll;
    private ImageView selExport;
    private ImageView selDelete;
    private TextView[] chips;
    private Filter[] chipFilters;

    private String query = "";
    private Filter filter = Filter.ALL;
    private Sort sort = Sort.NEWEST;
    private List<Take> visible = new ArrayList<>();

    private final Set<Long> selection = new HashSet<>();
    private boolean selectionMode = false;

    // Playback: real file via MediaPlayer, demo takes via simulated clock. No popups.
    /** Progress repaint cadence — 100ms + fine SeekBar steps = smooth motion. */
    private static final long PROGRESS_MS = 100;
    private MediaPlayer player;
    private long playingId = -1;
    private boolean playPaused = false;
    private boolean simMode = false;
    private long simBase = 0;
    private long simStartedAt = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastBackPress = 0;

    private ActivityResultLauncher<String> createDocLauncher;
    private ActivityResultLauncher<String> storagePermLauncher;
    private Take pendingSaveTake;
    private List<Take> pendingSaveMany;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createDocLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("audio/mp4"), uri -> {
                    Take t = pendingSaveTake;
                    pendingSaveTake = null;
                    if (uri != null && t != null && t.hasFile()) {
                        copyToUri(new File(t.filePath), uri);
                    }
                });
        storagePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        if (pendingSaveTake != null) {
                            Take t = pendingSaveTake;
                            pendingSaveTake = null;
                            saveToMusicFolder(singleton(t));
                        } else if (pendingSaveMany != null) {
                            List<Take> many = pendingSaveMany;
                            pendingSaveMany = null;
                            saveToMusicFolder(many);
                        }
                    } else {
                        snack(R.string.need_storage);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        TakesRepository.seedIfNeeded();
        sort = loadSort();

        subtitle = v.findViewById(R.id.librarySubtitle);
        list = v.findViewById(R.id.takesList);
        emptyState = v.findViewById(R.id.emptyState);
        emptyTitle = v.findViewById(R.id.emptyTitle);
        emptySub = v.findViewById(R.id.emptySub);
        searchBar = v.findViewById(R.id.searchBar);
        searchInput = v.findViewById(R.id.searchInput);
        searchClear = v.findViewById(R.id.searchClear);
        normalHeader = v.findViewById(R.id.normalHeader);
        selectionBar = v.findViewById(R.id.selectionBar);
        selCount = v.findViewById(R.id.selCount);
        selAll = v.findViewById(R.id.selAll);
        selExport = v.findViewById(R.id.selExport);
        selDelete = v.findViewById(R.id.selDelete);

        chips = new TextView[]{
                v.findViewById(R.id.chipAll), v.findViewById(R.id.chipFav),
                v.findViewById(R.id.chipDay), v.findViewById(R.id.chipWeek),
                v.findViewById(R.id.chipShort), v.findViewById(R.id.chipWav),
                v.findViewById(R.id.chipM4a)};
        chipFilters = new Filter[]{Filter.ALL, Filter.FAV, Filter.DAY, Filter.WEEK,
                Filter.SHORT, Filter.WAV, Filter.M4A};
        for (int i = 0; i < chips.length; i++) {
            final Filter f = chipFilters[i];
            chips[i].setOnClickListener(view -> {
                if (filter == f) return;
                filter = f;
                paintChipsAnimated();
                view.animate().cancel();
                view.setScaleX(0.9f);
                view.setScaleY(0.9f);
                view.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                refresh();
            });
        }

        adapter = new TakesAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // No fade animation on change events: kills the card flicker on updates.
        if (list.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) list.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        list.setAdapter(adapter);

        v.findViewById(R.id.searchToggle).setOnClickListener(view -> toggleSearch());
        v.findViewById(R.id.sortBtn).setOnClickListener(this::showSortMenu);

        // Press feedback on every tappable header / selection control.
        PressAnim.apply(v.findViewById(R.id.searchToggle));
        PressAnim.apply(v.findViewById(R.id.sortBtn));
        PressAnim.apply(v.findViewById(R.id.selClose));
        PressAnim.apply(selAll);
        PressAnim.apply(selExport);
        PressAnim.apply(selDelete);
        PressAnim.apply(searchClear);
        for (TextView chip : chips) PressAnim.apply(chip);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString().trim().toLowerCase();
                searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchInput.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                searchInput.clearFocus();
                return true;
            }
            return false;
        });
        searchClear.setOnClickListener(view -> searchInput.setText(""));

        v.findViewById(R.id.selClose).setOnClickListener(view -> exitSelection());
        selAll.setOnClickListener(view -> toggleSelectAll());
        selDelete.setOnClickListener(view -> deleteSelected());
        selExport.setOnClickListener(view -> showMassExportOptions());

        paintChips();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseForBackground();
    }

    @Override
    public void onDestroyView() {
        stopPlayback();
        super.onDestroyView();
    }

    // ---- search ----

    private void toggleSearch() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
        } else {
            searchBar.setVisibility(View.VISIBLE);
            searchBar.setAlpha(0f);
            searchBar.setTranslationY(-18f);
            searchBar.animate().cancel();
            searchBar.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
            searchInput.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeSearch() {
        hideKeyboard();
        try {
            searchInput.clearFocus();
        } catch (Exception ignored) {}
        if (searchBar.getVisibility() != View.VISIBLE) return;
        searchBar.animate().cancel();
        searchBar.animate()
                .alpha(0f).translationY(-14f)
                .setDuration(190)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    searchBar.setVisibility(View.GONE);
                    searchBar.setAlpha(1f);
                    searchBar.setTranslationY(0f);
                })
                .start();
        if (!query.isEmpty()) searchInput.setText("");
    }

    private boolean isSearchOpen() {
        return searchBar != null && searchBar.getVisibility() == View.VISIBLE;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getView() != null) {
            imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
        }
    }

    // ---- filtering / sorting ----

    private void paintChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean active = chipFilters[i] == filter;
            chips[i].setBackgroundResource(active
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chips[i].setTextColor(active ? 0xFFFFFFFF : 0xFF9AA3AF);
            chips[i].setAlpha(1f);
        }
    }

    private void paintChipsAnimated() {
        for (int i = 0; i < chips.length; i++) {
            boolean active = chipFilters[i] == filter;
            chips[i].setBackgroundResource(active
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chips[i].setTextColor(active ? 0xFFFFFFFF : 0xFF9AA3AF);
            if (active) {
                chips[i].animate().cancel();
                chips[i].setScaleX(0.92f);
                chips[i].setScaleY(0.92f);
                chips[i].animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(
                                new android.view.animation.OvershootInterpolator(2.2f))
                        .start();
            }
        }
    }

    private void showSortMenu(View anchor) {
        // Press bounce on the anchor for tactile feedback.
        anchor.animate().cancel();
        anchor.setScaleX(0.85f);
        anchor.setScaleY(0.85f);
        anchor.animate().scaleX(1f).scaleY(1f).setDuration(180).start();

        Sort[] values = Sort.values();
        List<OptionsSheet.Item> items = new ArrayList<>();
        for (Sort s : values) {
            final Sort option = s;
            items.add(new OptionsSheet.Item(0, sortLabel(option), false, option == sort,
                    () -> {
                        sort = option;
                        saveSort();
                        refresh();
                        OptionsSheet.showResult(requireContext(), true,
                                getString(R.string.sort_by),
                                sortLabel(option));
                    }));
        }
        int total = TakesRepository.all().size();
        OptionsSheet.showOptions(requireContext(),
                getString(R.string.sort_by),
                getString(R.string.sort_subtitle, total),
                items);
    }

    private String sortLabel(Sort s) {
        switch (s) {
            case OLDEST: return getString(R.string.sort_oldest);
            case NAME: return getString(R.string.sort_name);
            case LONGEST: return getString(R.string.sort_longest);
            case SHORTEST: return getString(R.string.sort_shortest);
            default: return getString(R.string.sort_newest);
        }
    }

    private Sort loadSort() {
        try {
            int ord = requireContext().getSharedPreferences("takeone", Context.MODE_PRIVATE)
                    .getInt("sort", 0);
            Sort[] all = Sort.values();
            if (ord >= 0 && ord < all.length) return all[ord];
        } catch (Exception ignored) {}
        return Sort.NEWEST;
    }

    private void saveSort() {
        try {
            SharedPreferences p = requireContext()
                    .getSharedPreferences("takeone", Context.MODE_PRIVATE);
            p.edit().putInt("sort", sort.ordinal()).apply();
        } catch (Exception ignored) {}
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        List<Take> out = new ArrayList<>();
        for (Take t : TakesRepository.all()) {
            switch (filter) {
                case FAV: if (!t.favorite) continue; break;
                case DAY: if (now - t.timestamp > 24L * 3600_000L) continue; break;
                case WEEK: if (now - t.timestamp > 7L * 24 * 3600_000L) continue; break;
                case SHORT: if (t.durationMs >= 5L * 60_000L) continue; break;
                case WAV: if (!"WAV".equalsIgnoreCase(t.format)) continue; break;
                case M4A: if (!"M4A".equalsIgnoreCase(t.format)) continue; break;
                default: break;
            }
            if (!query.isEmpty() && !t.title.toLowerCase().contains(query)) continue;
            out.add(t);
        }
        Comparator<Take> cmp;
        switch (sort) {
            case OLDEST: cmp = (a, b) -> Long.compare(a.timestamp, b.timestamp); break;
            case NAME: cmp = (a, b) -> a.title.compareToIgnoreCase(b.title); break;
            case LONGEST: cmp = (a, b) -> Long.compare(b.durationMs, a.durationMs); break;
            case SHORTEST: cmp = (a, b) -> Long.compare(a.durationMs, b.durationMs); break;
            default: cmp = (a, b) -> Long.compare(b.timestamp, a.timestamp); break;
        }
        Collections.sort(out, cmp);
        visible = out;

        Set<Long> alive = new HashSet<>();
        for (Take t : TakesRepository.all()) alive.add(t.id);
        selection.retainAll(alive);
        if (selectionMode && selection.isEmpty()) selectionMode = false;

        adapter.setTakes(out);
        adapter.setPlayingId(playingId);
        adapter.setPaused(playPaused);
        adapter.setSelection(selection, selectionMode);

        int total = TakesRepository.all().size();
        boolean scoped = !query.isEmpty() || filter != Filter.ALL;
        String base = scoped ? getString(R.string.search_count, out.size(), total)
                : getString(R.string.take_count, total);
        subtitle.setText(base + "  •  " + sortLabel(sort));

        boolean empty = out.isEmpty();
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            if (total == 0) {
                emptyTitle.setText(R.string.library_empty_title);
                emptySub.setText(R.string.library_empty_sub);
            } else {
                emptyTitle.setText(R.string.no_matches);
                emptySub.setText(query.isEmpty()
                        ? getString(R.string.no_matches_sub, "")
                        : getString(R.string.no_matches_sub, query));
            }
        }
        paintSelectionBar();
    }

    // ---- selection ----

    private void enterSelection(Take take) {
        selectionMode = true;
        selection.add(take.id);
        adapter.collapseAll();
        adapter.setSelection(selection, true);
        paintSelectionBar();
    }

    private void exitSelection() {
        selectionMode = false;
        selection.clear();
        adapter.setSelection(selection, false);
        paintSelectionBar();
    }

    private void toggleSelection(Take take) {
        if (selection.contains(take.id)) selection.remove(take.id);
        else selection.add(take.id);
        if (selection.isEmpty()) {
            exitSelection();
            return;
        }
        // setSelection rebinds on content change, so the tapped card (and only
        // the tapped card) animates — badges, border and shrink together.
        adapter.setSelection(selection, true);
        paintSelectionBar();
    }

    private void toggleSelectAll() {
        boolean allSel = selection.size() == visible.size() && !visible.isEmpty();
        selection.clear();
        if (!allSel) {
            for (Take t : visible) selection.add(t.id);
        }
        if (selection.isEmpty()) {
            exitSelection();
            return;
        }
        adapter.setSelection(selection, true);
        paintSelectionBar();
    }

    private void paintSelectionBar() {
        boolean showSel = selectionMode;
        if (showSel && selectionBar.getVisibility() != View.VISIBLE) {
            normalHeader.setVisibility(View.GONE);
            selectionBar.setVisibility(View.VISIBLE);
            selectionBar.setAlpha(0f);
            selectionBar.setTranslationY(-12f);
            selectionBar.animate().cancel();
            selectionBar.animate().alpha(1f).translationY(0f)
                    .setDuration(240)
                    .setInterpolator(
                            new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } else if (!showSel && normalHeader.getVisibility() != View.VISIBLE) {
            selectionBar.setVisibility(View.GONE);
            normalHeader.setVisibility(View.VISIBLE);
            normalHeader.setAlpha(0f);
            normalHeader.setTranslationY(-12f);
            normalHeader.animate().cancel();
            normalHeader.animate().alpha(1f).translationY(0f)
                    .setDuration(240)
                    .setInterpolator(
                            new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } else {
            normalHeader.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        }
        if (!selectionMode) return;
        selCount.setText(getString(R.string.selected_count, selection.size()));
        // Pop the count on every change so selection feels alive.
        selCount.setScaleX(0.85f);
        selCount.setScaleY(0.85f);
        selCount.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        boolean hasFile = false;
        for (Take t : TakesRepository.all()) {
            if (selection.contains(t.id) && t.hasFile()) {
                hasFile = true;
                break;
            }
        }
        selExport.setAlpha(hasFile ? 1f : 0.35f);
        selExport.setClickable(hasFile);
        selDelete.setAlpha(selection.isEmpty() ? 0.35f : 1f);
    }

    // ---- playback (silent: no popups) ----

    @Override
    public void onPlay(Take take) {
        if (selectionMode) {
            toggleSelection(take);
            return;
        }
        if (playingId == take.id) {
            if (playPaused) resumePlayback();
            else pausePlayback();
            return;
        }
        startPlayback(take, 0);
    }

    private void startPlayback(Take take, long startPos) {
        stopPlayback();
        if (take.hasFile()) {
            try {
                player = new MediaPlayer();
                player.setDataSource(take.filePath);
                player.prepare();
                if (startPos > 0) {
                    try {
                        player.seekTo((int) Math.min(startPos, player.getDuration()));
                    } catch (Exception ignored) {}
                }
                final long id = take.id;
                player.setOnCompletionListener(mp -> {
                    // Loop while the user is still scrubbing; otherwise finish.
                    if (adapter.isTracking(id, list)) {
                        try {
                            mp.seekTo(0);
                            mp.start();
                        } catch (Exception e) {
                            completePlayback(id);
                        }
                    } else {
                        completePlayback(id);
                    }
                });
                player.start();
                simMode = false;
            } catch (Exception e) {
                stopPlayback();
                return;
            }
        } else {
            simMode = true;
            simBase = Math.min(startPos, take.durationMs);
            simStartedAt = SystemClock.elapsedRealtime();
        }
        playingId = take.id;
        playPaused = false;
        // Drop any stale near-end position so replay starts exactly at startPos
        // instead of snapping to where the last playthrough left off.
        adapter.setProgress(list, take.id, startPos);
        adapter.setExpandedId(take.id);
        adapter.setPlayingId(playingId);
        adapter.setPaused(false);
        handler.post(progressTick);
    }

    private void pausePlayback() {
        if (playingId == -1 || playPaused) return;
        if (!simMode && player != null) {
            try {
                player.pause();
            } catch (Exception ignored) {}
        } else {
            simBase = simPosition();
        }
        playPaused = true;
        adapter.setPaused(true);
        handler.removeCallbacks(progressTick);
    }

    private void resumePlayback() {
        if (playingId == -1 || !playPaused) return;
        if (!simMode && player != null) {
            try {
                player.start();
            } catch (Exception ignored) {}
        } else {
            simStartedAt = SystemClock.elapsedRealtime();
        }
        playPaused = false;
        adapter.setPaused(false);
        handler.post(progressTick);
    }

    private void pauseForBackground() {
        if (playingId != -1 && !playPaused) pausePlayback();
    }

    private void completePlayback(long id) {
        if (playingId != id) return;
        // Land the scrubber exactly on the end first so progress visibly
        // reaches 100% instead of stopping one tick short.
        Take t = TakesRepository.find(id);
        if (t != null) adapter.paintProgress(list, id, t.durationMs);
        stopPlayback(); // single UI update; rebind already restores scrubber + icon
    }

    private void stopPlayback() {
        handler.removeCallbacks(progressTick);
        playingId = -1;
        playPaused = false;
        if (adapter != null) adapter.clearPlaying();
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {}
            try {
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    private long simPosition() {
        Take t = TakesRepository.find(playingId);
        long pos = simBase + (SystemClock.elapsedRealtime() - simStartedAt);
        return t == null ? pos : Math.min(pos, t.durationMs);
    }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (playingId == -1 || playPaused || getView() == null) return;
            Take t = TakesRepository.find(playingId);
            if (t == null) {
                stopPlayback();
                return;
            }
            long pos;
            if (simMode) {
                pos = simPosition();
                if (pos >= t.durationMs) {
                    // Loop while the user is still scrubbing; otherwise finish.
                    if (adapter.isTracking(playingId, list)) {
                        simBase = 0;
                        simStartedAt = SystemClock.elapsedRealtime();
                        adapter.paintProgress(list, playingId, 0);
                        handler.postDelayed(this, PROGRESS_MS);
                        return;
                    }
                    completePlayback(playingId);
                    return;
                }
            } else if (player != null) {
                try {
                    pos = player.getCurrentPosition();
                } catch (Exception e) {
                    return;
                }
            } else {
                return;
            }
            adapter.paintProgress(list, playingId, pos);
            handler.postDelayed(this, PROGRESS_MS);
        }
    };

    @Override
    public void onSeek(Take take, long posMs) {
        if (playingId == take.id && !playPaused) {
            if (!simMode && player != null) {
                try {
                    player.seekTo((int) posMs);
                } catch (Exception ignored) {}
            } else if (simMode) {
                simBase = Math.min(posMs, take.durationMs);
                simStartedAt = SystemClock.elapsedRealtime();
            }
        } else if (playingId == take.id) {
            if (!simMode && player != null) {
                try {
                    player.seekTo((int) posMs);
                } catch (Exception ignored) {}
            } else {
                simBase = Math.min(posMs, take.durationMs);
            }
            adapter.paintProgress(list, take.id, posMs);
        } else {
            startPlayback(take, posMs);
        }
    }

    // ---- card actions ----

    @Override
    public void onCardClick(Take take) {
        if (selectionMode) {
            toggleSelection(take);
            return;
        }
        // In-place expand, no list rebind -> no flicker.
        adapter.toggleExpandedDirect(list, take.id);
    }

    @Override
    public void onCardLongPress(Take take) {
        if (!selectionMode) enterSelection(take);
    }

    @Override
    public void onFavorite(Take take) {
        if (selectionMode) {
            toggleSelection(take);
            return;
        }
        TakesRepository.toggleFavorite(take.id);
        if (filter == Filter.FAV && !take.favorite) {
            refresh(); // item leaves the Favorites filter
        } else {
            // Paint only the heart, no rebind -> no flicker, pop animation survives.
            adapter.paintFavorite(list, take.id, take.favorite);
        }
    }

    @Override
    public void onRename(Take take) {
        OptionsSheet.showInput(requireContext(), getString(R.string.rename_title),
                take.title, getString(R.string.rename_save), text -> {
                    try {
                        TakesRepository.rename(take.id, text);
                        refresh();
                        Take updated = TakesRepository.find(take.id);
                        String name = updated != null ? updated.title : text;
                        OptionsSheet.showResult(requireContext(), true,
                                getString(R.string.rename_success_title),
                                getString(R.string.rename_success_msg, name));
                    } catch (Exception e) {
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.rename_failed_title),
                                getString(R.string.rename_failed_msg));
                    }
                });
    }

    @Override
    public void onExport(Take take) {
        if (selectionMode) {
            toggleSelection(take);
            return;
        }
        if (!take.hasFile()) return;
        showSingleExportOptions(take);
    }

    /** Singular export: one menu offering Share or Save to device. */
    private void showSingleExportOptions(Take take) {
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_share,
                getString(R.string.export_share), () -> shareFiles(singleton(take))));
        items.add(OptionsSheet.Item.of(R.drawable.ic_folder,
                getString(R.string.export_save), () -> saveSingleTake(take)));
        OptionsSheet.showOptions(requireContext(), getString(R.string.action_export),
                take.title, items);
    }

    private void saveSingleTake(Take take) {
        if (!take.hasFile()) return;
        pendingSaveTake = take;
        createDocLauncher.launch(safeName(new File(take.filePath).getName()));
    }

    @Override
    public void onDelete(Take take) {
        OptionsSheet.showConfirm(requireContext(),
                getString(R.string.delete_one_title),
                getString(R.string.delete_one_msg, take.title),
                getString(R.string.delete_confirm),
                R.drawable.ic_trash, true,
                () -> performDeleteSingle(take));
    }

    private void performDeleteSingle(Take take) {
        try {
            if (playingId == take.id) stopPlayback();
            Set<Long> ids = new HashSet<>();
            ids.add(take.id);
            String name = take.title;
            TakesRepository.RestoreOp op = TakesRepository.deleteMany(ids);
            selection.remove(take.id);
            if (selectionMode && selection.isEmpty()) selectionMode = false;
            refresh();
            snackUndo(getString(R.string.take_deleted), op);
            OptionsSheet.showResult(requireContext(), true,
                    getString(R.string.delete_success_title),
                    getString(R.string.delete_success_one, name));
        } catch (Exception e) {
            OptionsSheet.showResult(requireContext(), false,
                    getString(R.string.delete_failed_title),
                    getString(R.string.delete_failed_msg));
        }
    }

    private void deleteSelected() {
        if (selection.isEmpty()) return;
        final int n = selection.size();
        OptionsSheet.showConfirm(requireContext(),
                getString(R.string.delete_many_title, n),
                getString(R.string.delete_many_msg),
                getString(R.string.delete_confirm),
                R.drawable.ic_trash, true,
                () -> {
                    try {
                        if (selection.contains(playingId)) stopPlayback();
                        TakesRepository.RestoreOp op =
                                TakesRepository.deleteMany(selection);
                        selection.clear();
                        selectionMode = false;
                        refresh();
                        snackUndo(getString(R.string.takes_deleted, n), op);
                        OptionsSheet.showResult(requireContext(), true,
                                getString(R.string.delete_success_title),
                                getString(R.string.delete_success_many, n));
                    } catch (Exception e) {
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.delete_failed_title),
                                getString(R.string.delete_failed_msg));
                    }
                });
    }

    private void snackUndo(String text, TakesRepository.RestoreOp op) {
        View root = getView();
        if (root == null) return;
        Snackbar sb = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> {
                    TakesRepository.restore(op);
                    refresh();
                });
        styleSnack(sb);
        sb.show();
    }

    private void snack(int resId) {
        View root = getView();
        if (root == null) return;
        Snackbar sb = Snackbar.make(root, resId, Snackbar.LENGTH_SHORT);
        styleSnack(sb);
        sb.show();
    }

    private void snackText(String text) {
        View root = getView();
        if (root == null) return;
        Snackbar sb = Snackbar.make(root, text, Snackbar.LENGTH_SHORT);
        styleSnack(sb);
        sb.show();
    }

    private void styleSnack(Snackbar sb) {
        try {
            View sbView = sb.getView();
            sbView.setBackgroundResource(R.drawable.bg_btn_ghost);
            sbView.setAlpha(0f);
            sbView.setTranslationY(24f);
            sbView.animate().alpha(1f).translationY(0f)
                    .setDuration(260)
                    .setInterpolator(
                            new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
            sb.setActionTextColor(0xFFFF3B30);
        } catch (Exception ignored) {}
    }

    // ---- export: share + save to device ----

    private List<Take> singleton(Take t) {
        List<Take> l = new ArrayList<>();
        l.add(t);
        return l;
    }

    private void showMassExportOptions() {
        List<Take> files = new ArrayList<>();
        for (Take t : TakesRepository.all()) {
            if (selection.contains(t.id) && t.hasFile()) files.add(t);
        }
        if (files.isEmpty()) return;
        final int n = files.size();
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_share,
                getString(R.string.export_share_multi, n), () -> shareFiles(files)));
        items.add(OptionsSheet.Item.of(R.drawable.ic_folder,
                getString(R.string.export_save_multi, n),
                () -> saveToMusicFolderWithPerm(files)));
        OptionsSheet.showOptions(requireContext(), getString(R.string.action_export),
                getString(R.string.export_subtitle, n), items);
    }

    private void shareFiles(List<Take> takes) {
        try {
            String authority = requireContext().getPackageName() + ".fileprovider";
            ArrayList<Uri> uris = new ArrayList<>();
            for (Take t : takes) {
                uris.add(FileProvider.getUriForFile(requireContext(), authority,
                        new File(t.filePath)));
            }
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType("audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.action_export)));
        } catch (Exception e) {
            OptionsSheet.showResult(requireContext(), false,
                    getString(R.string.action_export),
                    getString(R.string.couldnt_export));
        }
    }

    /** Save into the permanent, user-visible Music/TakeOne folder. */
    private void saveToMusicFolderWithPerm(List<Take> takes) {
        if (takes.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSaveTake = null;
            pendingSaveMany = new ArrayList<>(takes);
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveToMusicFolder(takes);
    }

    private void saveToMusicFolder(List<Take> takes) {
        final Context app = requireContext().getApplicationContext();
        final List<Take> copy = new ArrayList<>(takes);
        new Thread(() -> {
            final List<String> locations = new ArrayList<>();
            for (Take t : copy) {
                String where = saveOneToMusic(app, t);
                if (where != null) locations.add(where);
            }
            final int n = locations.size();
            final int total = copy.size();
            handler.post(() -> {
                if (getView() == null || !isAdded()) return;
                if (n == 0) {
                    OptionsSheet.showResult(requireContext(), false,
                            getString(R.string.couldnt_save_title),
                            getString(R.string.couldnt_save));
                    return;
                }
                // Report the real destination the file landed in, not a
                // hardcoded folder name.
                if (n == 1) {
                    OptionsSheet.showResult(requireContext(), true,
                            getString(R.string.take_saved),
                            getString(R.string.saved_to_location, locations.get(0)));
                } else {
                    OptionsSheet.showResult(requireContext(), true,
                            getString(R.string.take_saved),
                            getString(R.string.saved_n_to_location, n, locations.get(0)));
                }
            });
        }).start();
    }

    /** @return the actual on-device location saved to, or null on failure. */
    private String saveOneToMusic(Context ctx, Take t) {
        File src = new File(t.filePath);
        if (!src.exists()) return null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Audio.Media.DISPLAY_NAME, src.getName());
                cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                cv.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TakeOne");
                cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return null;
                    copyStream(in, out);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, done, null, null);
                return "Music/TakeOne/" + src.getName();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), "TakeOne");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File dst = unique(new File(dir, src.getName()));
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    copyStream(in, out);
                }
                MediaScannerConnection.scanFile(ctx,
                        new String[]{dst.getAbsolutePath()}, null, null);
                return "Music/TakeOne/" + dst.getName();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private File unique(File dst) {
        if (!dst.exists()) return dst;
        String name = dst.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 2;
        while (true) {
            File c = new File(dst.getParent(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
            i++;
        }
    }

    /** Copy the take into a user-picked document (Save to device). */
    private void copyToUri(File src, Uri dst) {
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean ok;
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = app.getContentResolver().openOutputStream(dst)) {
                if (out == null) throw new IllegalStateException("no stream");
                copyStream(in, out);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            handler.post(() -> {
                if (getView() == null || !isAdded()) return;
                if (done) {
                    // Show where the user actually chose to put it.
                    OptionsSheet.showResult(requireContext(), true,
                            getString(R.string.take_saved),
                            getString(R.string.saved_to_location,
                                    resolveSavedLocation(dst)));
                } else {
                    OptionsSheet.showResult(requireContext(), false,
                            getString(R.string.couldnt_save_title),
                            getString(R.string.couldnt_save));
                }
            });
        }).start();
    }

    /** Best-effort human-readable location for a saved document URI. */
    private String resolveSavedLocation(Uri uri) {
        if (uri == null) return "";
        String name = null;
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                try (Cursor c = requireContext().getContentResolver().query(
                        uri, new String[]{OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (col >= 0) name = c.getString(col);
                    }
                }
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            try {
                name = uri.getLastPathSegment();
            } catch (Exception ignored) {}
        }
        if (name == null) name = "";
        // Strip the primary:/document/ prefix some providers hand back.
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        return name;
    }

    private void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ---- back handling ----
    // Order: selection -> open search -> double-press exit. True = consumed.

    public boolean onBackPressed() {
        if (selectionMode) {
            exitSelection();
            return true;
        }
        if (isSearchOpen()) {
            closeSearch();
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress < 2000) {
            return false; // let the activity exit
        }
        lastBackPress = now;
        View root = getView();
        if (root != null) {
            Snackbar.make(root, R.string.press_back_exit, Snackbar.LENGTH_SHORT).show();
        }
        return true;
    }
}
