# **Auth Relay Framework**

---

## ⚠️ CRITICAL: Authorized Use Only

This repository contains security research tools for **authorized penetration testing and controlled research environments only**.

**Unauthorized access to computer systems is a federal crime** under the Computer Fraud and Abuse Act (18 U.S.C. § 1030) and equivalent laws worldwide. Penalties include imprisonment, significant fines, and civil liability.

### **You Must Have:**
- **Written authorization** from system/network owners
- **Defined scope and rules of engagement**
- **Legal compliance** in all applicable jurisdictions
- **Professional context** (contracted pentest, authorized red team, academic research, or personal lab)

### **Strictly Prohibited:**
- Unauthorized access to any system
- Testing outside defined scope
- Production system exploitation without approval
- Any illegal or unethical activity

**By using this framework, you accept full responsibility for ensuring lawful, authorized use.**

---

## 🎯 Intended Use Cases

✅ Authorized penetration testing engagements  
✅ Red team exercises with proper authorization  
✅ Academic security research (with IRB approval)  
✅ Defensive security research in isolated labs  
✅ MFA/authentication mechanism analysis on test systems  
✅ Internal security validation on owned infrastructure  

---

## 🛡️ Defensive Guidance

### **Organizations: Protect Against This Attack**

**Immediate Actions:**
- **Deploy phishing-resistant MFA** (FIDO2/WebAuthn, hardware security keys)
- **Enforce HTTPS + HSTS** on all authentication endpoints
- **Implement certificate pinning** where feasible
- **Monitor for rogue access points** and unusual authentication patterns

**Technical Controls:**
- Certificate transparency monitoring
- Wireless intrusion detection systems (WIDS)
- Anomaly detection on authentication flows
- Geolocation and device fingerprinting
- 802.1X network authentication

**User Education:**
- Train employees to verify certificates
- Teach captive portal risks
- Encourage hardware security key adoption
- Establish clear reporting procedures for suspicious auth requests

---

## 📋 Pre-Deployment Checklist

Before deploying this framework, verify:

- [ ] Written authorization from all system/network owners
- [ ] Signed scope of work with defined boundaries
- [ ] Rules of engagement documented
- [ ] Data handling and destruction procedures established
- [ ] Client emergency contact information available
- [ ] Professional liability insurance (recommended)
- [ ] Test environment isolated from production systems

---

# **WordPress Auth Relay Framework Overview**

This framework demonstrates an authentication relay attack through three coordinated components:

### **1. WordPress Droplet**
- Hardened WordPress with enforced 2FA (miniOrange)
- Let's Encrypt TLS certificates
- SendGrid SMTP integration
- Realistic production-like configuration

### **2. WiFi Pineapple**
- EvilPortal module with cloned login pages
- Static WordPress assets for authentic rendering
- Custom Nginx relay endpoints
- Captive portal functionality

### **3. Rooted Android Device**
- Termux with X11 support
- Selenium WebDriver automation
- Custom relay application
- Python-based credential forwarding

> **This demonstrates real-world MITM authentication relay attacks. Deploy only in authorized, controlled environments.**

---

# **High-Level Objectives**

## **Objective 1 — Provision the WordPress Droplet**
Deploy hardened WordPress, configure DNS/TLS, integrate SendGrid SMTP, and enforce miniOrange 2FA

## **Objective 2 — Configure the WiFi Pineapple**
Install EvilPortal, upload cloned portal pages, add static assets, modify Nginx, and activate portal

## **Objective 3 — Configure Termux and Deploy Android Relay App**
Set up Termux environment, install required packages, and deploy custom relay application

## **Objective 4 — Execute the WordPress Relay Attack Simulation**
Launch relay workflow, capture credentials via captive portal, verify automated login and session establishment

---

# **Full Deployment Instructions**

**Complete documentation:**  
https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/objectives.txt

**Individual guides:**
- [WordPress Droplet Setup](https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/droplet/wordpress-droplet-setup.txt)
- [WiFi Pineapple Configuration](https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/pineapple/evilportal-setup.txt)
- [Termux Environment Setup](https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/android/termux_setup.txt)
- [Wordpress Relay App Setup](https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/setup/android/WordpressRelayApp_setup.txt/)
- [Relay Demonstration](https://github.com/PentestPlaybook/auth-relay-framework/blob/main/wordpress/captive-portal/execution/wordpress_relay_execution.txt)

---

## 📚 Educational Value

### **What This Demonstrates:**

This framework illustrates limitations of non-phishing-resistant 2FA methods:

- **SMS/Email OTP vulnerabilities** to real-time relay attacks
- **Captive portal trust exploitation** in public WiFi scenarios
- **Automated credential relay** techniques used by threat actors
- **Certificate validation gaps** in user awareness

### **Key Lessons:**
- Traditional 2FA (SMS/email codes) can be bypassed via real-time relay
- Phishing-resistant authentication (FIDO2, WebAuthn) prevents these attacks
- Certificate validation and pinning are critical defensive controls
- User education alone is insufficient without technical controls

---

## 🔄 Post-Engagement Requirements

### **After authorized testing:**

**Immediate:**
- Deactivate all attack infrastructure
- Stop relay scripts and automation
- Disconnect WiFi Pineapple access point

**Data Handling:**
- Securely delete all captured credentials
- Remove test accounts and access logs
- Document data destruction for compliance

**Reporting:**
- Provide detailed findings to authorized party
- Include specific remediation recommendations
- Document detection gaps and control failures

**Follow-Up:**
- Verify remediation implementation if requested
- Conduct retesting per engagement terms
- Archive authorization documentation per policy

---

## 🤝 Responsible Disclosure

If you discover vulnerabilities during authorized testing:

1. Document thoroughly with reproduction steps
2. Notify system owner per agreed timeline
3. Limit exploitation to proof-of-concept only
4. Provide actionable remediation guidance
5. Follow responsible disclosure timelines

---

## 📞 Support and Resources

**Professional Standards:**
- PTES (Penetration Testing Execution Standard): http://www.pentest-standard.org/
- OWASP Testing Guide: https://owasp.org/www-project-web-security-testing-guide/
- NIST Cybersecurity Framework: https://www.nist.gov/cyberframework

**Legal Guidance:**
- Consult cybersecurity legal counsel in your jurisdiction
- Review applicable computer crime statutes before testing
- Understand data protection laws (GDPR, CCPA, etc.) that may apply

**Report Abuse:**
If you observe unauthorized or illegal use of this framework:
- Contact local law enforcement
- Report to FBI IC3: https://www.ic3.gov/
- Notify your organization's security team

---

## 📄 License & Disclaimer

**MIT License** - Copyright (c) 2025 PentestPlaybook

This software is provided "as is" for educational and authorized security testing only.

**The authors:**
- Provide this framework for legitimate security research
- Do not authorize, encourage, or condone illegal use
- Assume no liability for misuse or unauthorized access
- Are not responsible for ensuring you have proper authorization

**You (the user):**
- Are solely responsible for lawful and authorized use
- Must independently verify legal compliance
- Accept all risks associated with using this framework
- Agree to indemnify authors against claims arising from your use

**Full license:** See LICENSE file

---

## ⚡ Final Note

This framework demonstrates real attack techniques used by threat actors. It exists to help security professionals understand these methods and help organizations defend against them.

**Use responsibly. Test ethically. Always get authorization in writing.**

---

**Framework Version:** 1.0  
**Last Updated:** November 2025  
**Maintained By:** PentestPlaybook

For questions about ethical security research practices, consult appropriate legal counsel and professional advisors.
