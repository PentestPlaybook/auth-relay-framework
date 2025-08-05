#!/bin/bash

echo "🚀 Setting up Selenium Session Takeover System..."
echo "This will work without any manual troubleshooting!"
echo ""

# Clean up
docker stop selenium-vnc 2>/dev/null || true
docker rm selenium-vnc 2>/dev/null || true

# Configure firewall
ufw allow 22/tcp
ufw allow 5901/tcp
ufw allow 6901/tcp  
ufw allow 5002/tcp
ufw --force enable

# Generate password
VNC_PASSWORD=$(openssl rand -base64 8)
echo "🔐 VNC Password: $VNC_PASSWORD"

# Create or update .env file with VNC_PASSWORD
echo "📝 Updating .env file..."
ENV_FILE="$HOME/.env"

# Create .env file if it doesn't exist
if [ ! -f "$ENV_FILE" ]; then
    echo "📄 Creating new .env file at $ENV_FILE"
    touch "$ENV_FILE"
fi

# Check if VNC_PASSWORD already exists in .env
if grep -q "^VNC_PASSWORD=" "$ENV_FILE"; then
    echo "⚠️  VNC_PASSWORD already exists in .env, updating value..."
    # Use sed to replace the existing line
    sed -i "s/^VNC_PASSWORD=.*/VNC_PASSWORD=$VNC_PASSWORD/" "$ENV_FILE"
else
    echo "➕ Adding VNC_PASSWORD to .env file..."
    # Append the new variable
    echo "VNC_PASSWORD=$VNC_PASSWORD" >> "$ENV_FILE"
fi

echo "✅ VNC_PASSWORD saved to $ENV_FILE"

# Start container with correct port mapping
echo "🐳 Starting container..."
docker run -d \
  --name selenium-vnc \
  --restart unless-stopped \
  -p 5901:5901 \
  -p 6901:80 \
  -p 5002:5000 \
  -e VNC_PW="$VNC_PASSWORD" \
  -e VNC_RESOLUTION=1920x1080 \
  -v /dev/shm:/dev/shm \
  dorowu/ubuntu-desktop-lxde-vnc:latest

echo "⏳ Waiting for container to start..."
sleep 30

# Install dependencies
echo "📦 Installing dependencies..."
docker exec selenium-vnc bash -c "
export DEBIAN_FRONTEND=noninteractive
apt update
apt install -y firefox wget python3 python3-pip curl
"

# Install geckodriver
echo "🔧 Installing geckodriver..."
docker exec selenium-vnc bash -c "
cd /tmp
wget -q https://github.com/mozilla/geckodriver/releases/download/v0.34.0/geckodriver-v0.34.0-linux64.tar.gz
tar -xzf geckodriver-v0.34.0-linux64.tar.gz
mv geckodriver /usr/local/bin/
chmod +x /usr/local/bin/geckodriver
"

# Install Python packages
echo "🐍 Installing Python packages..."
docker exec selenium-vnc bash -c "
pip3 install --upgrade pip
pip3 install selenium==4.15.0 flask==2.3.0
"

# Create working API (no f-string backslashes)
echo "⚡ Creating API..."
docker exec selenium-vnc bash -c 'cat > /root/api.py << '"'"'EOF'"'"'
import os
import time
from flask import Flask, request, jsonify
from selenium import webdriver
from selenium.webdriver.firefox.options import Options
from selenium.webdriver.firefox.service import Service
from selenium.webdriver.common.by import By

app = Flask(__name__)
sessions = {}
session_counter = 0
os.environ["DISPLAY"] = ":1"

def create_driver(headless=True):
    options = Options()
    if headless:
        options.add_argument("--headless")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    service = Service("/usr/local/bin/geckodriver")
    return webdriver.Firefox(service=service, options=options)

@app.route("/health")
def health():
    return {"status": "healthy", "sessions": len(sessions)}

@app.route("/start", methods=["POST"])
def start_session():
    global session_counter
    session_counter += 1
    sid = "session_" + str(session_counter)
    
    data = request.get_json() or {}
    url = data.get("url", "https://www.google.com")
    
    try:
        driver = create_driver(headless=True)
        driver.get(url)
        sessions[sid] = {
            "driver": driver,
            "status": "headless",
            "url": driver.current_url,
            "title": driver.title
        }
        return jsonify({
            "success": True,
            "session_id": sid,
            "status": "headless",
            "url": driver.current_url,
            "title": driver.title
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/takeover", methods=["POST"])
def takeover():
    data = request.get_json()
    sid = data.get("session_id")
    
    if sid not in sessions:
        return jsonify({"error": "Session not found"}), 404
    
    try:
        old_driver = sessions[sid]["driver"]
        current_url = old_driver.current_url
        cookies = old_driver.get_cookies()
        old_driver.quit()
        
        new_driver = create_driver(headless=False)
        new_driver.get(current_url)
        for cookie in cookies:
            try:
                new_driver.add_cookie(cookie)
            except:
                pass
        new_driver.refresh()
        
        sessions[sid] = {
            "driver": new_driver,
            "status": "visible",
            "url": new_driver.current_url,
            "title": new_driver.title
        }
        
        return jsonify({
            "success": True,
            "session_id": sid,
            "status": "visible",
            "message": "Session is now visible! Check VNC."
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/command", methods=["POST"])
def execute_command():
    data = request.get_json()
    sid = data.get("session_id")
    action = data.get("action")
    
    if sid not in sessions:
        return jsonify({"error": "Session not found"}), 404
    
    driver = sessions[sid]["driver"]
    
    try:
        if action == "navigate":
            driver.get(data["url"])
            result = "Navigated to " + data["url"]
        elif action == "click":
            driver.find_element(By.CSS_SELECTOR, data["selector"]).click()
            result = "Clicked " + data["selector"]
        elif action == "type":
            element = driver.find_element(By.CSS_SELECTOR, data["selector"])
            element.clear()
            element.send_keys(data["text"])
            result = "Typed: " + data["text"]
        elif action == "wait":
            seconds = data.get("seconds", 1)
            time.sleep(seconds)
            result = "Waited " + str(seconds) + " seconds"
        else:
            return jsonify({"error": "Unknown action"}), 400
        
        return jsonify({
            "success": True,
            "result": result,
            "current_url": driver.current_url
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/sessions")
def list_sessions():
    session_list = []
    for sid, session in sessions.items():
        try:
            session_list.append({
                "session_id": sid,
                "status": session["status"],
                "url": session["driver"].current_url,
                "title": session["driver"].title
            })
        except:
            pass
    return jsonify({"sessions": session_list})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
EOF'

# Create a startup script inside the container that will auto-restart the API
echo "🔧 Creating auto-restart mechanism..."
docker exec selenium-vnc bash -c 'cat > /root/start_api.sh << '"'"'EOF'"'"'
#!/bin/bash
cd /root
export DISPLAY=:1
while true; do
    echo "$(date): Starting API..." >> /var/log/api.log
    python3 /root/api.py &
    API_PID=$!
    echo "$(date): API started with PID $API_PID" >> /var/log/api.log
    wait $API_PID
    echo "$(date): API stopped, restarting in 5 seconds..." >> /var/log/api.log
    sleep 5
done
EOF'

docker exec selenium-vnc chmod +x /root/start_api.sh

# Start the API using supervisor (which works in containers)
echo "🛠️ Installing and configuring supervisor..."
docker exec selenium-vnc bash -c "
apt update && apt install -y supervisor
"

# Create supervisor config for the API
docker exec selenium-vnc bash -c 'cat > /etc/supervisor/conf.d/selenium-api.conf << '"'"'EOF'"'"'
[program:selenium-api]
command=/root/start_api.sh
directory=/root
autostart=true
autorestart=true
stderr_logfile=/var/log/selenium-api.err.log
stdout_logfile=/var/log/selenium-api.out.log
user=root
environment=DISPLAY=":1"
EOF'

# Update supervisor and start the API
docker exec selenium-vnc bash -c "
supervisorctl reread
supervisorctl update
supervisorctl start selenium-api
"

# Wait for everything to initialize
echo "⏳ Waiting for services to initialize..."
sleep 15

# Create host-level systemd service for container management
echo "🔧 Creating host-level persistence..."
cat > /etc/systemd/system/selenium-container.service << 'EOF'
[Unit]
Description=Selenium Container Management
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/root/start_selenium_container.sh
TimeoutStartSec=300

[Install]
WantedBy=multi-user.target
EOF

# Create the host startup script
cat > /root/start_selenium_container.sh << 'EOF'
#!/bin/bash

echo "$(date): Starting Selenium container management..." >> /var/log/selenium-container.log

# Wait for Docker to be ready
sleep 10

# Start container if not running
if ! docker ps | grep -q selenium-vnc; then
    echo "$(date): Starting selenium-vnc container..." >> /var/log/selenium-container.log
    docker start selenium-vnc
    sleep 20
    
    # Restart the API service inside the container
    docker exec selenium-vnc systemctl restart selenium-api.service
    sleep 10
fi

# Verify API is running
for i in {1..6}; do
    if curl -s http://localhost:5002/health >/dev/null 2>&1; then
        echo "$(date): API is healthy" >> /var/log/selenium-container.log
        break
    else
        echo "$(date): Waiting for API (attempt $i)..." >> /var/log/selenium-container.log
        docker exec selenium-vnc systemctl restart selenium-api.service
        sleep 10
    fi
done

echo "$(date): Container management complete" >> /var/log/selenium-container.log
EOF

chmod +x /root/start_selenium_container.sh

# Enable host-level service
systemctl daemon-reload
systemctl enable selenium-container.service

# Get IP
DROPLET_IP=$(curl -s ifconfig.me)

# Test everything
echo "🧪 Testing services..."

# Test API
if curl -s http://localhost:5002/health | grep -q "healthy"; then
    echo "✅ API is working!"
    API_STATUS="Working"
else
    echo "❌ API not responding"
    API_STATUS="Failed"
fi

# Test VNC
if curl -s http://localhost:6901 >/dev/null 2>&1; then
    echo "✅ VNC is working!"
    VNC_STATUS="Working"
else
    echo "❌ VNC not responding"
    VNC_STATUS="Failed"
fi

# Save final results
cat > /root/setup_results.txt << EOF
🎉 Selenium Setup Results (PERSISTENT VERSION)
===============================================

Droplet IP: $DROPLET_IP
VNC Password: $VNC_PASSWORD

Service Status:
- API: $API_STATUS
- VNC: $VNC_STATUS
- Container Auto-restart: Enabled
- API Auto-restart: Enabled

Connection Info:
===============
VNC Web Access: http://$DROPLET_IP:6901/?password=$VNC_PASSWORD
API Health: http://$DROPLET_IP:5002/health

Persistence Features:
====================
- Container auto-starts on boot via systemd
- API auto-restarts if it crashes
- Services will survive VPS reboots

Troubleshooting Commands:
========================
- Check container service: systemctl status selenium-container
- Check API logs: docker exec selenium-vnc journalctl -u selenium-api -f
- Container logs: cat /var/log/selenium-container.log
- Manual restart: systemctl restart selenium-container

Usage Examples:
==============

1. Start headless session:
curl -X POST http://$DROPLET_IP:5002/start \\
  -H "Content-Type: application/json" \\
  -d '{"url": "https://amazon.com"}'

2. Take over session (make visible):
curl -X POST http://$DROPLET_IP:5002/takeover \\
  -H "Content-Type: application/json" \\
  -d '{"session_id": "session_1"}'

3. Execute command:
curl -X POST http://$DROPLET_IP:5002/command \\
  -H "Content-Type: application/json" \\
  -d '{"session_id": "session_1", "action": "click", "selector": "#search"}'

4. List sessions:
curl http://$DROPLET_IP:5002/sessions

Generated: $(date)
EOF

echo ""
echo "🎉 PERSISTENT Setup Complete!"
echo ""
echo "Results:"
echo "- API: $API_STATUS"
echo "- VNC: $VNC_STATUS"
echo "- ✅ PERSISTENCE ENABLED"
echo ""
echo "Connection Details:"
echo "VNC: http://$DROPLET_IP:6901/?password=$VNC_PASSWORD"
echo "API: http://$DROPLET_IP:5002/health"
echo ""
echo "🔄 This setup will survive reboots!"
echo "Full details: /root/setup_results.txt"
