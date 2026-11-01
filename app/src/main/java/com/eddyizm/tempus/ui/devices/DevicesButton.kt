package com.eddyizm.tempus.ui.devices

import android.content.Context
import android.content.res.ColorStateList
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.eddyizm.tempus.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import java.util.function.BiConsumer

object DevicesButton {
    @JvmStatic fun watch(owner: LifecycleOwner, context: Context, onChange: Runnable) {
        val stop = watchCurrentDevice(context, deviceSources(context)) { onChange.run() }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = stop()
        })
    }

    @JvmStatic fun show(button: ImageButton, label: TextView, setText: BiConsumer<TextView, CharSequence>) {
        val context = button.context
        val device = currentDevice(context, deviceSources(context))
        button.setImageResource(device.kind.icon)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(tint(context, device.kind)))
        ViewCompat.setStateDescription(button, description(context, device))
        setText.accept(label, device.name.orEmpty())
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        label.visibility = if (device.name.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        label.isSelected = true
    }

    @JvmStatic fun show(item: MenuItem, context: Context) {
        item.isVisible = DevicesSheetHost.isOffered(context)
        val device = currentDevice(context, deviceSources(context))
        val color = if (device.kind.playsElsewhere) tint(context, device.kind)
            else ContextCompat.getColor(context, R.color.titleTextColor)
        MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(color))
        MenuItemCompat.setContentDescription(item, "${item.title}, ${description(context, device)}")
    }

    private fun tint(context: Context, kind: DeviceKind): Int = MaterialColors.getColor(
        context,
        if (kind.playsElsewhere) MaterialR.attr.colorPrimary else MaterialR.attr.colorOnSurface,
        DevicesButton::class.java.simpleName
    )

    private fun description(context: Context, device: ActiveDevice): String =
        device.name?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.lan_operating_on, it) }
            ?: context.getString(R.string.devices_playing_on_this_device)
}
