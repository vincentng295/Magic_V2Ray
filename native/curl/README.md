# curl applet (`xhuskydg_helper curl ...`)

The curl tool is built into `xhuskydg_helper`, so there is no separate `bin/curl` any more:

    xhuskydg_helper curl -sSL -f --max-time 20 https://example.com/
    xhuskydg_helper curl --socks5-hostname 127.0.0.1:1080 -s https://example.com/

Source = [vvb2060/curl-android](https://github.com/vvb2060/curl-android) (README kept as `README.curl-android.md`),
vendored here instead of as git submodules:

| dir | version | what is vendored |
|---|---|---|
| `curl/` | 8.18.0 | `include/`, `lib/` (libcurl), `src/` (the tool) |
| `nghttp2/` | 1.68.0 | `lib/` (HTTP/2) |
| `nghttp3/` | 1.14.0 | `lib/` incl. `lib/sfparse` (HTTP/3) |
| `ngtcp2/` | 1.19.0 | `lib/` + `crypto/` (BoringSSL backend) (QUIC) |
| `config/`, `config64/` | | upstream curl-android `curl_config.h` / `config.h` (arm64-v8a and x86_64 only) + version headers already copied into nghttp2/nghttp3/ngtcp2 |

TLS is BoringSSL (static `libssl.a` + `libcrypto.a`), downloaded by `build.sh` from the curl-android
author's prefab AAR on Maven Central into `.deps/` (override: `MV_BORINGSSL_DIR`, `MV_BORINGSSL_VERSION`).
Default CA store is the system one (`/system/etc/security/cacerts`), DNS is the system resolver.

## Build
`build.sh` calls `build_curl.sh`, which compiles every file in `sources.txt` into `.build/<arch>/libmvcurl.a`
(cached by a hash of this directory), then links it into the helper. Only the tool's `main()` is touched:
`tool_main.c` is compiled with `-Dmain=run_curl` and `native/jni/main.cpp` dispatches `curl` to it.
`curl/src/tool_hugehelp.c` is the upstream-workflow stub (no built-in `--manual`).

## Updating
Replace the four source dirs with a newer release, regenerate `sources.txt` from the upstream
`Makefile.inc` / `Makefile.am` lists (`CURL_CFILES`, `CURLX_CFILES`, `CSOURCES`, `OBJECTS`, ngtcp2 boringssl crypto),
recopy `config/versions/*.h` over the nghttp2/nghttp3/ngtcp2 version headers, and bump `MV_BORINGSSL_VERSION`
to the matching BoringSSL AAR.

Licenses: curl (`curl/COPYING`), nghttp2/nghttp3/ngtcp2 MIT (their `COPYING`).
