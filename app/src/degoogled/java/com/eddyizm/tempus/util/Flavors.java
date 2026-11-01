package com.eddyizm.tempus.util;

import android.content.Context;

import androidx.mediarouter.media.MediaRouteSelector;

import com.eddyizm.tempus.upnp.UpnpRouteProvider;

public class Flavors {
    public static void initializeCastContext(Context context) {

    }

    public static boolean isCastAvailable(Context context) {
        return false;
    }

    public static MediaRouteSelector routeSelector() {
        return new MediaRouteSelector.Builder()
                .addControlCategory(UpnpRouteProvider.CATEGORY_UPNP)
                .build();
    }
}
