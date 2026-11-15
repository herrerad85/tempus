package com.eddyizm.tempus.util;

import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.mediarouter.media.MediaRouteSelector;

import com.eddyizm.tempus.upnp.UpnpRouteProvider;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

public class Flavors {
    @SuppressWarnings("deprecation")
    public static void initializeCastContext(Context context) {
        if (isCastAvailable(context))
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context));
    }

    public static boolean isCastAvailable(Context context) {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
    }

    public static MediaRouteSelector routeSelector() {
        MediaRouteSelector.Builder builder = new MediaRouteSelector.Builder()
                .addControlCategory(UpnpRouteProvider.CATEGORY_UPNP);
        CastContext cast = CastContext.getSharedInstance();
        if (cast != null && cast.getMergedSelector() != null) builder.addSelector(cast.getMergedSelector());
        return builder.build();
    }
}
