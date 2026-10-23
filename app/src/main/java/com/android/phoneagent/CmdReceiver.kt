package com.android.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 仅供 adb 自测：am broadcast -a com.android.phoneagent.CMD --es cmd connect */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Agent.init(context)
        when (intent.getStringExtra("cmd")) {
            "connect" -> Agent.connect()
        }
    }
}
