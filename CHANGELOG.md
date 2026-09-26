# Release Notes — v1.19

**Direct Cloudflare WARP Account Generation**
This release introduces direct Cloudflare WARP account registration natively integrated into the interface. Users can now generate a fresh, free Cloudflare WARP account directly against Cloudflare's API with a single click in the WireGuard node settings, eliminating the need for external tools like wgcf-cli. Key generation is handled natively via Xray, with credentials, server keys, and reserved bytes derived automatically and populated into the configuration fields. The interface copy and localized translation strings across all supported languages have been updated to reflect these simplified management options.

**Header Redesign and Streamlined Service Controls**
The web user interface receives structural enhancements to improve layout clarity and interaction efficiency. Core engine controls, including the engine toggle actions, have been relocated into the top header bar for consistent access across all management tabs. The header structure has been refined with flexible row wrapping and improved text truncation for device status labels, ensuring clean presentation on narrow screen dimensions.

**Improved TUN Interface Setup and Error Handling**
Network initialization logic has been reinforced to prevent partial engine starts and hanging states when the TUN interface fails to initialize. The TUN device configuration routine now enforces strict status checks and fails fast with an explicit error log if the interface does not appear within the increased retry window. These checks are integrated into both the initial routing setup and service restart procedures to guarantee system stability.

**Asynchronous UI Loading Updates**
User interface responsiveness during background operations has been enhanced across several interactive flows. Operations such as active IP verification, subscription fetching, and routing preset updates now utilize asynchronous loading state handling. This prevents UI thread blocking and ensures loading overlays display smoothly during network requests.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)