package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.log.Log
import java.io.DataOutputStream
import java.io.IOException

object Root {
    fun exec(vararg commands: String): Boolean {
        var process: Process? = null
        var os: DataOutputStream? = null
        try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            for (command in commands) {
                os.writeBytes(command + "\n")
            }
            os.writeBytes("exit\n")
            os.flush()
            return process.waitFor() == 0
        } catch (e: IOException) {
            Log.e("Root exec error: ${e.message}", e)
            return false
        } finally {
            os?.close()
            process?.destroy()
        }
    }
}
