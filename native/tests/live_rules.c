#include "../jni/service/netlink.h"
#include <linux/fib_rules.h>
#include <sys/socket.h>
#include <stdio.h>
#include <assert.h>
/* live test (needs root): gcc -D_GNU_SOURCE -o /tmp/live_rules native/tests/live_rules.c native/jni/service/netlink.c && unshare -n /tmp/live_rules */
int main(void){
  nl_rule r = NL_RULE(AF_INET); r.has_mark = true; r.mark = 255; r.table = 1002; r.pref = 1000;
  assert(!nl_rule_exists(AF_INET,1000,true,255,1002));
  assert(nl_rule_add(&r)==0);
  assert(nl_rule_exists(AF_INET,1000,true,255,1002));
  assert(!nl_rule_exists(AF_INET,1000,true,255,999));   /* wrong table */
  assert(!nl_rule_exists(AF_INET,1000,true,1,1002));    /* wrong mark */
  assert(!nl_rule_exists(AF_INET6,1000,true,255,1002)); /* other family */
  nl_rule d = NL_RULE(AF_INET); d.has_mark=true; d.mark=255; d.pref=1000;
  assert(nl_rule_del_all(&d)==1);
  assert(!nl_rule_exists(AF_INET,1000,true,255,1002));
  puts("PASS"); return 0; }
