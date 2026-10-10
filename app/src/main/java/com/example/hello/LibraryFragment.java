package com.example.hello;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
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
    private MediaPlayer player;
    private long playingId = -1;
    private boolean playPaused = false;
    private boolean simMode = false;
    private long simBase = 0;
    private long simStartedAt = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastBackPress = 0;

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
                filter = f;
                paintChips();
                refresh();
            });
        }

        adapter = new TakesAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        v.findViewById(R.id.searchToggle).setOnClickListener(view -> toggleSearch());
        v.findViewById(R.id.sortBtn).setOnClickListener(this::showSortMenu);

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

        selClose(v);
        selAll.setOnClickListener(view -> toggleSelectAll());
        selDelete.setOnClickListener(view -> deleteSelected());
        selExport.setOnClickListener(view -> exportSelected());

        paintChips();
        refresh();
    }

    private void selClose(View v) {
        v.findViewById(R.id.selClose).setOnClickListener(view -> exitSelection());
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

    // ---- filtering / sorting / search ----

    private void toggleSearch() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            searchBar.setVisibility(View.GONE);
            hideKeyboard();
            if (!query.isEmpty()) searchInput.setText("");
        } else {
            searchBar.setVisibility(View.VISIBLE);
            searchInput.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getView() != null) {
            imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
        }
    }

    private void paintChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean active = chipFilters[i] == filter;
            chips[i].setTextColor(active ? 0xFFF2F4F7 : 0xFF9AA3AF);
            chips[i].setAlpha(active ? 1f : 0.6f);
        }
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        Sort[] values = Sort.values();
        String[] labels = {
                getString(R.string.sort_newest), getString(R.string.sort_oldest),
                getString(R.string.sort_name), getString(R.string.sort_longest),
                getString(R.string.sort_shortest)};
        for (int i = 0; i < values.length; i++) {
            menu.getMenu().add(0, i, i, (values[i] == sort ? "✓  " : "      ") + labels[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            sort = values[item.getItemId()];
            saveSort();
            refresh();
            return true;
        });
        menu.show();
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

        // Prune selection of takes that no longer exist.
        Set<Long> alive = new HashSet<>();
        for (Take t : TakesRepository.all()) alive.add(t.id);
        selection.retainAll(alive);
        if (selectionMode && selection.isEmpty()) {
            selectionMode = false;
        }

        adapter.setTakes(out);
        adapter.setPlayingId(playingId);
        adapter.setPaused(playPaused);
        adapter.setSelection(selection, selectionMode);

        int total = TakesRepository.all().size();
        boolean scoped = !query.isEmpty() || filter != Filter.ALL;
        subtitle.setText(scoped ? getString(R.string.search_count, out.size(), total)
                : getString(R.string.take_count, total));

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
        adapter.setSelection(selection, true);
        adapter.notifySelection(take.id);
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
        adapter.notifyDataSetChanged();
        paintSelectionBar();
    }

    private void paintSelectionBar() {
        normalHeader.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (!selectionMode) return;
        selCount.setText(getString(R.string.selected_count, selection.size()));
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

    // ---- playback (silent: no toasts) ----

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
                player.setOnCompletionListener(mp -> completePlayback(id));
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
        long done = playingId;
        stopPlayback();
        if (getView() != null) adapter.paintProgress(list, done, 0);
    }

    private void stopPlayback() {
        handler.removeCallbacks(progressTick);
        playingId = -1;
        playPaused = false;
        if (adapter != null) {
            adapter.setPaused(false);
            adapter.setPlayingId(-1);
        }
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
            handler.postDelayed(this, 250);
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
            // Paused: move the resume point.
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
        adapter.setExpandedId(adapter.getExpandedId() == take.id ? -1 : take.id);
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
        if (filter == Filter.FAV && !take.favorite) refresh();
        else adapter.refreshItem(take.id);
    }

    @Override
    public void onRename(Take take) {
        FrameLayout pad = new FrameLayout(requireContext());
        int m = (int) (20 * getResources().getDisplayMetrics().density);
        pad.setPadding(m, m / 2, m, 0);
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(take.title);
        input.setSelection(input.getText().length());
        input.setTextColor(0xFFF2F4F7);
        pad.addView(input);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.rename_title)
                .setView(pad)
                .setPositiveButton(R.string.rename_save, (d, w) -> {
                    TakesRepository.rename(take.id, input.getText().toString());
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        input.post(() -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override
    public void onExport(Take take) {
        if (!take.hasFile()) return;
        shareFiles(java.util.Collections.singletonList(take));
    }

    @Override
    public void onDelete(Take take) {
        if (playingId == take.id) stopPlayback();
        Set<Long> ids = new HashSet<>();
        ids.add(take.id);
        TakesRepository.RestoreOp op = TakesRepository.deleteMany(ids);
        selection.remove(take.id);
        if (selectionMode && selection.isEmpty()) selectionMode = false;
        refresh();
        snackUndo(getString(R.string.take_deleted), op);
    }

    private void deleteSelected() {
        if (selection.isEmpty()) return;
        if (selection.contains(playingId)) stopPlayback();
        TakesRepository.RestoreOp op = TakesRepository.deleteMany(selection);
        int n = selection.size();
        selection.clear();
        selectionMode = false;
        refresh();
        snackUndo(getString(R.string.takes_deleted, n), op);
    }

    private void snackUndo(String text, TakesRepository.RestoreOp op) {
        View root = getView();
        if (root == null) return;
        Snackbar.make(root, text, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> refreshAfterUndo(op))
                .show();
    }

    private void refreshAfterUndo(TakesRepository.RestoreOp op) {
        TakesRepository.restore(op);
        refresh();
    }

    // ---- export ----

    private void exportSelected() {
        List<Take> files = new ArrayList<>();
        for (Take t : TakesRepository.all()) {
            if (selection.contains(t.id) && t.hasFile()) files.add(t);
        }
        if (files.isEmpty()) return;
        shareFiles(files);
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
            View root = getView();
            if (root != null) Snackbar.make(root, "Couldn't export", Snackbar.LENGTH_SHORT).show();
        }
    }

    // ---- back handling ----
    // @return true if the press was consumed.

    public boolean onBackPressed() {
        if (selectionMode) {
            exitSelection();
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
