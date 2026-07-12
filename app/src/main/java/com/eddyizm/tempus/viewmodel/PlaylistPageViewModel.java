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
    private String writingPlaylistId;
    private String reportedGonePlaylistId;
    private int fetchSequence;
    private int publishedSequence;

    private final MutableLiveData<List<Child>> songLiveList = new MutableLiveData<>();
    private final MutableLiveData<Boolean> playlistMissingEvent = new MutableLiveData<>();

    // The active client-side sort order (a Constants.PLAYLIST_SONG_ORDER_BY_* value).
    // Loaded from Preferences below; defaults to ORIGINAL (server order), so a user who
    // never picks a sort sees exactly the pre-feature behavior.
    private String currentSortOrder = Constants.PLAYLIST_SONG_ORDER_BY_ORIGINAL;

    // The order the server holds, which a sort reorders for display only. Every index the
    // server takes is a position in this list, never in the list on screen.
    private List<Child> serverOrder;

    // The song removeSong last sent and the position it sent it at, so an undo puts the song
    // back where it was on the server and not at the row it occupied on screen.
    private String removedSongId;
    private int removedServerIndex = -1;

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
        int sequence = ++fetchSequence;
        String forPlaylist = playlist.getId();
        LiveData<List<Child>> remoteData = playlistRepository.getPlaylistSongs(forPlaylist, true, () -> playlistMissing(forPlaylist));
        remoteData.observeForever(new androidx.lifecycle.Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> songs) {
                remoteData.removeObserver(this);
                // An answer for a playlist the page has left is dropped. While a write to this
                // playlist is out, and when a newer fetch has been issued or a write has started
                // or published since, the answer can be a list the page has moved past, so it is
                // dropped too, unless the page has no list at all, where a list beats none until
                // the newest fetch or the write's own answer lands.
                if (playlist == null || !forPlaylist.equals(playlist.getId())) return;
                boolean movedPast = forPlaylist.equals(writingPlaylistId) || sequence != fetchSequence;
                if (songs == null) {
                    if (!movedPast && songLiveList.getValue() == null) playlistMissing(forPlaylist);
                    return;
                }
                if (movedPast && songLiveList.getValue() != null) return;
                publishedSequence = sequence;
                publishSongs(songs);
            }
        });
    }

    /**
     * Removes the song at index on the server and drops it from the list at once, so a remove
     * that follows before the refetch lands sends an index the server agrees with. On failure
     * the row comes back, if the list is still the one it left, and the server is asked for the
     * list, since a lost answer can follow a delete the server did apply. A remove that succeeds
     * while a read has replaced the list drops the row again when that read is the list it
     * started from, and asks the server otherwise. This view model runs one write at a time,
     * since a second request could reach the server first.
     * <p>
     * index is the row's position on screen. A sort reorders the list for display and leaves the
     * server's order alone, so the position sent is looked up in that order by song id.
     */
    public void removeSong(String playlistId, int index, PlaylistRepository.AddToPlaylistCallback callback) {
        List<Child> songs = songLiveList.getValue();
        if (writingPlaylistId != null || playlist == null || !playlist.getId().equals(playlistId) || songs == null || serverOrder == null || index < 0 || index >= songs.size()) {
            callback.onFailure();
            return;
        }

        boolean sorted = comparatorFor(currentSortOrder) != null;
        int serverIndex = sorted ? serverIndexOf(songs.get(index)) : index;
        if (serverIndex < 0 || serverIndex >= serverOrder.size()) {
            callback.onFailure();
            return;
        }

        writingPlaylistId = playlistId;
        publishedSequence = ++fetchSequence;
        List<Child> withoutSong = new ArrayList<>(serverOrder);
        Child removed = withoutSong.remove(serverIndex);
        removedSongId = removed.getId();
        removedServerIndex = serverIndex;
        List<Child> shorter = publishSongs(withoutSong);

        playlistRepository.removeSongFromPlaylist(playlistId, serverIndex, new PlaylistRepository.AddToPlaylistCallback() {
            @Override
            public void onSuccess() {
                // A read issued meanwhile can have replaced the list with one from before the
                // delete, which is dropped again here; any other list gets a fresh read, with
                // the flag up until it lands.
                List<Child> current = songLiveList.getValue();
                int at = sameIds(current, songs) ? (sorted ? serverIndexOf(removed) : index) : -1;
                if (current == shorter || playlist == null || !playlist.getId().equals(playlistId)) {
                    writingPlaylistId = null;
                } else if (at >= 0) {
                    writingPlaylistId = null;
                    publishedSequence = ++fetchSequence;
                    List<Child> again = new ArrayList<>(serverOrder);
                    again.remove(at);
                    publishSongs(again);
                } else {
                    reconcileSongs(playlistId, true);
                }
                callback.onSuccess();
            }

            @Override
            public void onFailure() {
                List<Child> current = songLiveList.getValue();
                if (current == shorter) {
                    List<Child> back = new ArrayList<>(serverOrder);
                    back.add(Math.min(serverIndex, back.size()), removed);
                    publishSongs(back);
                }
                reconcileSongs(playlistId, true);
                callback.onFailure();
            }

            @Override
            public void onAllSkipped() {
                writingPlaylistId = null;
                callback.onAllSkipped();
            }
        });
    }

    /**
     * Puts song back by replacing the playlist's contents with the server's current list plus
     * the song, since the updatePlaylist call can only add a song at the end. The list is
     * fetched fresh, never from the cache, so nothing added since is written over. Refused
     * while another write is out, for the reason removeSong gives.
     * <p>
     * It goes back where removeSong took it from, since index is a row on screen and a sort
     * makes that a different position from the server's. index is used for any other song.
     */
    public void restoreSong(String playlistId, Child song, int index, PlaylistRepository.PlaylistActionCallback callback) {
        if (writingPlaylistId != null) {
            callback.onFailure();
            return;
        }

        int at = song != null && song.getId() != null && song.getId().equals(removedSongId) ? removedServerIndex : index;

        writingPlaylistId = playlistId;
        invalidateFetches(playlistId);
        LiveData<List<Child>> current = playlistRepository.getPlaylistSongs(playlistId, false, () -> playlistMissing(playlistId));
        current.observeForever(new androidx.lifecycle.Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> songs) {
                current.removeObserver(this);
                if (songs == null) {
                    writingPlaylistId = null;
                    callback.onFailure();
                    return;
                }
                List<Child> restored = new ArrayList<>(songs);
                restored.add(Math.min(at, restored.size()), song);
                ArrayList<String> ids = new ArrayList<>(restored.size());
                for (Child each : restored) ids.add(each.getId());
                playlistRepository.createPlaylist(playlistId, null, ids, new PlaylistRepository.PlaylistActionCallback() {
                    @Override
                    public void onSuccess() {
                        writingPlaylistId = null;
                        // restored is what the server holds now, so the page takes it whatever
                        // its list was, as long as it still shows this playlist.
                        if (invalidateFetches(playlistId)) {
                            publishedSequence = fetchSequence;
                            publishSongs(restored);
                        }
                        callback.onSuccess();
                    }

                    @Override
                    public void onFailure() {
                        reconcileSongs(playlistId, true);
                        callback.onFailure();
                    }
                });
            }
        });
    }

    public boolean isWriting() {
        return writingPlaylistId != null;
    }

    /**
     * Advances the fetch sequence, so every fetch out lands as stale, when the page still holds
     * playlistId, and says whether it does.
     */
    private boolean invalidateFetches(String playlistId) {
        if (playlist == null || !playlist.getId().equals(playlistId)) return false;
        ++fetchSequence;
        return true;
    }

    /**
     * Asks the server for the list after a write whose outcome the page cannot show from what it
     * has, twice at most, holding the write flag until a list comes, the read fails twice, or
     * the page has moved on, and takes the list whenever the page still holds the playlist and
     * nothing from a fetch issued after this read has been published.
     */
    private void reconcileSongs(String playlistId, boolean retry) {
        LiveData<List<Child>> remote = playlistRepository.getPlaylistSongs(playlistId, false, () -> playlistMissing(playlistId));
        int sequence = fetchSequence;
        remote.observeForever(new androidx.lifecycle.Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> songs) {
                remote.removeObserver(this);
                if (songs == null && retry && playlist != null && playlist.getId().equals(playlistId)) {
                    reconcileSongs(playlistId, false);
                    return;
                }
                writingPlaylistId = null;
                if (songs != null && publishedSequence <= sequence && invalidateFetches(playlistId)) {
                    publishedSequence = fetchSequence;
                    publishSongs(songs);
                }
            }
        });
    }

    private static boolean sameIds(List<Child> a, List<Child> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getId().equals(b.get(i).getId())) return false;
        }
        return true;
    }

    /**
     * The server said playlistId is gone, or a page with nothing to show got an empty answer.
     * Raised only while this view model still holds the playlist, since the read that learned it
     * may have been sent before the user opened another playlist, and only once per setPlaylist
     * call, since a page opened for a gone playlist learns it from each fetch it issued, some
     * after the dialog's OK has cleared the event.
     */
    private void playlistMissing(String playlistId) {
        if (playlist != null && playlist.getId().equals(playlistId) && !playlistId.equals(reportedGonePlaylistId)) {
            reportedGonePlaylistId = playlistId;
            playlistMissingEvent.setValue(true);
        }
    }

    // Reorders the displayed playlist songs client-side only; the server order is never
    // touched. The song list and the play/queue buttons both read songLiveList, so
    // re-publishing here reorders playback too. `order` is a Constants.PLAYLIST_SONG_ORDER_BY_*
    // value; ORIGINAL re-publishes the server's own order. The choice persists across
    // add/remove refreshes and reaches the first load through publishSongs.
    public void sortSongs(String order) {
        currentSortOrder = order;
        if (serverOrder != null) publishSongs(serverOrder);
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

    /**
     * Holds serverList as the order the server has and shows it sorted, and hands back the list
     * the page now shows, which a caller compares against to tell whether what it published is
     * still on screen.
     */
    private List<Child> publishSongs(List<Child> serverList) {
        serverOrder = serverList;
        Comparator<Child> comparator = comparatorFor(currentSortOrder);
        if (comparator == null) {
            songLiveList.setValue(serverList);
            return serverList;
        }
        List<Child> sorted = new ArrayList<>(serverList);
        sorted.sort(comparator);
        songLiveList.setValue(sorted);
        return sorted;
    }

    /**
     * Where song sits in the server's order, by id, or -1 when the page has no list or the song
     * is not in it. The row's position on screen is a different number once a sort is on, and
     * the server only takes a position in its own list.
     */
    private int serverIndexOf(Child song) {
        if (serverOrder == null || song == null || song.getId() == null) return -1;
        for (int i = 0; i < serverOrder.size(); i++) {
            if (song.getId().equals(serverOrder.get(i).getId())) return i;
        }
        return -1;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public void setPlaylist(Playlist playlist) {
        boolean isDifferentPlaylist = this.playlist == null || !this.playlist.getId().equals(playlist.getId());

        this.playlist = playlist;
        reportedGonePlaylistId = null;

        if (isDifferentPlaylist) {
            this.songLiveList.setValue(null); // Clear old data immediately
            serverOrder = null;
            removedSongId = null;
            playlistMissingEvent.setValue(false);
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
        playlistRepository.insertIfAbsent(playlist);

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
