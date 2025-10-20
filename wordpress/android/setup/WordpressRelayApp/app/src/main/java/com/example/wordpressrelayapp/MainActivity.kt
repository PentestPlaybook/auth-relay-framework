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
                        val rootOutput =
                            BufferedReader(InputStreamReader(rootProc.inputStream)).use { it.readText() }
                        val rootError =
                            BufferedReader(InputStreamReader(rootProc.errorStream)).use { it.readText() }
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
                            .exec(arrayOf("su", "-c", "grep com.termux /data/system/packages.list"))
                        val listOutput =
                            BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
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
                        val x11Output =
                            BufferedReader(InputStreamReader(x11Proc.inputStream)).use { it.readText() }
                        val x11Exit = x11Proc.waitFor()

                        if (x11Exit != 0 || x11Output.isBlank()) {
                            diagnostics.appendLine("3. ❌ Termux:X11 not found\n")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }

                        diagnostics.appendLine("4. ✅ Termux:X11 installed\n")

                        // Step 4: Calculate standard Android groups
                        diagnostics.appendLine("4. Calculating standard Android groups...")

                        val appId = termuxAppId - 10000  // Extract app number (e.g., 10321 -> 321)

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

                    val checkPipeline =
                        "cat $checkScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                    val checkProc =
                        Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", checkPipeline))
                    val checkOutput =
                        BufferedReader(InputStreamReader(checkProc.inputStream)).use { it.readText() }
                    val checkError =
                        BufferedReader(InputStreamReader(checkProc.errorStream)).use { it.readText() }
                    checkProc.waitFor()

                    val sshKeyExists = checkOutput.contains("SSH_KEY:EXISTS")
                    val pythonScriptExists = checkOutput.contains("SCRIPT:EXISTS")

                    if (sshKeyExists && pythonScriptExists) {
                        runOnUiThread {
                            textView.text =
                                "✅ Private SSH Key and Relay Script Exist in Current Directory\n\nReady to proceed!"
                        }
                        return@Thread
                    }

                    val statusMessage = StringBuilder()
                    statusMessage.appendLine("=== Setting Up Prerequisites ===\n")

                    var needToTransferKey = false

                    if (!sshKeyExists) {
                        statusMessage.appendLine("📝 Generating SSH key pair...")

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
                        keygenLines.add("ssh-keygen -t ed25519 -f .ssh/pineapple -N ''")
                        keygenLines.add("echo 'KEYGEN:SUCCESS'")

                        val keygenScript = keygenLines.joinToString("\n")
                        val keygenScriptPath = "/sdcard/generate_key.sh"
                        writeFileAsRoot(keygenScriptPath, keygenScript)
                        execAsRoot("chmod 644 $keygenScriptPath")

                        val keygenPipeline =
                            "cat $keygenScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                        val keygenProc =
                            Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", keygenPipeline))
                        val keygenOutput =
                            BufferedReader(InputStreamReader(keygenProc.inputStream)).use { it.readText() }
                        val keygenError =
                            BufferedReader(InputStreamReader(keygenProc.errorStream)).use { it.readText() }
                        keygenProc.waitFor()

                        if (keygenOutput.contains("KEYGEN:SUCCESS")) {
                            statusMessage.appendLine("✅ SSH key pair generated!\n")
                            needToTransferKey = true
                        } else {
                            statusMessage.appendLine("❌ Failed to generate SSH key\n")
                            if (keygenOutput.isNotBlank()) statusMessage.appendLine("Output: $keygenOutput\n")
                            if (keygenError.isNotBlank()) statusMessage.appendLine("Error: $keygenError\n")
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
                        downloadLines.add("wget -q https://raw.githubusercontent.com/PentestPlaybook/auth-relay-framework/refs/heads/main/wordpress/android/execution/scripts/wordpress-relay.py")
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

                        val downloadPipeline =
                            "cat $downloadScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                        val downloadProc =
                            Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", downloadPipeline))
                        val downloadOutput =
                            BufferedReader(InputStreamReader(downloadProc.inputStream)).use { it.readText() }
                        val downloadError =
                            BufferedReader(InputStreamReader(downloadProc.errorStream)).use { it.readText() }
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

                    if (needToTransferKey) {
                        statusMessage.appendLine("=".repeat(50))
                        statusMessage.appendLine("⚠️  IMPORTANT: SSH KEY TRANSFER REQUIRED")
                        statusMessage.appendLine("=".repeat(50))
                        statusMessage.appendLine("\nYou must transfer the public key to the Pineapple.")
                        statusMessage.appendLine("\nOpen Termux and run these commands:\n")
                        statusMessage.appendLine("scp ~/.ssh/pineapple.pub root@172.16.42.1:/root/.ssh/authorized_keys")
                        statusMessage.appendLine("\nAfter completing these steps, press Button 1 again.")
                    } else if (sshKeyExists && pythonScriptExists) {
                        statusMessage.appendLine("\n✅ All prerequisites ready!")
                        statusMessage.appendLine("You can now proceed with buttons 2, 3, and 4.")
                    } else {
                        statusMessage.appendLine("\n⚠️ Some prerequisites are missing or failed to setup.")
                        statusMessage.appendLine("Please check the error messages above.")
                    }

                    runOnUiThread {
                        textView.text = statusMessage.toString()
                    }

                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error: ${e.message}\n${e.stackTraceToString()}" }
                }
            }.start()
        }

        button3.setOnClickListener {
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

                    val output =
                        BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                    val error =
                        BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
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

        // =========================
        // BUTTON 4 - USES nc FOR PORT CHECKING
        // =========================
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

                    // Read the last URL from last-url.txt in Termux home directory
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
                        
                        // Pre-load the last URL if it exists
                        if (lastUrl.isNotEmpty()) {
                            input.setText(lastUrl)
                            textView.text = "Last URL loaded: $lastUrl"
                        } else {
                            textView.text = "No previous URL found. Enter a new one."
                        }

                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Enter WordPress Domain")
                            .setMessage("Enter the full WordPress domain URL:")
                            .setView(input)
                            .setPositiveButton("Start") { dialog, _ ->
                                val domain = input.text.toString().trim()

                                if (domain.isEmpty()) {
                                    textView.text = "❌ Domain cannot be empty"
                                    dialog.dismiss()
                                    return@setPositiveButton
                                }

                                if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                                    textView.text = "❌ Domain must start with http:// or https://"
                                    dialog.dismiss()
                                    return@setPositiveButton
                                }

                                textView.text = "Saving URL and creating script…"

                                Thread {
                                    try {
                                        // SAVE THE URL TO last-url.txt BEFORE PROCEEDING
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

                                        // Build the script using nc (netcat) for PORT CHECKING
                                        val scriptContent = """
                                #!$TERMUX_BASH
                                set -e

                                export HOME=$TERMUX_HOME
                                export PREFIX=/data/data/com.termux/files/usr
                                export PATH="${'$'}PREFIX/bin:${'$'}PATH"

                                # Critical: Set TMPDIR before anything else
                                export TMPDIR="${'$'}HOME/tmp"

                                # Use TCP connection instead of Unix socket
                                export DISPLAY=:0

                                # Prevent headless mode
                                unset MOZ_HEADLESS
                                export MOZ_CRASHREPORTER_DISABLE=1

                                mkdir -p "${'$'}TMPDIR"
                                cd "${'$'}HOME"

                                echo "Button 4 pressed at ${'$'}(date)" > button4_test.log || true

                                # Aggressive cleanup of prior instances
                                echo "Cleaning up previous processes..." > cleanup.log
                                pkill -9 -f python.*wordpress-relay || true
                                pkill -9 -f geckodriver || true
                                pkill -9 -f firefox || true
                                pkill -9 -f termux-x11 || true
                                pkill -f 'ssh.*172.16.42.1' || true

                                # Wait for ports to be released
                                echo "Waiting for ports to be released..." >> cleanup.log
                                for i in {1..10}; do
                                  # Use nc to check if ports are in use
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

                                # Start the X11 server with TCP listening enabled
                                echo "Starting X11 server with TCP..." > x11_output.log
                                echo "DISPLAY=${'$'}DISPLAY" >> x11_output.log
                                termux-x11 :0 -ac -listen tcp >> x11_output.log 2>&1 &
                                X11_PID=${'$'}!
                                echo "X11 started with PID: ${'$'}X11_PID" >> x11_output.log

                                # Wait for X11 to be listening on TCP port 6000 using nc
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

                                # Test Firefox connection using TCP
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

                                # Log environment
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

                                # Start geckodriver
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

                                # Start Python relay with user-provided domain
                                echo "Starting Python relay with domain: $domain..." >> x11_output.log
                                python "${'$'}HOME/wordpress-relay.py" --domain $domain --port 8080 > python_output.log 2>&1 &
                                PYTHON_PID=${'$'}!

                                echo "=== All processes started ===" >> x11_output.log
                                echo "X11: ${'$'}X11_PID (TCP port 6000)" >> x11_output.log
                                echo "Geckodriver: ${'$'}GECKO_PID (port 4444)" >> x11_output.log
                                echo "Python: ${'$'}PYTHON_PID (port 8080, domain: $domain)" >> x11_output.log

                                # NEW: Comprehensive port checking using nc before SSH tunnel setup
                                echo "" >> x11_output.log
                                echo "=== Waiting for all required ports to be listening ===" >> x11_output.log
                                echo 'Checking required ports using nc...' > ssh_setup.log

                                # Required ports: 6000 (X11), 4444 (geckodriver), 8080 (python relay)
                                ALL_PORTS_READY=0
                                for attempt in {1..60}; do
                                  PORT_6000_OK=0
                                  PORT_4444_OK=0
                                  PORT_8080_OK=0
                                  
                                  # Check port 6000 (X11) using nc
                                  if nc -z 127.0.0.1 6000 2>/dev/null; then
                                    PORT_6000_OK=1
                                  fi
                                  
                                  # Check port 4444 (geckodriver) using nc
                                  if nc -z 127.0.0.1 4444 2>/dev/null; then
                                    PORT_4444_OK=1
                                  fi
                                  
                                  # Check port 8080 (python relay) using nc
                                  if nc -z 127.0.0.1 8080 2>/dev/null; then
                                    PORT_8080_OK=1
                                  fi
                                  
                                  # Log current port status
                                  echo "Attempt ${'$'}attempt/60:" >> ssh_setup.log
                                  echo "  Port 6000 (X11):        ${'$'}([ "${'$'}PORT_6000_OK" -eq 1 ] && echo '✓' || echo '✗')" >> ssh_setup.log
                                  echo "  Port 4444 (geckodriver): ${'$'}([ "${'$'}PORT_4444_OK" -eq 1 ] && echo '✓' || echo '✗')" >> ssh_setup.log
                                  echo "  Port 8080 (python):      ${'$'}([ "${'$'}PORT_8080_OK" -eq 1 ] && echo '✓' || echo '✗')" >> ssh_setup.log
                                  
                                  # Check if all ports are ready
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

                                # All ports confirmed listening, now proceed with SSH setup
                                echo "" >> ssh_setup.log
                                echo "=== SSH Port Forward Setup ===" >> ssh_setup.log
                                echo "" >> x11_output.log
                                echo "=== SSH Port Forward Setup ===" >> x11_output.log

                                # Kill any existing SSH tunnels
                                echo "Cleaning up old SSH tunnels..." >> ssh_setup.log
                                pkill -f 'ssh.*172.16.42.1' || true
                                sleep 2

                                # NETWORK CONNECTIVITY CHECK BEFORE SSH
                                echo "Checking network connectivity to Pineapple..." >> ssh_setup.log
                                if timeout 10 ping -c 2 172.16.42.1 > /dev/null 2>&1; then
                                  echo "✓ Network connectivity confirmed" >> ssh_setup.log
                                else
                                  echo "✗ FATAL: Cannot reach Pineapple at 172.16.42.1" >> ssh_setup.log
                                  echo "SSH_SETUP_FAILED:NO_NETWORK" >> ssh_setup.log
                                  exit 2
                                fi

                                # Set up SSH port forward 1: Pineapple:9999 → Android:8080
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

                                # Set up SSH port forward 2: Android:9998 → Pineapple:80
                                echo "Setting up Forward 2: Android:9998 → Pineapple:80" >> ssh_setup.log
                                nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -o ConnectTimeout=10 -N -L 9998:172.16.42.1:80 root@172.16.42.1 > ssh_forward2.log 2>&1 &
                                FORWARD2_PID=${'$'}!
                                echo "Forward 2 PID: ${'$'}FORWARD2_PID" >> ssh_setup.log
                                echo "Forward 2 started with PID: ${'$'}FORWARD2_PID" >> x11_output.log
                                sleep 3

                                # Verify SSH tunnels are running
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

                                        // Do NOT switch to X11 yet. Execute, gather logs, then conditionally launch.
                                        try {
                                            val termuxLaunch = packageManager.getLaunchIntentForPackage("com.termux")
                                                ?: Intent().apply {
                                                    setClassName("com.termux", "com.termux.app.TermuxActivity")
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                            startActivity(termuxLaunch)
                                            // Quickly return to our app
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

                                        val pipeline =
                                            "cat $SD_SCRIPT | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))

                                        val stdout =
                                            BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                                        val stderr =
                                            BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                                        val exit = proc.waitFor()

                                        // Read logs from Termux after execution
                                        Thread.sleep(1500)

                                        val readLogsScript = mutableListOf<String>()
                                        readLogsScript.add("#!/data/data/com.termux/files/usr/bin/bash")
                                        readLogsScript.add("set -e")
                                        readLogsScript.add("")
                                        readLogsScript.add("export HOME=/data/data/com.termux/files/home")
                                        readLogsScript.add("export PREFIX=/data/data/com.termux/files/usr")
                                        readLogsScript.add("export PATH=\"${'$'}PREFIX/bin:${'$'}PATH\"")
                                        readLogsScript.add("")
                                        readLogsScript.add("cd \"${'$'}HOME\"")
                                        readLogsScript.add("")
                                        readLogsScript.add("echo '=== X11_OUTPUT_LOG ==='")
                                        readLogsScript.add("cat x11_output.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== CLEANUP_LOG ==='")
                                        readLogsScript.add("cat cleanup.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== ENV_CHECK_LOG ==='")
                                        readLogsScript.add("cat env_check.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== GECKODRIVER_LOG ==='")
                                        readLogsScript.add("tail -200 geckodriver.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== PYTHON_OUTPUT_LOG ==='")
                                        readLogsScript.add("tail -200 python_output.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== SSH_SETUP_LOG ==='")
                                        readLogsScript.add("cat ssh_setup.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== SSH_FORWARD1_LOG ==='")
                                        readLogsScript.add("tail -200 ssh_forward1.log 2>/dev/null || echo 'File not found'")
                                        readLogsScript.add("echo ''")
                                        readLogsScript.add("echo '=== SSH_FORWARD2_LOG ==='")
                                        readLogsScript.add("tail -200 ssh_forward2.log 2>/dev/null || echo 'File not found'")

                                        val readLogsScriptContent = readLogsScript.joinToString("\n")
                                        val readLogsScriptPath = "/sdcard/read_logs.sh"
                                        writeFileAsRoot(readLogsScriptPath, readLogsScriptContent)
                                        execAsRoot("chmod 644 $readLogsScriptPath")

                                        val readPipeline =
                                            "cat $readLogsScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                                        val readProc =
                                            Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", readPipeline))
                                        val allLogs =
                                            BufferedReader(InputStreamReader(readProc.inputStream)).use { it.readText() }
                                        readProc.waitFor()

                                        fun section(after: String, before: String? = null): String {
                                            val a = allLogs.substringAfter(after, "")
                                            return if (before == null) a.trim()
                                            else a.substringBefore(before, "").trim()
                                        }

                                        val x11LogContent =
                                            section("=== X11_OUTPUT_LOG ===", "=== CLEANUP_LOG ===")
                                        val cleanupLogContent =
                                            section("=== CLEANUP_LOG ===", "=== ENV_CHECK_LOG ===")
                                        val envCheckLogContent =
                                            section("=== ENV_CHECK_LOG ===", "=== GECKODRIVER_LOG ===")
                                        val geckodriverLogContent =
                                            section("=== GECKODRIVER_LOG ===", "=== PYTHON_OUTPUT_LOG ===")
                                        val pythonLogContent =
                                            section("=== PYTHON_OUTPUT_LOG ===", "=== SSH_SETUP_LOG ===")
                                        val sshSetupLogContent =
                                            section("=== SSH_SETUP_LOG ===", "=== SSH_FORWARD1_LOG ===")
                                        val sshForward1LogContent =
                                            section("=== SSH_FORWARD1_LOG ===", "=== SSH_FORWARD2_LOG ===")
                                        val sshForward2LogContent =
                                            section("=== SSH_FORWARD2_LOG ===")

                                        val sshOk = sshSetupLogContent.contains("SSH_SETUP_SUCCESS")
                                        val x11Ok = x11LogContent.contains("listening on TCP port 6000") ||
                                                x11LogContent.contains("✓ X11 listening")
                                        val geckoOk = geckodriverLogContent.contains("Listening on 127.0.0.1:4444") ||
                                                x11LogContent.contains("Geckodriver PID:")
                                        val allPortsReady = sshSetupLogContent.contains("All required ports are listening")

                                        val overallSuccess = (exit == 0) && sshOk && x11Ok && geckoOk && allPortsReady

                                        // Show output in the app FIRST
                                        runOnUiThread {
                                            textView.text = buildString {
                                                if (overallSuccess) {
                                                    appendLine("✅ Complete Setup Finished (Button 4)")
                                                } else {
                                                    appendLine("⚠️ Setup finished with issues (Button 4)")
                                                }
                                                appendLine("Domain: $domain")
                                                appendLine("Saved to: $TERMUX_HOME/last-url.txt")
                                                appendLine("Exit: $exit")
                                                appendLine("")
                                                if (!allPortsReady) appendLine("• Required ports: ❌ not all confirmed listening")
                                                else appendLine("• Required ports: ✅ all confirmed listening (via nc)")
                                                if (!sshOk) appendLine("• SSH tunnels: ❌ not established")
                                                else appendLine("• SSH tunnels: ✅ established")
                                                if (!x11Ok) appendLine("• X11: ❌ not confirmed listening on TCP :6000")
                                                else appendLine("• X11: ✅ listening on TCP :6000")
                                                if (!geckoOk) appendLine("• Geckodriver: ❌ not confirmed")
                                                else appendLine("• Geckodriver: ✅ running")
                                                appendLine("")
                                                if (stdout.isNotBlank()) appendLine("--- STDOUT ---\n$stdout\n")
                                                if (stderr.isNotBlank()) appendLine("--- STDERR ---\n$stderr\n")
                                                appendLine("=== X11 & Service Logs ===")
                                                appendLine(x11LogContent.ifBlank { "(no x11_output.log)" })
                                                appendLine("\n=== Cleanup Log ===")
                                                appendLine(cleanupLogContent.ifBlank { "(no cleanup.log)" })
                                                appendLine("\n=== Environment Check ===")
                                                appendLine(envCheckLogContent.ifBlank { "(no env_check.log)" })
                                                appendLine("\n=== Geckodriver Log (last 200 lines) ===")
                                                appendLine(geckodriverLogContent.ifBlank { "(no geckodriver.log)" })
                                                appendLine("\n=== Python Output (last 200 lines) ===")
                                                appendLine(pythonLogContent.ifBlank { "(no python_output.log)" })
                                                appendLine("\n=== SSH Port Forward Setup (ssh_setup.log) ===")
                                                appendLine(sshSetupLogContent.ifBlank { "(no ssh_setup.log)" })
                                                appendLine("\n=== ssh_forward1.log (last 200 lines) ===")
                                                appendLine(sshForward1LogContent.ifBlank { "(no ssh_forward1.log)" })
                                                appendLine("\n=== ssh_forward2.log (last 200 lines) ===")
                                                appendLine(sshForward2LogContent.ifBlank { "(no ssh_forward2.log)" })
                                                appendLine("\nLogs are in $TERMUX_HOME")
                                                when {
                                                    overallSuccess -> appendLine("\n✅ All services and SSH tunnels are up. Preparing to switch to Termux:X11…")
                                                    exit == 2 -> appendLine("\n❌ SSH tunnels failed. Stay in app to review logs.")
                                                    sshSetupLogContent.contains("PORTS_NOT_READY") -> appendLine("\n❌ Required ports did not become ready in time. Stay in app to review logs.")
                                                    else -> appendLine("\n⚠️ Not all checks passed. Stay in app to review logs.")
                                                }
                                            }.trim()
                                        }

                                        // Only after showing output, switch to X11 if successful
                                        if (overallSuccess) {
                                            try {
                                                Handler(Looper.getMainLooper()).postDelayed({
                                                    try {
                                                        val x11Launch = packageManager.getLaunchIntentForPackage("com.termux.x11")
                                                            ?: Intent().apply {
                                                                setClassName("com.termux.x11", "com.termux.x11.MainActivity")
                                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                            }
                                                        startActivity(x11Launch)
                                                    } catch (e: ActivityNotFoundException) {
                                                        runOnUiThread {
                                                            textView.append("\n\n⚠️ Termux:X11 not installed; cannot switch automatically.")
                                                        }
                                                    } catch (e: Exception) {
                                                        runOnUiThread {
                                                            textView.append("\n\n⚠️ Failed to launch Termux:X11: ${e.message}")
                                                        }
                                                    }
                                                }, 900L)
                                            } catch (e: Exception) {
                                                runOnUiThread {
                                                    textView.append("\n\n⚠️ Delayed launch error: ${e.message}")
                                                }
                                            }
                                        }

                                    } catch (e: Exception) {
                                        runOnUiThread { textView.text = "Error: ${e.message}\n${e.stackTraceToString()}" }
                                    }
                                }.start()

                                dialog.dismiss()
                            }
                            .setNegativeButton("Cancel") { dialog, _ ->
                                dialog.cancel()
                            }
                            .show()
                    }

                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error loading last URL: ${e.message}\n${e.stackTraceToString()}" }
                }
            }.start()
        }
    }

    /**
     * Detects Termux UID, GID, and groups by reading packages.list
     * and calculating standard Android supplementary groups.
     */
    private fun detectTermuxIds() {
        Thread {
            try {
                // Get Termux UID from packages.list
                val listProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "grep com.termux /data/system/packages.list"))
                val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
                val listExit = listProc.waitFor()

                if (listExit != 0 || listOutput.isBlank()) {
                    return@Thread
                }

                // Format: com.termux 10321 0 /data/user/0/com.termux ...
                val parts = listOutput.trim().split("\\s+".toRegex())
                if (parts.size < 2) {
                    return@Thread
                }

                val termuxAppId = parts[1].toIntOrNull() ?: return@Thread

                termuxUid = termuxAppId.toString()
                termuxGid = termuxAppId.toString()

                // Calculate standard Android supplementary groups
                // 3003 = inet (network access)
                // 9997 = everybody
                // 20000 + app_id = cache group
                // 50000 + app_id = all group
                val appId = termuxAppId - 10000  // Extract app number (e.g., 10321 -> 321)
                val supplementaryGroups = listOf(
                    "3003",           // inet - network access
                    "9997",           // everybody
                    (20000 + appId).toString(),  // cache group (e.g., 20321)
                    (50000 + appId).toString()   // all group (e.g., 50321)
                )

                termuxGroups = supplementaryGroups.joinToString(" ") { "-G $it" }
            } catch (e: Exception) {
                // Silent failure - will be caught later when trying to use these values
            }
        }.start()
    }

    /**
     * Ensures Termux IDs are detected. If not, attempts detection synchronously.
     * Returns a pair: (success: Boolean, errorMessage: String?)
     */
    private fun ensureTermuxIdsDetected(): Pair<Boolean, String?> {
        if (termuxUid.isNotEmpty() && termuxGid.isNotEmpty() && termuxGroups.isNotEmpty()) {
            return Pair(true, null)
        }

        try {
            // Get Termux UID from packages.list
            val listProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "grep com.termux /data/system/packages.list"))
            val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
            val listError = BufferedReader(InputStreamReader(listProc.errorStream)).use { it.readText() }
            val listExit = listProc.waitFor()

            if (listExit != 0) {
                return Pair(false, "Failed to read packages.list (exit: $listExit)\nError: $listError")
            }

            if (listOutput.isBlank()) {
                return Pair(false, "Termux not found in packages.list. Is Termux installed?")
            }

            // Format: com.termux 10321 0 /data/user/0/com.termux ...
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

            // Calculate standard Android supplementary groups
            // 3003 = inet (network access)
            // 9997 = everybody
            // 20000 + app_id = cache group
            // 50000 + app_id = all group
            val appId = termuxAppId - 10000  // Extract app number (e.g., 10321 -> 321)
            val supplementaryGroups = listOf(
                "3003",           // inet - network access
                "9997",           // everybody
                (20000 + appId).toString(),  // cache group (e.g., 20321)
                (50000 + appId).toString()   // all group (e.g., 50321)
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
                val output =
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
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
