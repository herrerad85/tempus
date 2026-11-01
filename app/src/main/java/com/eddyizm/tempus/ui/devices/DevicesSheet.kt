package com.eddyizm.tempus.ui.devices

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.AppTypography
import com.eddyizm.tempus.ui.queue.rememberAppColorScheme
import kotlinx.coroutines.launch

object DevicesSheetHost {
    private const val TAG = "devices_sheet"

    @JvmStatic fun isOffered(context: Context): Boolean = deviceSources(context).any { it.isOffered() }

    @JvmStatic fun show(activity: FragmentActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<View>(TAG) != null) return
        val view = ComposeView(activity).apply { tag = TAG }
        view.setContent { DevicesSheet(activity) { root.post { root.removeView(view) } } }
        root.addView(view)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(activity: FragmentActivity, onDismiss: () -> Unit) {
    val sources = remember { deviceSources(activity) }
    val current = rememberCurrentDevice(activity, sources)
    var closed by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val sheet = remember {
        DevicesSheetScope(
            activity = activity,
            sources = sources,
            close = { closed = true; scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            closeNow = { closed = true; onDismiss() },
            isClosed = { closed },
        )
    }

    val colorScheme = rememberAppColorScheme()
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography) {
        ModalBottomSheet(
            onDismissRequest = sheet.closeNow,
            sheetState = sheetState,
            containerColor = colorScheme.surfaceContainerLow,
            contentColor = colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.devices_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                val phoneSelected = !current.kind.playsElsewhere
                DeviceRow(
                    kind = if (phoneSelected) current.kind else DeviceKind.THIS_DEVICE,
                    name = stringResource(R.string.lan_this_device),
                    description = null,
                    selected = phoneSelected,
                ) {
                    sheet.stopAllExcept(null)
                    sheet.close()
                }

                sources.forEach { source ->
                    if (remember(current) { source.isOffered() }) source.Section(sheet)
                }
            }
        }
    }
}

@Composable
private fun rememberCurrentDevice(context: Context, sources: List<DeviceSource>): ActiveDevice {
    var current by remember { mutableStateOf(currentDevice(context, sources)) }
    DisposableEffect(sources) {
        val stop = watchCurrentDevice(context, sources) { current = it }
        current = currentDevice(context, sources)
        onDispose { stop() }
    }
    return current
}
