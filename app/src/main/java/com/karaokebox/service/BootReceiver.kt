package com.karaokebox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karaokebox.ServiceLocator
import com.karaokebox.ui.PlayerActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ServiceLocator.settings.autoStart) {
            context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
