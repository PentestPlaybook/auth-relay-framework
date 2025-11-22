# **Auth Relay Framework**

## ⚠️ IMPORTANT: Authorized Use Only

This repository contains security research tools intended **exclusively** for authorized penetration testing, defensive security research, and controlled laboratory environments.

By using this project, you agree to:

* Use it **only** on systems and networks you own or are explicitly authorized to test
* Follow all applicable laws and regulations
* Operate strictly within defined testing scope
* Adhere to professional ethical security practices

---

## 🎯 Intended Use Cases

* Authorized penetration testing
* Red team exercises
* Academic security research
* MFA and captive-portal analysis
* Controlled lab testing environments

---

## 🚨 Legal & Ethical Requirements

### Before using this repository:

1. **Obtain written authorization**
2. **Follow all applicable laws**
3. **Respect defined scope (ROE)**
4. **Document tests**
5. **Report findings responsibly**

### ❌ Prohibited Uses

* Unauthorized access
* Production exploitation
* Commercial misuse
* Illegal activity of any kind

### 🛡 Defensive Guidance for Organizations

* Enforce HTTPS + HSTS
* Validate certificates properly
* Use certificate pinning
* Use phishing-resistant MFA
* Monitor authentication flows
* Audit captive portal behavior

---

# **WordPress Auth Relay Framework Overview**

This framework demonstrates a controlled authentication relay simulation using:

### **1. WordPress Droplet**

A public WordPress instance with:

* Enforced 2FA
* Let’s Encrypt TLS
* SendGrid SMTP
* miniOrange MFA enforcement

### **2. WiFi Pineapple**

Running EvilPortal with:

* Cloned WordPress login + MFA pages
* Static WordPress assets
* Custom Nginx location blocks for relay endpoints

### **3. Rooted Android Device**

With:

* Termux
* Termux:X11
* Selenium + Geckodriver
* WordPress Relay Android App
* Python automation script

> ⚠️ Only for controlled, authorized, legal environments.

---

# **High-Level Objectives**

## **Objective 1 — Provision the WordPress Droplet**

* Deploy hardened WordPress
* Configure DNS + TLS
* Integrate SendGrid
* Enforce miniOrange MFA

## **Objective 2 — Configure the WiFi Pineapple**

* Install + configure EvilPortal
* Upload cloned WordPress portal
* Add static WordPress styling files
* Modify Nginx handlers
* Enable/activate the portal

## **Objective 3 — Configure Termux and Deploy Android Relay App**

* Install Termux and Termux:X11
* Configure the Termux environment
* Build and Install the WordPress Relay Android App

## **Objective 4 — Execute the WordPress Relay Attack Simulation**
* Launch the relay workflow and spawn the browser session
* Capture credentials/OTP via the captive portal
* Verify automatic WordPress login + client internet release

---

# **Full Deployment Instructions**

Full Deployment Instructions: https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/objectives.txt

---

# **License & Disclaimer**

This software is provided **for educational and authorized use only.**
The author assumes **no liability** for misuse.
You are solely responsible for complying with laws and authorization requirements.
