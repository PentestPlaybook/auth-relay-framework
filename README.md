> ⚠️ **AUTHORIZED USE ONLY**  
> This security research framework is intended **exclusively for authorized penetration testing, academic research, and controlled laboratory environments.**  
>
> **Unauthorized access to computer systems is illegal** under the U.S. Computer Fraud and Abuse Act (18 U.S.C. §1030) and equivalent laws worldwide.  
>
> By using this framework, you agree to:  
> • Obtain **written authorization** from system owners  
> • Operate **strictly within defined scope**  
> • Conduct all activity as **good-faith security research**  
>
> This project exists to help organizations strengthen authentication security.  
> **Use responsibly. Test ethically.**

# 📘 Auth Relay Framework  
**Authentication Relay Attack Simulation for Authorized Security Research**

---

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](#license)  
[![Good-Faith Research](https://img.shields.io/badge/Good--Faith-Security%20Research-orange.svg)](#good-faith-security-research-policy)

---

# 🧭 Overview

The **Auth Relay Framework** demonstrates, in a **controlled and authorized environment**, how real-world authentication relay attacks operate.  
It is designed for:

- Professional penetration testers  
- Red teams / purple teams  
- Blue-team defenders  
- Internal security validation  
- Academic and IRB-approved research  
- Controlled cybersecurity labs  

The framework aligns with industry standards (PTES, OWASP, NIST, ISO 27001) and provides a safe, structured environment for authentication security research.

**Note:** Professionals using this framework often hold certifications such as **OSCP**, **OSEP**, **OSCE**, or **CREST**—although these are **not** required.

---

# 🔧 System Components

### **1. WordPress Droplet**
A production-like environment featuring:

- TLS via Let’s Encrypt  
- MiniOrange 2FA enforcement  
- SendGrid SMTP  
- Realistic login flows  

### **2. WiFi Pineapple**
Used for:

- Rogue AP simulation  
- Captive portal demonstration  
- Controlled credential relay testing  

### **3. Rooted Android Device**
Implements:

- Termux/X11  
- Selenium automation  
- Custom Android relay app  

All components operate inside **authorized, isolated, and controlled environments** only.

---

# 🎯 Intended Use Cases

### ✔️ Allowed (Requires Written Authorization)
- Enterprise penetration tests  
- Red-team campaigns  
- Blue-team training & detection validation  
- Authentication hardening exercises  
- Internal SOC 2 control testing  
- HIPAA-compliant security assessments (with proper agreements)  
- Academic cybersecurity research  
- Private lab environments  

### ❌ Not Allowed
- Unauthorized access to any system  
- Real-world credential interception  
- Attacking systems without permission  
- Bypassing MFA outside authorized scope  
- Evading monitoring or forensic systems  
- Violating data protection laws (GDPR/CCPA)  
- Any illegal activity in any jurisdiction  

This is strictly a **defensive and educational framework**, not a weapon.

---

# 🛡️ Defensive & Educational Value

### 🔐 Strengthening Authentication
Demonstrates weaknesses in:

- SMS/email OTP  
- Push MFA  
- Legacy 2FA methods  
- Improper TLS validation  
- Missing certificate pinning  

Supports migration to:

- **FIDO2**  
- **WebAuthn**  
- **Passkeys**  
- **Hardware-backed MFA**

### 🧠 Training & Awareness
Provides hands-on education for:

- SOC analysts  
- Blue-team defenders  
- Red-team/pentest operators  
- Security engineers  
- Cybersecurity students  

Teaches:

- Credential relay flow  
- Captive portal UX exploitation  
- MFA time sensitivity  
- TLS trust model pitfalls  
- Human-factor authentication gaps  

### 🛠️ Defensive Guidance
Organizations can strengthen defenses through:

**Technical Controls**  
- WIDS & rogue AP detection  
- HSTS and certificate pinning  
- Certificate Transparency monitoring  
- Strong MFA enrollment policies  
- Behavioral login anomaly detection  

**Organizational Controls**  
- Employee training on captive portal risks  
- Hardening guest networks  
- Authentication governance frameworks  
- Clear reporting procedures  

---

# 📋 Pre-Deployment Checklist (Required for Legal Use)

Before using this framework, verify the following:

## ✔️ Authorization Requirements
- Written permission from all system/network owners  
- Signed **Statement of Work (SOW)**  
- Signed **Rules of Engagement (ROE)**  
- Scope boundaries must be clear and documented  

Legal teams may provide SOW/ROE templates; consult counsel as appropriate.

## ✔️ Compliance & Legal Review
You must ensure compliance with:

### **United States**
- CFAA (18 U.S.C. §1030)  
- State computer misuse laws  

### **International**
- **GDPR** (EU)  
- **CCPA / CPRA** (California)  
- **ePrivacy Directive**  
- Local wireless/telecom regulations  

### **Industry Standards**
- **ISO/IEC 27001** (logging & audit controls)  
- **NIST Cybersecurity Framework**  
- **SOC 2 Trust Service Criteria** (Security, Availability, Confidentiality)  
- **HIPAA** Security Rule requirements if healthcare systems are in scope  

### **If Payment Systems Are in Scope**
- Ensure alignment with **PCI DSS**, including segmentation and authorization requirements.

## ✔️ Data Handling & Storage
Define procedures for:

- Sanitizing captured credentials  
- Minimal retention  
- Secure deletion (NIST SP 800-88)  
- Logging access control  
- Data minimization  

## ✔️ Recommended (Not Required)
- Cyber liability or tech E&O insurance  
- Legal counsel review prior to enterprise engagements  

---

# 📚 Educational Value

This framework helps researchers understand:

- Identity and access management weak points  
- Practical MFA bypass mechanics (in labs only)  
- Endpoint posture verification  
- Real-world attacker methodology  
- Why strong MFA alone is not enough  

Ideal for:

- Red/blue exercises  
- Academic courses  
- Cyber ranges  
- SOC training labs  

---

# 📂 Repository Structure

```
auth-relay-framework/
├── wordpress/
│   ├── captive-portal/
│   │   ├── setup/
│   │   ├── execution/
│   │   ├── objectives.txt
│   │   └── web-root/
│   └── cloud/
└── README.md
```

---

# 📘 Full Documentation

### 🔹 WordPress Droplet Setup  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/droplet/wordpress-droplet-setup.txt

### 🔹 WiFi Pineapple Configuration  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/pineapple/evilportal-setup.txt  

Web Root Files:  
https://github.com/PentestPlaybook/auth-relay-framework/tree/main/wordpress/captive-portal/setup/pineapple/web-root

### 🔹 Android / Termux Relay Environment  
Termux Setup:  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/android/termux_setup.txt  

WordPress Relay App Setup:  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/android/WordpressRelayApp_setup.txt  

App Source Code:  
https://github.com/PentestPlaybook/auth-relay-framework/tree/main/wordpress/captive-portal/setup/android/WordpressRelayApp

### 🔹 Relay Simulation Execution  
Execution Guide:  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/execution/wordpress_relay_execution.txt  

Automation Script:  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/execution/wordpress-relay.py

---

# 🛡️ Good-Faith Security Research Policy

This project may be used only for **good-faith research**, defined by:

### **1. Authorization**
- Documented permission is required  
- SOW and ROE must be in place  
- Scope boundaries must be clear  

### **2. Controlled Environment**
Testing allowed only on:

- Owned systems  
- Systems explicitly provided for testing  
- Academic research labs  
- Authorized red-team environments  

### **3. No Harm**
You must avoid:

- Production impact  
- Accessing personal data  
- Excessive scope deviation  

### **4. No Unauthorized Access**
Never use this project to:

- Attack real-world systems  
- Intercept genuine user credentials  
- Evade detection outside authorized scope  

### **5. Responsible Disclosure**
Any discovered vulnerabilities must be:

- Documented  
- Privately disclosed  
- Never weaponized  

### **Safe Harbor Alignment**
This project aligns with principles from:

- Disclose.io  
- HackerOne Safe Harbor  
- Bugcrowd Vulnerability Disclosure Standards  
- DOJ 2022 CFAA Good-Faith Guidance  

---

# 🔄 Post-Engagement Requirements

After completing authorized testing:

### ✔️ Deactivate Infrastructure
- Disable rogue APs  
- Stop automation scripts  
- Shut down droplet and relay components  

### ✔️ Purge Sensitive Data
Follow **NIST SP 800-88** & **ISO 27001** Annex controls.

### ✔️ Deliver Findings
Provide:

- Vulnerabilities  
- Reproduction steps  
- Impact analysis  
- Remediation recommendations  

### ✔️ Validate Remediation
Retest if required.

---

# 📄 License

**MIT License © 2025 PentestPlaybook**

Distributed **“as is”**, without warranty.  
The authors:

- Do **not** authorize illegal use  
- Assume **no liability** for misuse  
- Provide the framework for **authorized security research only**  

---

# 🤝 Contributing

Contributions welcome for:

- Defensive features  
- Documentation  
- Academic modules  
- Detection enhancements  

Do **not** contribute code intended for unauthorized access.

---

# 📞 Report Abuse

If you observe misuse:

- Notify affected organizations  
- Contact appropriate authorities  
- U.S.: FBI IC3 – https://www.ic3.gov  

---

# 🧩 Additional Resources

**Pentesting Standards**  
- PTES: https://www.pentest-standard.org/  
- OWASP WSTG: https://owasp.org/www-project-web-security-testing-guide/

**Bug Bounty Platforms**  
- HackerOne: https://www.hackerone.com  
- Bugcrowd: https://www.bugcrowd.com  
- Intigriti: https://www.intigriti.com  
- YesWeHack: https://www.yeswehack.com  

**Compliance Frameworks**  
- ISO/IEC 27001: https://www.iso.org/standard/82875.html  
- NIST Cybersecurity Framework: https://www.nist.gov/cyberframework  
- PCI DSS: https://www.pcisecuritystandards.org/  

**General Legal & Security References**  
- Disclose.io: https://disclose.io  
- DOJ CFAA Good-Faith Guidance: https://www.justice.gov  

---

# ⚡ Final Note

This framework exists to **strengthen authentication security**, **educate defenders**, and **support authorized cybersecurity professionals**.

**Use responsibly. Test ethically. Always get authorization in writing.**
