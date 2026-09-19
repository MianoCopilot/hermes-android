package com.hermesandroid.bridge.hermes

import android.content.Context
import android.content.Intent

object TermuxGatewayStarter {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private const val PROOT_DISTRO = "/data/data/com.termux/files/usr/bin/proot-distro"

    fun startLocalGateway(context: Context): Boolean {
        return runCatching {
            val command = "nohup hermes serve --host 127.0.0.1 --port 9119 --skip-build > ~/.hermes/native-app-serve.log 2>&1 &"
            val intent = Intent(RUN_COMMAND_ACTION).apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(RUN_COMMAND_PATH, PROOT_DISTRO)
                putExtra(
                    RUN_COMMAND_ARGUMENTS,
                    arrayOf("login", "ubuntu", "--", "bash", "-lc", command)
                )
                putExtra(RUN_COMMAND_BACKGROUND, true)
            }
            context.startService(intent)
            true
        }.getOrDefault(false)
    }
}
