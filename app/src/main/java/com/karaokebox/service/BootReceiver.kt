package com.karaokebox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karaokebox.ServiceLocator
import com.karaokebox.ui.PlayerActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val open = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ServiceLocator.settings.autoStart
            // Vừa cài đè bản mới do chính app yêu cầu: Android không tự mở lại app nên mở giúp
            Intent.ACTION_MY_PACKAGE_REPLACED -> ServiceLocator.settings.relaunchAfterUpdate.also {
                ServiceLocator.settings.relaunchAfterUpdate = false
            }
            else -> false
        }
        if (open) {
            context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
