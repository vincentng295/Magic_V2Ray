#!/bin/sh
# Host-side tests for the iptables engine (fake kernel). From the repo root:
#   sh native/tests/run_host_tests.sh
set -e
cd "$(dirname "$0")/../.."
S=native/jni/service
gcc -Wno-override-init -fsanitize=address,undefined -o /tmp/test_ipt native/tests/test_ipt.c $S/ipt.c -lpthread
/tmp/test_ipt
# live test (real kernel, needs root):  gcc -Wno-override-init -o /tmp/live_ipt native/tests/live_ipt.c $S/ipt.c -lpthread && unshare -n /tmp/live_ipt
gcc -Wno-override-init -D_GNU_SOURCE -fsanitize=address,undefined -o /tmp/test_fallthrough native/tests/test_fallthrough.c $S/ipt.c -lpthread
/tmp/test_fallthrough
