package com.eddyizm.tempus.ui.devices

import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import com.eddyizm.tempus.R
import com.eddyizm.tempus.lan.LanDiscovery
import com.eddyizm.tempus.lan.LanPlaybackActivity
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.lan.LanRemoteState
import com.eddyizm.tempus.util.Preferences

internal object TempusPlayers : DeviceSource {
    override fun isOffered(): Boolean = Preferences.isLanRemoteEnabled() || LanRemoteSession.isActive()

    override fun active(): ActiveDevice? =
        LanRemoteSession.current().takeIf { it.active }?.let { ActiveDevice(DeviceKind.TEMPUS_PLAYER, tempusPlayerName(it.name)) }

    override fun watch(onChange: () -> Unit): () -> Unit {
        val observer = Observer<LanRemoteState> { onChange() }
        LanRemoteSession.state().observeForever(observer)
        return { LanRemoteSession.state().removeObserver(observer) }
    }

    override fun stop() {
        if (LanRemoteSession.isActive()) LanRemoteSession.disconnect()
    }

    @Composable
    override fun Section(sheet: DevicesSheetScope) {
        val activity = sheet.activity
        val lan by LanRemoteSession.state().observeAsState(LanRemoteSession.current())
        val (players, playersError) = rememberTempusPlayers(activity)
        var connecting by remember { mutableStateOf<String?>(null) }
        var failed by remember { mutableStateOf<String?>(null) }

        SectionLabel(stringResource(R.string.devices_tempus))
        val shown = tempusRows(players, lan)
        if (playersError != null) EmptyRow(playersError) else if (shown.isEmpty()) EmptyRow()
        shown.forEach { (name, info) ->
            val active = lan.active && isConnected(name)
            val description = when {
                connecting == name -> stringResource(R.string.lan_connecting)
                failed == name -> stringResource(R.string.lan_connection_error)
                active && lan.error != 0 -> stringResource(lan.error)
                active && !lan.connected -> stringResource(R.string.lan_connecting)
                else -> null
            }
            DeviceRow(DeviceKind.TEMPUS_PLAYER, tempusPlayerName(name), description, selected = active) {
                when {
                    connecting != null -> Unit
                    active && lan.error == 0 -> sheet.close()
                    info == null -> { LanRemoteSession.reconnect(activity); sheet.close() }
                    else -> {
                        connecting = name
                        failed = null
                        LanDiscovery.resolve(activity, info,
                            onResolved = { resolved ->
                                connecting = null
                                if (!sheet.isClosed() && !activity.isFinishing && !activity.isDestroyed) {
                                    LanRemoteSession.connect(activity, resolved)
                                    sheet.close()
                                }
                            },
                            onFailed = { connecting = null; failed = name })
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                sheet.closeNow()
                activity.startActivity(Intent(activity, LanPlaybackActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
        ) { Text(stringResource(R.string.devices_pair_settings)) }
    }
}

private data class TempusRow(val name: String, val info: NsdServiceInfo?)

private fun tempusRows(found: List<NsdServiceInfo>, lan: LanRemoteState): List<TempusRow> {
    val rows = found.map { TempusRow(it.serviceName, it) }
    if (!lan.active || rows.any { isConnected(it.name) }) return rows
    return listOf(TempusRow(LanRemoteSession.targetName() ?: lan.name, null)) + rows
}

private fun isConnected(name: String) = name == LanRemoteSession.targetName()

@Composable
private fun rememberTempusPlayers(activity: FragmentActivity): Pair<List<NsdServiceInfo>, Int?> {
    var players by remember { mutableStateOf<List<NsdServiceInfo>>(emptyList()) }
    var error by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(activity) {
        if (Build.VERSION.SDK_INT >= 37 &&
            activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            error = R.string.lan_permission
            return@DisposableEffect onDispose { }
        }
        val stop = LanDiscovery.discover(activity,
            onChange = { players = it },
            onFailed = { error = R.string.lan_discovery_error })
        onDispose { stop() }
    }
    return players to error
}
