package com.example.hello;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LibraryFragment extends Fragment implements TakesAdapter.Listener {

    /** A4-12: focus holder for library playback; released in stopPlayback(). */
    private android.media.AudioManager playAudioManager;
    private android.media.AudioFocusRequest playFocusRequest;

    private static final int PLAY_FOCUS =
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;

    /**
     * A4-12: library playback never asked for focus, so it could duck or
     * bleed into a live microphone take. Request transient-may-duck focus and
     * refuse while the studio is actually capturing.
     *
     * @return false when playback should not start.
     */
    private boolean acquirePlaybackFocus() {
        try {
            if (RecordingEngine.get().state == RecordingEngine.State.RECORDING) {
                // Playing back through the speaker straight into an open mic.
                return false;
            }
            if (playAudioManager == null) {
                playAudioManager = (android.media.AudioManager)
                        requireContext().getApplicationContext()
                                .getSystemService(Context.AUDIO_SERVICE);
            }
            if (playAudioManager == null) return true;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                playFocusRequest = new android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes
                                .Builder()
                                .setUsage(android.media.AudioAttributes
                                        .USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes
                                        .CONTENT_TYPE_SPEECH)
                                .build())
                        .build();
                return playAudioManager.requestAudioFocus(playFocusRequest)
                        == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            //noinspection deprecation
            return playAudioManager.requestAudioFocus(null, PLAY_FOCUS,
                    android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    private void releasePlaybackFocus() {
        try {
            if (playAudioManager == null) return;
            if (playFocusRequest != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    playAudioManager.abandonAudioFocusRequest(
                            playFocusRequest);
                }
                playFocusRequest = null;
            } else {
                //noinspection deprecation
                playAudioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignored) {}
    }

    /** A4-08: jobs cancelled by the user; their done-broadcast is not a failure. */
    private final Set<String> cancelledJobs =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private enum Filter { ALL, FAV, DAY, WEEK, SHORT, WAV, FLAC, OPUS, AMR, M4A }
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

    // Playback: real file via MediaPlayer, raw preview via AudioTrack,
    // demo takes via simulated clock. No popups.
    /** Progress repaint cadence — 100ms + fine SeekBar steps = smooth motion. */
    private static final long PROGRESS_MS = 100;
    private MediaPlayer player;
    private long playingId = -1;
    private boolean playPaused = false;
    private boolean simMode = false;
    private boolean rawMode = false;
    private RawFloatPlayer rawPlayer;
    private long simBase = 0;
    private long simStartedAt = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver convReceiver;

    private long lastBackPress = 0;

    private ActivityResultLauncher<Uri> openTreeLauncher;
    private List<Take> pendingExportTakes;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    List<Take> takes = pendingExportTakes;
                    pendingExportTakes = null;
                    if (uri == null || takes == null || takes.isEmpty()) return;
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    exportTakesToFolder(takes, uri);
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
                v.findViewById(R.id.chipFlac), v.findViewById(R.id.chipOpus),
                v.findViewById(R.id.chipAmr), v.findViewById(R.id.chipM4a)};
        chipFilters = new Filter[]{Filter.ALL, Filter.FAV, Filter.DAY, Filter.WEEK,
                Filter.SHORT, Filter.WAV, Filter.FLAC, Filter.OPUS, Filter.AMR,
                Filter.M4A};
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
        // No item animations at all. The animator cannot survive a stable-id
        // sign flip (converting card -> finished take): it keeps animating a
        // holder that is never rebound, and change animations on top of
        // notifyDataSetChanged() also flickered cards on ordinary updates.
        list.setItemAnimator(null);
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

        // Registered LAST, on purpose. TakesRepository.onReady runs its
        // callback SYNCHRONOUSLY when the library is already loaded, and this
        // is reached on every return from the studio (repo is loaded) and on
        // any cold start where the load finished first. Registering before
        // the adapter exists meant refresh() ran with a null adapter and
        // NPE'd the whole process on the main thread.
        TakesRepository.init(requireContext());
        TakesRepository.onReady(() -> {
            if (isAdded() && getView() != null) refresh();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        ConversionJobs.addListener(jobsListener);
        registerConvReceiver();
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseForBackground();
        ConversionJobs.removeListener(jobsListener);
        unregisterConvReceiver();
    }

    /**
     * Last live-job set that triggered a structural refresh. Must be declared
     * before {@link #jobsListener}, which reads it during initialization.
     */
    private String lastStructuralSig = "";

    private final ConversionJobs.Listener jobsListener = () -> {
        try {
            if (!isAdded() || getView() == null) return;
            handler.post(() -> {
                if (!isAdded() || getView() == null || adapter == null) return;
                // Structural change (job added/finished/cancelled) needs a
                // full rebind; bare progress ticks only repaint their rows.
                Set<Long> shown = new HashSet<>();
                for (Take t : visible) {
                    if (t.converting) shown.add(t.id);
                }
                Set<Long> live = new HashSet<>();
                for (ConversionJob j : ConversionJobs.active()) {
                    live.add(j.takeId());
                }
                if (!shown.equals(live)) {
                    // A conversion finishing is not a routine structural tick:
                    // the raw it was previewing from is gone, so tear the
                    // preview down first and skip the 500ms debounce that
                    // would otherwise leave a dead, clickable card on screen.
                    //
                    // Only act on a real transition, though. This condition can
                    // hold continuously (a job whose card the active filter
                    // hides, or a job left QUEUED with no worker), and
                    // refreshing on every progress tick then rebinds the whole
                    // list hundreds of times: the UI thread never goes idle,
                    // which starves accessibility queries. One refresh per
                    // distinct set of live jobs is the whole requirement.
                    String sig = new java.util.TreeSet<>(live).toString();
                    if (!sig.equals(lastStructuralSig)) {
                        lastStructuralSig = sig;
                        if (rawMode && playingId < 0
                                && ConversionJobs.forTakeId(playingId) == null) {
                            stopPlayback();
                        }
                        refreshImmediate();
                    }
                    return;
                }
                lastStructuralSig = "";
                for (Take t : visible) {
                    if (!t.converting) continue;
                    ConversionJob j = ConversionJobs.forTakeId(t.id);
                    if (j == null) continue;
                    int pct = Math.max(0, Math.min(100, j.progress));
                    if (pct == t.convProgress) continue;
                    t.convProgress = pct;
                    String status = null;
                    try {
                        status = getString(R.string.conv_card_status,
                                t.convFormat, pct);
                        t.dateLabel = status;
                    } catch (Exception ignored) {}
                    // Direct holder paint: no notify calls on the progress
                    // hot path, so rapid ticks can never race a layout pass.
                    // Off-screen rows pick the values up on their next bind.
                    if (adapter != null && list != null) {
                        try {
                            adapter.paintConvProgress(list, t.id, pct, status);
                        } catch (Exception ignored) {}
                    }
                }
            });
        } catch (Exception ignored) {}
    };

    private void registerConvReceiver() {
        if (convReceiver != null) return;
        try {
            convReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!ConversionService.BROADCAST_CONVERSION_DONE.equals(
                            intent.getAction())) {
                        return;
                    }
                    boolean ok = intent.getBooleanExtra(
                            ConversionService.EXTRA_OK, false);
                    String title = intent.getStringExtra(
                            ConversionService.EXTRA_TITLE);
                    String label = intent.getStringExtra(
                            ConversionService.EXTRA_LABEL);
                    String jobId = intent.getStringExtra(
                            ConversionService.EXTRA_JOB_ID);
                    handler.post(() -> {
                        if (!isAdded()) return;
                        // A finished raw preview must stop: its file is gone.
                        if (rawMode) stopPlayback();
                        requestStructuralRefresh();
                        if (ok) {
                            snackText(getString(R.string.conv_saved_bg, title,
                                    label != null ? label : ""));
                        } else if (jobId != null
                                && cancelledJobs.remove(jobId)) {
                            // The user cancelled this one; it is not a
                            // failure and must not claim the raw was kept.
                        } else {
                            snackText(getString(R.string.conv_failed_bg,
                                    title != null ? title : ""));
                        }
                    });
                }
            };
            IntentFilter f = new IntentFilter(
                    ConversionService.BROADCAST_CONVERSION_DONE);
            androidx.core.content.ContextCompat.registerReceiver(
                    requireContext(), convReceiver, f,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception ignored) {
            convReceiver = null;
        }
    }

    private void unregisterConvReceiver() {
        if (convReceiver == null) return;
        try {
            requireContext().unregisterReceiver(convReceiver);
        } catch (Exception ignored) {}
        convReceiver = null;
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

    /** External refresh (e.g. MainActivity after library init). */
    public void refreshExternal() {
        if (isAdded() && getView() != null) refresh();
    }

    private void refresh() {
        // Defensive: refresh() is reachable from lifecycle callbacks that can
        // fire before onViewCreated has built the adapter (and from a queued
        // repository callback racing a destroyed view). Never let a render
        // helper take the process down.
        if (adapter == null || getView() == null) return;
        long now = System.currentTimeMillis();
        List<Take> out = new ArrayList<>();
        List<Take> converting = convertingCards();
        out.addAll(converting);
        for (Take t : TakesRepository.all()) {
            switch (filter) {
                case FAV: if (!t.favorite) continue; break;
                case DAY: if (now - t.timestamp > 24L * 3600_000L) continue; break;
                case WEEK: if (now - t.timestamp > 7L * 24 * 3600_000L) continue; break;
                case SHORT: if (t.durationMs >= 5L * 60_000L) continue; break;
                case WAV:
                    if (t.format == null
                            || !t.format.toUpperCase(Locale.US)
                            .startsWith("WAV")) continue;
                    break;
                case FLAC:
                    if (!"FLAC".equalsIgnoreCase(t.format)) continue;
                    break;
                case OPUS:
                    if (!"OPUS".equalsIgnoreCase(t.format)) continue;
                    break;
                case AMR:
                    if (t.format == null
                            || !t.format.toUpperCase(Locale.US)
                            .startsWith("AMR")) continue;
                    break;
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
        // Active conversions pin to the top in job order; the library sort
        // applies to finished takes only.
        List<Take> finished = new ArrayList<>(out.subList(converting.size(),
                out.size()));
        Collections.sort(finished, cmp);
        out = new ArrayList<>(converting);
        out.addAll(finished);
        visible = out;

        Set<Long> alive = new HashSet<>();
        for (Take t : TakesRepository.all()) alive.add(t.id);
        selection.retainAll(alive);
        if (selectionMode && selection.isEmpty()) selectionMode = false;

        // A finished conversion replaces a synthetic card (negative id) with
        // its real take (positive id). Under stable ids RecyclerView treats
        // that as a remove+insert and runs overlapping remove/add/move
        // animations against a holder that is never rebound, which is what
        // took the process down. Structural changes are rare and the list
        // rebinds in one pass anyway.
        if (rawMode && playingId < 0
                && ConversionJobs.forTakeId(playingId) == null) {
            stopPlayback();
        }
        adapter.setTakesAndState(out, playingId, playPaused,
                selection, selectionMode);

        int total = TakesRepository.all().size();
        boolean scoped = !query.isEmpty() || filter != Filter.ALL;
        int conv = converting.size();
        // A4-12: converting cards are synthetic rows that live outside the
        // Room set, so counting them made "3 of 2 takes" appear.
        String base = scoped
                ? getString(R.string.search_count, out.size() - conv, total)
                : getString(R.string.take_count, total);
        subtitle.setText(base + "  •  " + sortLabel(sort)
                + (conv > 0 ? "  •  " + getString(R.string.conv_active, conv)
                        : ""));

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

    /**
     * Coalesced full rebind for structural job changes. Back-to-back dataset
     * swaps (appear → progress → disappear) must never stack overlapping
     * layout passes, so requests inside the window collapse into one.
     */
    private long lastStructuralMs = 0;
    /** Structural refresh with no debounce, for completion/cancellation. */
    private void refreshImmediate() {
        structuralPending = false;
        lastStructuralMs = SystemClock.elapsedRealtime();
        if (isAdded() && getView() != null) refresh();
    }

    private boolean structuralPending = false;
    private static final long STRUCTURAL_MIN_GAP_MS = 500;

    private void requestStructuralRefresh() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStructuralMs < STRUCTURAL_MIN_GAP_MS) {
            if (structuralPending) return;
            structuralPending = true;
            long wait = STRUCTURAL_MIN_GAP_MS - (now - lastStructuralMs);
            handler.postDelayed(() -> {
                structuralPending = false;
                lastStructuralMs = SystemClock.elapsedRealtime();
                if (isAdded() && getView() != null) refresh();
            }, wait);
            return;
        }
        lastStructuralMs = now;
        refresh();
    }

    /**
     * Synthetic cards for active background conversions. They are never
     * persisted (no Room row, stable negative ids) and always pin above
     * finished takes so conversions are visible at a glance.
     */
    private List<Take> convertingCards() {
        List<Take> cards = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ConversionJob job : ConversionJobs.active()) {
            String target;
            try {
                target = TakeSaver.labelFor(
                        RecordPreset.of(job.presetId),
                        SaveFormat.of(job.formatId));
            } catch (Exception e) {
                target = "";
            }
            String size;
            try {
                size = Dsp.sizeLabel(new File(job.rawPath).length());
            } catch (Exception e) {
                size = "";
            }
            Take t = new Take(job.takeId(), job.title, job.durationMs, now,
                    size, target, false, "");
            t.converting = true;
            t.convProgress = Math.max(0, Math.min(100, job.progress));
            t.convFormat = target;
            t.convJobId = job.jobId;
            t.convRawPath = job.rawPath;
            t.dateLabel = getString(R.string.conv_card_status, target,
                    t.convProgress);
            ConversionJobs.noteTakeRaw(t.id, job.rawPath);
            cards.add(t);
        }
        return cards;
    }

    /** Jumps to the top where active-conversion cards live. */
    private void scrollToConversions() {
        try {
            if (list != null) list.scrollToPosition(0);
        } catch (Exception ignored) {}
    }

    // ---- selection ----

    private void enterSelection(Take take) {
        if (take.converting) return; // converting takes cannot be selected
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
        if (take.converting) return; // converting takes cannot be selected
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
        // AUDIT-3-03 fix: exclude active converting cards (`t.converting`),
        // matching `enterSelection` and `toggleSelection`. Including them
        // made the count comparison never true and selected a card that
        // refuses selection.
        List<Take> selectable = new ArrayList<>();
        for (Take t : visible) {
            if (!t.converting) selectable.add(t);
        }
        boolean allSel = !selectable.isEmpty()
                && selection.size() == selectable.size();
        selection.clear();
        if (!allSel) {
            for (Take t : selectable) selection.add(t.id);
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
        // A4-12: only the first start asks for focus; the toggle branches
        // below are pause/resume of an already-focused player.
        if (playingId != take.id && !acquirePlaybackFocus()) {
            snackText(getString(R.string.playback_busy));
            return;
        }
        if (take.converting) {
            startRawPreview(take);
            return;
        }
        if (playingId == take.id) {
            if (playPaused) resumePlayback();
            else pausePlayback();
            return;
        }
        // Long takes resume where they stopped; short ones restart.
        long resume = take.lastPositionMs;
        if (resume < 10_000 || resume > take.durationMs - 10_000) resume = 0;
        startPlayback(take, resume);
    }

    /**
     * Previews the stored raw of a take that is still converting. The final
     * file does not exist yet; the raw is a valid float WAV and plays
     * straight through AudioTrack.
     */
    private void startRawPreview(Take take) {
        if (playingId == take.id && rawMode) {
            if (playPaused) resumePlayback();
            else pausePlayback();
            return;
        }
        stopPlayback();
        File raw = new File(take.convRawPath);
        if (!raw.exists()) {
            snackText(getString(R.string.edit_no_audio));
            return;
        }
        try {
            final long id = take.id;
            rawPlayer = new RawFloatPlayer(raw, completed -> handler.post(() -> {
                if (playingId == id && rawMode) {
                    Take t = findVisible(id);
                    if (t != null) {
                        adapter.paintProgress(list, id, t.durationMs);
                    }
                    stopPlayback();
                    refresh();
                }
            }));
        } catch (Exception e) {
            rawPlayer = null;
            snackText(getString(R.string.edit_no_audio));
            return;
        }
        rawMode = true;
        playingId = take.id;
        playPaused = false;
        adapter.setProgress(list, take.id, 0);
        adapter.setExpandedId(take.id);
        adapter.setPlayingId(playingId);
        adapter.setPaused(false);
        rawPlayer.play();
        handler.post(progressTick);
        snackText(getString(R.string.conv_preview_hint, take.convFormat));
    }

    private Take findVisible(long id) {
        for (Take t : visible) {
            if (t.id == id) return t;
        }
        return null;
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
        persistCurrentPosition();
        if (rawMode && rawPlayer != null) {
            try {
                rawPlayer.pause();
            } catch (Exception ignored) {}
        } else if (!simMode && player != null) {
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
        if (rawMode && rawPlayer != null) {
            try {
                rawPlayer.play();
            } catch (Exception ignored) {}
        } else if (!simMode && player != null) {
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
        // Finished: next play starts over instead of resuming the tail.
        TakesRepository.updatePosition(id, 0);
    }

    private void stopPlayback() {
        persistCurrentPosition();
        handler.removeCallbacks(progressTick);
        playingId = -1;
        playPaused = false;
        rawMode = false;
        if (rawPlayer != null) {
            try {
                rawPlayer.stop();
            } catch (Exception ignored) {}
            rawPlayer = null;
        }
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
        releasePlaybackFocus();
    }

    private long simPosition() {
        Take t = TakesRepository.find(playingId);
        long pos = simBase + (SystemClock.elapsedRealtime() - simStartedAt);
        return t == null ? pos : Math.min(pos, t.durationMs);
    }

    /** Persists where the current take stopped (pause / switch / exit). */
    private void persistCurrentPosition() {
        if (playingId == -1) return;
        if (rawMode) return; // converting rows have no stored position
        long pos;
        if (simMode) {
            pos = simPosition();
        } else if (player != null) {
            try {
                pos = player.getCurrentPosition();
            } catch (Exception e) {
                return;
            }
        } else {
            return;
        }
        TakesRepository.updatePosition(playingId, pos);
    }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (playingId == -1 || playPaused || getView() == null) return;
            if (rawMode) {
                Take t = findVisible(playingId);
                if (t == null || rawPlayer == null) {
                    stopPlayback();
                    return;
                }
                long pos = rawPlayer.positionMs();
                if (pos >= t.durationMs) {
                    if (adapter.isTracking(playingId, list)) {
                        rawPlayer.seekToMs(0);
                        adapter.paintProgress(list, playingId, 0);
                        handler.postDelayed(this, PROGRESS_MS);
                        return;
                    }
                    completeRawPreview(playingId);
                    return;
                }
                adapter.paintProgress(list, playingId, pos);
                handler.postDelayed(this, PROGRESS_MS);
                return;
            }
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

    private void completeRawPreview(long id) {
        if (playingId != id) return;
        Take t = findVisible(id);
        if (t != null) adapter.paintProgress(list, id, t.durationMs);
        stopPlayback();
        refresh();
    }

    @Override
    public void onSeek(Take take, long posMs) {
        TakesRepository.updatePosition(take.id, posMs);
        if (playingId == take.id && !playPaused) {
            if (rawMode && rawPlayer != null) {
                try {
                    rawPlayer.seekToMs(posMs);
                } catch (Exception ignored) {}
            } else if (!simMode && player != null) {
                try {
                    player.seekTo((int) posMs);
                } catch (Exception ignored) {}
            } else if (simMode) {
                simBase = Math.min(posMs, take.durationMs);
                simStartedAt = SystemClock.elapsedRealtime();
            }
        } else if (playingId == take.id) {
            if (rawMode && rawPlayer != null) {
                try {
                    rawPlayer.seekToMs(posMs);
                } catch (Exception ignored) {}
            } else if (!simMode && player != null) {
                try {
                    player.seekTo((int) posMs);
                } catch (Exception ignored) {}
            } else {
                simBase = Math.min(posMs, take.durationMs);
            }
            adapter.paintProgress(list, take.id, posMs);
        } else {
            if (take.converting) {
                startRawPreview(take);
                if (posMs > 0 && rawPlayer != null) {
                    try {
                        rawPlayer.seekToMs(posMs);
                    } catch (Exception ignored) {}
                }
            } else {
                startPlayback(take, posMs);
            }
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
        if (take.converting) return;
        if (!selectionMode) enterSelection(take);
    }

    @Override
    public void onFavorite(Take take) {
        if (take.converting) return;
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
        // Renaming a converting take is safe: it only changes the title, and
        // the worker reads the job's title when it inserts the finished take.
        OptionsSheet.showInput(requireContext(), getString(R.string.rename_title),
                take.title, getString(R.string.rename_save), text -> {
                    try {
                        if (take.converting) {
                            ConversionJob job = take.convJobId != null
                                    ? ConversionJobs.get(take.convJobId)
                                    : ConversionJobs.forTakeId(take.id);
                            if (job != null) {
                                String clean = text.trim();
                                if (!clean.isEmpty()) {
                                    job.title = clean;
                                    job.write();
                                }
                            }
                        } else {
                            TakesRepository.rename(take.id, text);
                        }
                        refresh();
                        Take updated = TakesRepository.find(take.id);
                        String name = take.converting ? text.trim()
                                : (updated != null ? updated.title : text);
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
        if (take.converting) {
            showConvertingShareWarning(take);
            return;
        }
        if (!take.hasFile()) return;
        showSingleExportOptions(take);
    }

    /**
     * The finished file is not ready, so sharing is blocked — but the stored
     * raw can be shared instead, with a live look at where the conversion
     * stands and where to watch it.
     */
    private void showConvertingShareWarning(Take take) {
        if (!isAdded()) return;
        ConversionJob job = ConversionJobs.forTakeId(take.id);
        File raw = new File(take.convRawPath);
        Dialog dialog = new Dialog(requireContext(),
                R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_progress, null);
        dialog.setContentView(root);
        TextView titleView = root.findViewById(R.id.progressTitle);
        TextView msgView = root.findViewById(R.id.progressMessage);
        ProgressBar bar = root.findViewById(R.id.progressBar);
        titleView.setText(R.string.conv_share_title);
        bar.setMax(100);
        Runnable paintWarn = () -> {
            try {
                ConversionJob j = job != null ? ConversionJobs.get(job.jobId)
                        : ConversionJobs.forTakeId(take.id);
                int pct = j != null ? Math.max(0, Math.min(100, j.progress))
                        : take.convProgress;
                bar.setProgress(pct);
                msgView.setText(getString(R.string.conv_share_msg,
                        take.title, take.convFormat, pct));
            } catch (Exception ignored) {}
        };
        paintWarn.run();
        // Live progress inside this dialog, plus the list behind it.
        ConversionJobs.Listener progressPaint = () -> {
            try {
                if (dialog.isShowing()) {
                    handler.post(paintWarn);
                }
            } catch (Exception ignored) {}
        };
        ConversionJobs.addListener(progressPaint);
        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.CENTER);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        buttons.setPadding(0, pad * 2, 0, 0);
        TextView share = warnButton(getString(R.string.conv_share_wav));
        share.setOnClickListener(v -> {
            try {
                ConversionJobs.removeListener(progressPaint);
            } catch (Exception ignored) {}
            try {
                dialog.dismiss();
            } catch (Exception ignored) {}
            if (!raw.exists()) {
                snackText(getString(R.string.edit_no_audio));
                return;
            }
            Take rawTake = new Take(take.id, take.title + " (WAV)",
                    take.durationMs, System.currentTimeMillis(),
                    take.sizeLabel, "WAV", false, raw.getAbsolutePath());
            shareFiles(singleton(rawTake));
        });
        TextView viewAll = warnButton(getString(R.string.conv_view_all));
        viewAll.setOnClickListener(v -> {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {}
            // Conversions pin to the top of the library with all filters
            // cleared so every active job is visible.
            query = "";
            try {
                if (searchInput != null) searchInput.setText("");
            } catch (Exception ignored) {}
            filter = Filter.ALL;
            paintChips();
            refresh();
            scrollToConversions();
        });
        TextView cancel = warnButton(getString(R.string.cancel));
        cancel.setOnClickListener(v -> {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {}
        });
        buttons.addView(share, buttonParams(1f));
        buttons.addView(viewAll, buttonParams(1f));
        buttons.addView(cancel, buttonParams(1f));
        ((ViewGroup) root).addView(buttons);
        dialog.setOnDismissListener(d -> {
            try {
                ConversionJobs.removeListener(progressPaint);
            } catch (Exception ignored) {}
        });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.62f);
        }
        dialog.show();
    }

    private TextView warnButton(String text) {
        TextView b = new TextView(requireContext());
        b.setText(text);
        b.setTextSize(13);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTextColor(0xFFF2F4F7);
        b.setBackgroundResource(R.drawable.bg_btn_ghost);
        int v = Math.round(10 * getResources().getDisplayMetrics().density);
        int h = Math.round(6 * getResources().getDisplayMetrics().density);
        b.setPadding(h, v, h, v);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        int m = Math.round(4 * getResources().getDisplayMetrics().density);
        p.setMargins(m, 0, m, 0);
        return p;
    }

    /** Singular export: Share or Export to another folder (folder picker). */
    private void showSingleExportOptions(Take take) {
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_share,
                getString(R.string.export_share), () -> shareFiles(singleton(take))));
        items.add(OptionsSheet.Item.of(R.drawable.ic_folder,
                getString(R.string.export_save), () -> launchFolderPicker(singleton(take))));
        OptionsSheet.showOptions(requireContext(), getString(R.string.action_export),
                take.title + "\n" + getString(R.string.export_current_location,
                        currentTakesDirLabel()),
                items);
    }

    @Override
    public void onEdit(Take take) {
        if (selectionMode) {
            toggleSelection(take);
            return;
        }
        if (take.converting) {
            // Edit from the stored raw: the punch-in copies bytes out, so
            // the running conversion is never invalidated. The negative
            // source id lets the replace flow resolve the original later.
            if (RecordingEngine.get().hasSession()) {
                snackText(getString(R.string.edit_busy));
                return;
            }
            File raw = new File(take.convRawPath);
            if (!raw.exists()) {
                snackText(getString(R.string.edit_no_audio));
                return;
            }
            startEditFromFile(raw, take.id, take.title);
            return;
        }
        if (!take.hasFile()) {
            snackText(getString(R.string.edit_no_audio));
            return;
        }
        if (RecordingEngine.get().hasSession()) {
            snackText(getString(R.string.edit_busy));
            return;
        }
        if (playingId == take.id) stopPlayback();
        startEditFromFile(new File(take.filePath), take.id, take.title);
    }

    /**
     * Punch-in edit from any readable source file (a finished take or the
     * stored raw of a converting one). The source is only ever read, so
     * editing can never invalidate a running conversion.
     */
    /** Session channels for an imported file: mono stays mono (BUG-08). */
    private static int sessionChannels(File session) {
        try {
            FormatConverter.WavInfo info =
                    FormatConverter.readFloatWav(session);
            if (info.channels == 1 || info.channels == 2) {
                return info.channels;
            }
        } catch (Exception ignored) {}
        return 2;
    }

    private void startEditFromFile(File src, long sourceId, String sourceTitle) {
        // A4-02: capture the application context now (we are on the UI
        // thread). The worker below must not call requireContext(): a
        // fragment detached mid-import would otherwise abort after having
        // already written a session file.
        final android.content.Context appCtx =
                requireContext().getApplicationContext();
        // Decode a copy to 32-bit float and punch in from its end. The
        // original stays in the library until the user picks replace/new.
        Dialog progress = showDeterminateProgress(
                getString(R.string.importing_title),
                getString(R.string.importing_msg, 0));
        final ProgressBar bar;
        final TextView msg;
        try {
            bar = progress.findViewById(R.id.progressBar);
            msg = progress.findViewById(R.id.progressMessage);
        } catch (Exception e) {
            try {
                progress.dismiss();
            } catch (Exception ignored) {}
            return;
        }
        new Thread(() -> {
            File session = null;
            String fail = null;
            try {
                // STU-03: hold the raw while we decode it so the conversion
                // worker cannot delete it mid-import.
                ConversionJobs.beginRawRead(src.getAbsolutePath());
                session = AudioImporter.takeToFloatWav(src,
                        RecordingEngine.takesDir(appCtx),
                        RecordingEngine.sanitize(sourceTitle), pct -> handler.post(() -> {
                            try {
                                if (bar != null) bar.setProgress(pct);
                                if (msg != null) {
                                    msg.setText(getString(R.string.importing_msg, pct));
                                }
                            } catch (Exception ignored) {}
                        }));
                RecordingEngine.get().attachExisting(
                        appCtx, session,
                        sourceTitle, sessionChannels(session),
                        RecordingEngine.payloadBytesOf(session));
            } catch (Exception e) {
                fail = e.getMessage();
                if (session != null && session.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    session.delete();
                }
            } finally {
                ConversionJobs.endRawRead(src.getAbsolutePath());
            }
            final File done = session;
            final String failure = fail;
            handler.post(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignored) {}
                if (!isAdded()) return;
                if (done != null && failure == null) {
                    try {
                        EditLink.save(appCtx, sourceId, sourceTitle,
                                done.getAbsolutePath());
                        ((MainActivity) requireActivity())
                                .openStudioWithEdit(done, sourceId, sourceTitle);
                    } catch (Exception e) {
                        RecordingEngine.get().abortAndDelete();
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.import_failed_title),
                                getString(R.string.import_failed_msg, sourceTitle));
                    }
                } else {
                    OptionsSheet.showResult(requireContext(), false,
                            getString(R.string.import_failed_title),
                            getString(R.string.import_failed_msg, sourceTitle));
                }
            });
        }).start();
    }

    /** Styled determinate progress dialog (import / conversion prep). */
    private Dialog showDeterminateProgress(String title, String message) {
        Dialog dialog = new Dialog(requireContext(), R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_progress, null);
        dialog.setContentView(root);
        ((TextView) root.findViewById(R.id.progressTitle)).setText(title);
        ((TextView) root.findViewById(R.id.progressMessage)).setText(message);
        dialog.setCancelable(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.62f);
        }
        dialog.show();
        return dialog;
    }

    @Override
    public void onCancelConversion(Take take) {
        showCancelConversionConfirm(take);
    }

    @Override
    public void onDelete(Take take) {        if (take.converting) {
            showCancelConversionConfirm(take);
            return;
        }
        OptionsSheet.showConfirm(requireContext(),
                getString(R.string.delete_one_title),
                getString(R.string.delete_one_msg, take.title),
                getString(R.string.delete_confirm),
                R.drawable.ic_trash, true,
                () -> performDeleteSingle(take));
    }

    /** Delete on a converting card means cancel the conversion. */
    private void showCancelConversionConfirm(Take take) {
        ConversionJob job = ConversionJobs.forTakeId(take.id);
        String target = take.convFormat != null ? take.convFormat : "";
        OptionsSheet.showConfirm(requireContext(),
                getString(R.string.conv_cancel_title),
                getString(R.string.conv_cancel_msg, take.title, target),
                getString(R.string.delete_confirm),
                R.drawable.ic_trash, true,
                () -> {
                    if (playingId == take.id) stopPlayback();
                    if (job != null) {
                        // Tell the worker first so it stops touching a raw we
                        // are about to delete, then cancel the registry entry.
                        try {
                            ConversionService.cancelJob(requireContext()
                                    .getApplicationContext(), job.jobId);
                        } catch (Exception ignored) {}
                        ConversionJobs.cancel(job.jobId, true);
                        if (job.jobId != null) {
                            // Stop the done-broadcast from painting a false
                            // "couldn't save" over the confirmed cancel.
                            cancelledJobs.add(job.jobId);
                        }
                    }
                    refresh();
                    snackText(getString(R.string.conv_cancelled));
                });
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
                () -> launchFolderPicker(files)));
        OptionsSheet.showOptions(requireContext(), getString(R.string.action_export),
                getString(R.string.export_subtitle, n) + "\n"
                        + getString(R.string.export_current_location,
                        currentTakesDirLabel()),
                items);
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

    // ---- unified export: user picks a folder (SAF), we copy file(s) there ----

    /** Both single-card and multi-select export go through this one picker. */
    private void launchFolderPicker(List<Take> takes) {
        List<Take> withFiles = new ArrayList<>();
        for (Take t : takes) {
            if (t != null && t.hasFile()) withFiles.add(t);
        }
        if (withFiles.isEmpty()) {
            OptionsSheet.showResult(requireContext(), false,
                    getString(R.string.couldnt_save_title),
                    getString(R.string.couldnt_save));
            return;
        }
        pendingExportTakes = withFiles;
        try {
            openTreeLauncher.launch(null);
        } catch (Exception e) {
            pendingExportTakes = null;
            OptionsSheet.showResult(requireContext(), false,
                    getString(R.string.couldnt_save_title),
                    getString(R.string.couldnt_export));
        }
    }

    private void exportTakesToFolder(List<Take> takes, Uri treeUri) {
        final Context app = requireContext().getApplicationContext();
        final List<Take> copy = new ArrayList<>(takes);
        final String folderLabel = resolveFolderLabel(treeUri);
        new Thread(() -> {
            final List<String> savedNames = new ArrayList<>();
            for (Take t : copy) {
                String name = saveOneToTree(app, t, treeUri);
                if (name != null) savedNames.add(name);
            }
            final int n = savedNames.size();
            final int total = copy.size();
            handler.post(() -> {
                if (getView() == null || !isAdded()) return;
                if (n == total && n == 1) {
                    OptionsSheet.showResult(requireContext(), true,
                            getString(R.string.export_complete_title),
                            getString(R.string.saved_to_location,
                                    folderLabel + "/" + savedNames.get(0)));
                } else if (n == total) {
                    OptionsSheet.showResult(requireContext(), true,
                            getString(R.string.export_complete_title),
                            getString(R.string.saved_n_to_location, n, folderLabel));
                } else if (n > 0) {
                    OptionsSheet.showResult(requireContext(), false,
                            getString(R.string.export_partial_title),
                            getString(R.string.export_partial_msg, n, total, folderLabel));
                } else {
                    OptionsSheet.showResult(requireContext(), false,
                            getString(R.string.couldnt_save_title),
                            getString(R.string.export_failed_msg, total, folderLabel));
                }
            });
        }).start();
    }

    /**
     * Copies one take into the user-picked tree URI.
     *
     * @return the file name actually created, or null on failure.
     */
    private String saveOneToTree(Context ctx, Take t, Uri treeUri) {
        File src = new File(t.filePath);
        if (!src.exists()) return null;
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(ctx, treeUri);
            if (dir == null || !dir.canWrite()) return null;
            String wanted = safeName(src.getName());
            String uniqueName = uniqueDocName(dir, wanted);
            String mime = mimeFor(uniqueName);
            DocumentFile out = dir.createFile(mime, uniqueName);
            // Some providers ignore the requested name; fall back to display name.
            if (out == null) return null;
            try (InputStream in = new FileInputStream(src);
                 OutputStream outStream = ctx.getContentResolver().openOutputStream(
                         out.getUri())) {
                if (outStream == null) {
                    try {
                        out.delete();
                    } catch (Exception ignored) {}
                    return null;
                }
                copyStream(in, outStream);
            }
            String actual = out.getName();
            return actual != null ? actual : uniqueName;
        } catch (Exception e) {
            return null;
        }
    }

    private String uniqueDocName(DocumentFile dir, String wanted) {
        if (dir.findFile(wanted) == null) return wanted;
        String base = wanted;
        String ext = "";
        int dot = wanted.lastIndexOf('.');
        if (dot > 0) {
            base = wanted.substring(0, dot);
            ext = wanted.substring(dot);
        }
        int i = 2;
        while (true) {
            String candidate = base + " (" + i + ")" + ext;
            if (dir.findFile(candidate) == null) return candidate;
            i++;
        }
    }

    private String mimeFor(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".webm")) return "audio/webm";
        if (lower.endsWith(".3gp") || lower.endsWith(".3gpp")) return "audio/3gpp";
        return "audio/*";
    }

    /** Best-effort human-readable label for the picked folder. */
    private String resolveFolderLabel(Uri treeUri) {
        if (treeUri == null) return "";
        String name = null;
        try {
            try (Cursor c = requireContext().getContentResolver().query(
                    treeUri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (col >= 0) name = c.getString(col);
                }
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            try {
                name = Uri.decode(treeUri.getLastPathSegment());
            } catch (Exception ignored) {}
        }
        if (name == null || name.isEmpty()) return treeUri.toString();
        // Tree URIs look like "primary:Download/Backup" — keep the tail.
        int colon = name.lastIndexOf(':');
        if (colon >= 0 && colon < name.length() - 1) name = name.substring(colon + 1);
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1 && name.contains(":")) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    /** Where takes currently live on this device (shown in the export dialog). */
    private String currentTakesDirLabel() {
        try {
            File base = requireContext().getExternalFilesDir(null);
            if (base == null) base = requireContext().getFilesDir();
            return new File(base, "takes").getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
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
