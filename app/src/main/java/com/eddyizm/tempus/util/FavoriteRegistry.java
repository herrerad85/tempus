package com.eddyizm.tempus.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FavoriteRegistry {
    public enum Kind {SONG, ALBUM, ARTIST}

    private static final Map<String, Boolean> starred = new ConcurrentHashMap<>();

    private FavoriteRegistry() {
    }

    public static void set(Kind kind, String id, boolean isStarred) {
        if (id != null) starred.put(key(kind, id), isStarred);
    }

    public static boolean resolve(Kind kind, String id, boolean serverSaysStarred) {
        Boolean ours = id != null ? starred.get(key(kind, id)) : null;
        return ours != null ? ours : serverSaysStarred;
    }

    public static void forget(Kind kind, String id) {
        if (id != null) starred.remove(key(kind, id));
    }

    public static void clear() {
        starred.clear();
    }

    private static String key(Kind kind, String id) {
        return kind.name() + ":" + id;
    }
}
