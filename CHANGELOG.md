# Magic V2Ray v3.0 Release Notes

Magic V2Ray v3.0 lets other apps and websites control it. Any app can now ask Magic V2Ray to route the device through a server for a while, using a `magicv2ray://connect` link, an intent or a broadcast. The server is temporary: your own nodes and settings are never changed, and when the connection ends your previously selected node comes back, running again if it was running before. A session never survives a reboot, and a session started by a website or another app can always be ended from the notification, the Quick Settings tile or the app itself.

Every request is confirmed by you. The new permission popup shows who is asking, the full server address and the apps that will be kept out of the VPN, and lets you allow it once, always, or deny it. Apps you choose to always trust are tied to their signing certificate, so a reinstalled or impersonating app is not trusted, and links and browsers can never be trusted because Android cannot tell which web page sent them. A new Access permissions tab lists the trusted apps with search and a revoke button, and an in-app guide explains how to connect from your own app or site.

A connect request can also carry temporary settings. While the session lasts it can change traffic settings, network settings, excluded apps, routing rules and custom hosts, which makes it possible to build tools such as a DNS rewriter on top of Magic V2Ray. Only a safe, validated set of options is accepted, and options that would expose your device, such as sharing a proxy on your network, can never be changed this way. These settings are kept in memory or cache only and are never written to your saved profile. A request that changes settings is always confirmed, and an app you trust is asked again whenever the settings it sends are different from the ones you last allowed.

Apps can also look at Magic V2Ray and listen to it. A read-only content provider and an ordered broadcast report the version, the supported API level and features, and whether the proxy is running. A new `STATE_CHANGED` broadcast is sent whenever the proxy connects or disconnects, whether the cause was the app that asked, you, a crash or the root service, so an app can stop its own work as soon as Magic V2Ray disconnects. Nothing in this data reveals a server address or your settings.

The app itself became easier to use. Provider node lists are now saved to native storage, so home-screen shortcuts always show your latest nodes without opening the app. Headings on tabs with a background image are outlined, and hints, warnings and empty states sit on their own cards, so text stays readable over any picture. Custom and default app colors are now available on every supported Android version, and only the Follow system option still needs Android 13 or newer. The new screens are translated into all supported languages.

Full documentation of the connect link, the intent actions, the temporary settings and the info and state API is in the README, in English and in Vietnamese.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)