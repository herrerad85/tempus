package com.eddyizm.tempus.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eddyizm.tempus.util.FavoriteRegistry.Kind;

import org.junit.Before;
import org.junit.Test;

public class FavoriteRegistryTest {

    @Before
    public void reset() {
        FavoriteRegistry.clear();
    }

    @Test
    public void untouchedItemFollowsTheServer() {
        assertTrue(FavoriteRegistry.resolve(Kind.ALBUM, "1", true));
        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "1", false));
    }

    @Test
    public void starringWinsOverAServerThatDoesNotSayStarred() {
        FavoriteRegistry.set(Kind.ALBUM, "1", true);

        assertTrue(FavoriteRegistry.resolve(Kind.ALBUM, "1", false));
    }

    @Test
    public void unstarringWinsOverAServerStillSayingStarred() {
        FavoriteRegistry.set(Kind.ALBUM, "1", false);

        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "1", true));
    }

    @Test
    public void onlyTheItemThatWasTouchedIsAffected() {
        FavoriteRegistry.set(Kind.ALBUM, "1", true);

        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "2", false));
    }

    @Test
    public void theSameIdUnderADifferentKindIsADifferentItem() {
        FavoriteRegistry.set(Kind.SONG, "1234", true);

        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "1234", false));
        assertFalse(FavoriteRegistry.resolve(Kind.ARTIST, "1234", false));
        assertTrue(FavoriteRegistry.resolve(Kind.SONG, "1234", false));
    }

    @Test
    public void aNullIdFallsBackToTheServer() {
        FavoriteRegistry.set(Kind.SONG, null, true);

        assertFalse(FavoriteRegistry.resolve(Kind.SONG, null, false));
        assertTrue(FavoriteRegistry.resolve(Kind.SONG, null, true));
    }

    @Test
    public void clearingForgetsEverything() {
        FavoriteRegistry.set(Kind.ALBUM, "1", true);
        FavoriteRegistry.clear();

        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "1", false));
    }
}
