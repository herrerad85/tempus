package com.eddyizm.tempus.repository;

import androidx.annotation.NonNull;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.FavoriteDao;
import com.eddyizm.tempus.interfaces.StarCallback;
import com.eddyizm.tempus.model.Favorite;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.util.FavoriteRegistry;
import com.eddyizm.tempus.subsonic.models.ResponseStatus;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FavoriteRepository {
    private final FavoriteDao favoriteDao = AppDatabase.getInstance().favoriteDao();

    // Exactly one of the three ids is set on any call, so the caller's intent is recorded against
    // whichever one that is, under the kind that id belongs to.
    private static void remember(String id, String albumId, String artistId, boolean isStarred) {
        FavoriteRegistry.set(FavoriteRegistry.Kind.SONG, id, isStarred);
        FavoriteRegistry.set(FavoriteRegistry.Kind.ALBUM, albumId, isStarred);
        FavoriteRegistry.set(FavoriteRegistry.Kind.ARTIST, artistId, isStarred);
    }

    private static void forget(String id, String albumId, String artistId) {
        FavoriteRegistry.forget(FavoriteRegistry.Kind.SONG, id);
        FavoriteRegistry.forget(FavoriteRegistry.Kind.ALBUM, albumId);
        FavoriteRegistry.forget(FavoriteRegistry.Kind.ARTIST, artistId);
    }

    // Subsonic reports a refusal as a 200 whose body says failed, so the status line alone cannot
    // tell a stored star from a rejected one.
    private static boolean serverRefused(Response<ApiResponse> response) {
        return response.body() != null
                && response.body().getSubsonicResponse() != null
                && ResponseStatus.FAILED.equals(response.body().getSubsonicResponse().getStatus());
    }

    public void star(String id, String albumId, String artistId, StarCallback starCallback) {
        remember(id, albumId, artistId, true);

        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .star(id, albumId, artistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && !serverRefused(response)) {
                            starCallback.onSuccess();
                        } else if (serverRefused(response)) {
                            forget(id, albumId, artistId);
                            starCallback.onRefused();
                        } else {
                            starCallback.onError();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        starCallback.onError();
                    }
                });
    }

    public void unstar(String id, String albumId, String artistId, StarCallback starCallback) {
        remember(id, albumId, artistId, false);

        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .unstar(id, albumId, artistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && !serverRefused(response)) {
                            starCallback.onSuccess();
                        } else if (serverRefused(response)) {
                            forget(id, albumId, artistId);
                            starCallback.onRefused();
                        } else {
                            starCallback.onError();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        starCallback.onError();
                    }
                });
    }

    public List<Favorite> getFavorites() {
        List<Favorite> favorites = new ArrayList<>();

        GetAllThreadSafe getAllThreadSafe = new GetAllThreadSafe(favoriteDao);
        Thread thread = new Thread(getAllThreadSafe);
        thread.start();

        try {
            thread.join();
            favorites = getAllThreadSafe.getFavorites();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return favorites;
    }

    private static class GetAllThreadSafe implements Runnable {
        private final FavoriteDao favoriteDao;
        private List<Favorite> favorites = new ArrayList<>();

        public GetAllThreadSafe(FavoriteDao favoriteDao) {
            this.favoriteDao = favoriteDao;
        }

        @Override
        public void run() {
            favorites = favoriteDao.getAll();
        }

        public List<Favorite> getFavorites() {
            return favorites;
        }
    }

    public void starLater(String id, String albumId, String artistId, boolean toStar) {
        remember(id, albumId, artistId, toStar);

        InsertThreadSafe insert = new InsertThreadSafe(favoriteDao, new Favorite(System.currentTimeMillis(), id, albumId, artistId, toStar));
        Thread thread = new Thread(insert);
        thread.start();
    }

    private static class InsertThreadSafe implements Runnable {
        private final FavoriteDao favoriteDao;
        private final Favorite favorite;

        public InsertThreadSafe(FavoriteDao favoriteDao, Favorite favorite) {
            this.favoriteDao = favoriteDao;
            this.favorite = favorite;
        }

        @Override
        public void run() {
            favoriteDao.insert(favorite);
        }
    }

    public void delete(Favorite favorite) {
        DeleteThreadSafe delete = new DeleteThreadSafe(favoriteDao, favorite);
        Thread thread = new Thread(delete);
        thread.start();
    }

    private static class DeleteThreadSafe implements Runnable {
        private final FavoriteDao favoriteDao;
        private final Favorite favorite;

        public DeleteThreadSafe(FavoriteDao favoriteDao, Favorite favorite) {
            this.favoriteDao = favoriteDao;
            this.favorite = favorite;
        }

        @Override
        public void run() {
            favoriteDao.delete(favorite);
        }
    }
}
