package com.eddyizm.tempus.viewmodel;

import static org.junit.Assert.assertEquals;

import com.eddyizm.tempus.subsonic.models.Child;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class PlaylistPageViewModelTest {

    private static Child song(String id, String title) {
        Child child = new Child(id);
        child.setTitle(title);
        return child;
    }

    private static final Child BRAVO = song("1", "Bravo");
    private static final Child ALPHA = song("2", "Alpha");
    private static final Child CHARLIE = song("3", "Charlie");
    private static final List<Child> SERVER_ORDER = Arrays.asList(BRAVO, ALPHA, CHARLIE);

    // Sorted by title the page shows Alpha, Bravo, Charlie, so the row the user long presses
    // is at a position the server does not hold the same song at. Sending the row's own number
    // deletes a different track.
    @Test
    public void serverIndexOfFindsThePositionTheServerHolds() {
        List<Child> displayed = Arrays.asList(ALPHA, BRAVO, CHARLIE);
        assertEquals(1, PlaylistPageViewModel.serverIndexOf(SERVER_ORDER, displayed.get(0)));
        assertEquals(0, PlaylistPageViewModel.serverIndexOf(SERVER_ORDER, displayed.get(1)));
        assertEquals(2, PlaylistPageViewModel.serverIndexOf(SERVER_ORDER, displayed.get(2)));
    }

    // A fetch builds new objects for the same songs, so the match is by id and not by identity.
    @Test
    public void serverIndexOfMatchesARefetchedSong() {
        assertEquals(1, PlaylistPageViewModel.serverIndexOf(SERVER_ORDER, song("2", "Alpha")));
    }

    // Two songs can carry the same title, so only the id tells the server which row to drop.
    @Test
    public void serverIndexOfSeparatesTwoSongsWithOneTitle() {
        Child otherAlpha = song("4", "Alpha");
        List<Child> serverOrder = Arrays.asList(BRAVO, ALPHA, CHARLIE, otherAlpha);
        assertEquals(3, PlaylistPageViewModel.serverIndexOf(serverOrder, otherAlpha));
        assertEquals(1, PlaylistPageViewModel.serverIndexOf(serverOrder, ALPHA));
    }

    // A caller that gets -1 refuses the remove instead of sending a position it cannot trust.
    @Test
    public void serverIndexOfRefusesWhatItCannotPlace() {
        assertEquals(-1, PlaylistPageViewModel.serverIndexOf(SERVER_ORDER, song("9", "Delta")));
        assertEquals(-1, PlaylistPageViewModel.serverIndexOf(null, ALPHA));
        assertEquals(-1, PlaylistPageViewModel.serverIndexOf(SERVER_ORDER, null));
        assertEquals(-1, PlaylistPageViewModel.serverIndexOf(Collections.emptyList(), ALPHA));
    }
}
