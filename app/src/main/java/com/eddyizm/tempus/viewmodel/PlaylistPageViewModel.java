package com.eddyizm.tempus.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.model.PinnedPlaylist;
import com.eddyizm.tempus.repository.PlaylistRepository;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.Preferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@UnstableApi
public class PlaylistPageViewModel extends AndroidViewModel {
    private final PlaylistRepository playlistRepository;
    private final androidx.lifecycle.Observer<Boolean> playlistUpdateObserver;

    private Playlist playlist;
    private boolean isOffline;

    private final MutableLiveData<List<Child>> songLiveList = new MutableLiveData<>();
    private final MutableLiveData<Boolean> playlistMissingEvent = new MutableLiveData<>();

    // The active client-side sort order (a Constants.PLAYLIST_SONG_ORDER_BY_* value).
    // Loaded from Preferences below; defaults to ORIGINAL (server order), so a user who
    // never picks a sort sees exactly the pre-feature behavior.
    private String currentSortOrder = Constants.PLAYLIST_SONG_ORDER_BY_ORIGINAL;

    public PlaylistPageViewModel(@NonNull Application application) {
        super(application);

        playlistRepository = new PlaylistRepository();
        currentSortOrder = Preferences.getPlaylistSongSortOrder();
        playlistUpdateObserver = needsRefresh -> {
            if (needsRefresh != null && needsRefresh && playlist != null) {
                refreshSongs();
            }
        };
        playlistRepository.getPlaylistUpdateTrigger().observeForever(playlistUpdateObserver);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        playlistRepository.getPlaylistUpdateTrigger().removeObserver(playlistUpdateObserver);
    }

    public LiveData<Boolean> getPlaylistMissingEvent() {
        return playlistMissingEvent;
    }

    public void clearPlaylistMissingEvent() {
        playlistMissingEvent.setValue(false);
    }

    public LiveData<List<Child>> getPlaylistSongLiveList() {
        if (songLiveList.getValue() == null && playlist != null) {
            refreshSongs();
        }
        return songLiveList;
    }

    private void refreshSongs() {
        if (playlist == null) return;
        LiveData<List<Child>> remoteData = playlistRepository.getPlaylistSongs(playlist.getId());
        remoteData.observeForever(new androidx.lifecycle.Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> songs) {
                if (songs == null) {
                    playlistMissingEvent.postValue(true);
                } else {
                    Comparator<Child> comparator = comparatorFor(currentSortOrder);
                    if (comparator != null) {
                        List<Child> sorted = new ArrayList<>(songs);
                        sorted.sort(comparator);
                        songLiveList.postValue(sorted);
                    } else {
                        songLiveList.postValue(songs);
                    }
                }
                remoteData.removeObserver(this);
            }
        });
    }

    // Reorders the displayed playlist songs client-side only; the server order is never
    // touched. The song list and the play/queue buttons both read songLiveList, so
    // re-posting here reorders playback too. `order` is a Constants.PLAYLIST_SONG_ORDER_BY_*
    // value; ORIGINAL re-fetches to restore the server's order. The choice persists across
    // add/remove refreshes and is applied to the first load as well (see refreshSongs).
    public void sortSongs(String order) {
        currentSortOrder = order;
        Comparator<Child> comparator = comparatorFor(order);
        if (comparator == null) {
            refreshSongs();
            return;
        }
        List<Child> current = songLiveList.getValue();
        if (current == null) return; // applied on first load via refreshSongs
        List<Child> sorted = new ArrayList<>(current);
        sorted.sort(comparator);
        songLiveList.setValue(sorted);
    }

    // null = original (server) order. Date sorts use the library "created" date;
    // Subsonic exposes no per-entry added-to-playlist timestamp.
    private Comparator<Child> comparatorFor(String order) {
        if (order == null) return null;
        Comparator<String> text = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<Date> date = Comparator.nullsLast(Comparator.naturalOrder());
        switch (order) {
            case Constants.PLAYLIST_SONG_ORDER_BY_TITLE_ASC: return Comparator.comparing(Child::getTitle, text);
            case Constants.PLAYLIST_SONG_ORDER_BY_TITLE_DESC: return Comparator.comparing(Child::getTitle, text).reversed();
            case Constants.PLAYLIST_SONG_ORDER_BY_ARTIST_ASC: return Comparator.comparing(Child::getArtist, text);
            case Constants.PLAYLIST_SONG_ORDER_BY_ARTIST_DESC: return Comparator.comparing(Child::getArtist, text).reversed();
            case Constants.PLAYLIST_SONG_ORDER_BY_ALBUM_ASC: return Comparator.comparing(Child::getAlbum, text);
            case Constants.PLAYLIST_SONG_ORDER_BY_ALBUM_DESC: return Comparator.comparing(Child::getAlbum, text).reversed();
            case Constants.PLAYLIST_SONG_ORDER_BY_RECENTLY_ADDED: return Comparator.comparing(Child::getCreated, date).reversed();
            case Constants.PLAYLIST_SONG_ORDER_BY_OLDEST_ADDED: return Comparator.comparing(Child::getCreated, date);
            default: return null; // ORIGINAL / unknown
        }
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public void setPlaylist(Playlist playlist) {
        if (this.playlist == null || !this.playlist.getId().equals(playlist.getId())) {
            this.playlist = playlist;
            this.songLiveList.setValue(null); // Clear old data immediately
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public LiveData<Boolean> isPinned(LifecycleOwner owner) {
        MutableLiveData<Boolean> isPinnedLive = new MutableLiveData<>();

        playlistRepository.getPinnedPlaylists().observe(owner, playlists -> {
            isPinnedLive.postValue(playlists.stream().anyMatch(obj -> obj.getPlaylistId().equals(playlist.getId())));
        });

        return isPinnedLive;
    }

    @OptIn(markerClass = UnstableApi.class)
    public void setPinned(boolean isNowPinned) {
        playlistRepository.insert(playlist);

        if (isNowPinned) {
            playlistRepository.pin(playlist.getId());
        } else {
            playlistRepository.unpin(playlist.getId());
        }
    }

    public void updateLastPlayed(String playlistId) {
        playlistRepository.updateLastPlayed(playlistId);
    }
}
