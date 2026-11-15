package com.eddyizm.tempus.ui.devices

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.eddyizm.tempus.R
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.upnp.UpnpRouteProvider
import com.eddyizm.tempus.util.Flavors
import com.eddyizm.tempus.util.Preferences

internal class MediaRouteDevices(private val context: Context) : DeviceSource {
    private val router = MediaRouter.getInstance(context)

    override fun isOffered(): Boolean = Preferences.isUpnpEnabled() || Flavors.isCastAvailable(context)

    override fun active(): ActiveDevice? =
        router.selectedRoute.takeUnless { it.playsOnThisDevice || LanRemoteSession.isActive() }?.let { ActiveDevice(it.kind(), it.name) }

    override fun watch(onChange: () -> Unit): () -> Unit {
        val callback = routeChanges(onChange)
        router.addCallback(MediaRouteSelector.EMPTY, callback, MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS)
        return { router.removeCallback(callback) }
    }

    override fun stop() {
        if (!router.selectedRoute.playsOnThisDevice) router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
    }

    @Composable
    override fun Section(sheet: DevicesSheetScope) {
        val rows = rememberRoutes()
        val search = rememberUpnpSearch()
        SectionLabel(stringResource(R.string.devices_network))
        rows.forEach { row ->
            DeviceRow(row.kind, row.name, row.description, selected = row.selected) {
                sheet.stopAllExcept(this)
                if (!row.selected) row.route.select()
                sheet.close()
            }
        }
        when {
            search == UpnpSearch.RUNNING -> SearchingDots()
            rows.isEmpty() -> EmptyRow()
            search == UpnpSearch.DONE -> SearchingDots(visible = false)
        }
    }

    @Composable
    private fun rememberRoutes(): List<RouteRow> {
        val selector = remember { Flavors.routeSelector() }
        var rows by remember { mutableStateOf(emptyList<RouteRow>()) }
        DisposableEffect(router) {
            fun refresh() {
                rows = router.routes
                    .filter { !it.playsOnThisDevice && it.isEnabled && it.matchesSelector(selector) }
                    .map { RouteRow(it, it.kind(), it.name, it.description.takeIf { _ -> it.isUpnp }, it.isSelected) }
            }
            val callback = routeChanges(::refresh)
            router.addCallback(selector, callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            refresh()
            onDispose { router.removeCallback(callback) }
        }
        return rows
    }
}

private enum class UpnpSearch { NONE, RUNNING, DONE }

@Composable
private fun rememberUpnpSearch(): UpnpSearch {
    val atOpen = remember { UpnpRouteProvider.roundsLanded.value }
    val landed by UpnpRouteProvider.roundsLanded.collectAsState()
    return when {
        atOpen == null -> UpnpSearch.NONE
        landed == atOpen -> UpnpSearch.RUNNING
        else -> UpnpSearch.DONE
    }
}

private data class RouteRow(
    val route: MediaRouter.RouteInfo,
    val kind: DeviceKind,
    val name: String,
    val description: String?,
    val selected: Boolean,
)

private val MediaRouter.RouteInfo.isUpnp: Boolean
    get() = supportsControlCategory(UpnpRouteProvider.CATEGORY_UPNP)

private fun MediaRouter.RouteInfo.kind(): DeviceKind = routeKind(isUpnp, deviceType)

private fun routeChanges(onChange: () -> Unit) = object : MediaRouter.Callback() {
    override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
    override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
    override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
    override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = onChange()
    override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = onChange()
}
