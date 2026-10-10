package com.example.hello;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LibraryFragment extends Fragment implements TakesAdapter.Listener {

    private TakesAdapter adapter;
    private TextView subtitle;
    private TextView chipAll;
    private TextView chipFav;
    private RecyclerView list;
    private LinearLayout emptyState;
    private LinearLayout searchBar;
    private EditText searchInput;

    private String query = "";
    private boolean favOnly = false;
    private MediaPlayer player;
    private long playingId = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        TakesRepository.seedIfNeeded();

        subtitle = v.findViewById(R.id.librarySubtitle);
        chipAll = v.findViewById(R.id.chipAll);
        chipFav = v.findViewById(R.id.chipFav);
        list = v.findViewById(R.id.takesList);
        emptyState = v.findViewById(R.id.emptyState);
        searchBar = v.findViewById(R.id.searchBar);
        searchInput = v.findViewById(R.id.searchInput);

        adapter = new TakesAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        v.findViewById(R.id.searchToggle).setOnClickListener(view -> {
            if (searchBar.getVisibility() == View.VISIBLE) {
                searchBar.setVisibility(View.GONE);
                searchInput.setText("");
            } else {
                searchBar.setVisibility(View.VISIBLE);
                searchInput.requestFocus();
            }
        });

        v.findViewById(R.id.libraryMore).setOnClickListener(view ->
                Toast.makeText(requireContext(), "TakeOne 1.1", Toast.LENGTH_SHORT).show());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString().trim().toLowerCase();
                refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        chipAll.setOnClickListener(view -> {
            favOnly = false;
            paintChips();
            refresh();
        });
        chipFav.setOnClickListener(view -> {
            favOnly = true;
            paintChips();
            refresh();
        });
        paintChips();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        stopPlayback();
        refresh();
    }

    @Override
    public void onDestroyView() {
        stopPlayback();
        super.onDestroyView();
    }

    private void paintChips() {
        chipAll.setTextColor(favOnly ? 0xFF9AA3AF : 0xFFF2F4F7);
        chipFav.setTextColor(favOnly ? 0xFFF2F4F7 : 0xFF9AA3AF);
        chipAll.setAlpha(favOnly ? 0.6f : 1f);
        chipFav.setAlpha(favOnly ? 1f : 0.6f);
    }

    private void refresh() {
        List<Take> out = new ArrayList<>();
        for (Take t : TakesRepository.all()) {
            if (favOnly && !t.favorite) continue;
            if (!query.isEmpty() && !t.title.toLowerCase().contains(query)) continue;
            out.add(t);
        }
        adapter.setTakes(out);
        adapter.setPlayingId(playingId);
        int total = TakesRepository.all().size();
        subtitle.setText(total + (total == 1 ? " take" : " takes"));
        boolean empty = out.isEmpty();
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ---- playback ----

    @Override
    public void onPlay(Take take) {
        if (playingId == take.id) {
            stopPlayback();
            refresh();
            return;
        }
        if (take.filePath == null || !(new File(take.filePath).exists())) {
            // Demo take: emulate playback UI briefly.
            playingId = take.id;
            adapter.setPlayingId(playingId);
            Toast.makeText(requireContext(), "Playing “" + take.title + "” (demo)",
                    Toast.LENGTH_SHORT).show();
            list.postDelayed(() -> {
                if (playingId == take.id) {
                    playingId = -1;
                    adapter.setPlayingId(-1);
                }
            }, 2500);
            return;
        }
        try {
            stopPlayback();
            player = new MediaPlayer();
            player.setDataSource(take.filePath);
            player.prepare();
            player.setOnCompletionListener(mp -> {
                playingId = -1;
                adapter.setPlayingId(-1);
            });
            player.start();
            playingId = take.id;
            adapter.setPlayingId(playingId);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not play take", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayback() {
        playingId = -1;
        if (adapter != null) adapter.setPlayingId(-1);
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {}
            try {
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override
    public void onFavorite(Take take) {
        TakesRepository.toggleFavorite(take.id);
        refresh();
    }

    @Override
    public void onMore(Take take, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, take.favorite ? "Unfavorite" : "Favorite");
        menu.getMenu().add(0, 2, 0, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                TakesRepository.toggleFavorite(take.id);
                refresh();
                return true;
            } else if (item.getItemId() == 2) {
                if (playingId == take.id) stopPlayback();
                TakesRepository.delete(take.id);
                refresh();
                Toast.makeText(requireContext(), "Take deleted", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        menu.show();
    }
}
