/* LIVE (real kernel, root): 10 copies of a counter-only rule inflate the sum 10x; one poll with self-heal leaves one.
 * gcc -Wno-override-init -Inative/jni/service -o /tmp/live_heal native/tests/live_heal.c -lpthread
 * then, with lo up inside the netns: unshare -n sh -c "<bring lo up>; /tmp/live_heal" */
#include "ipt.c"
#include <unistd.h>
#include <arpa/inet.h>
static void burst(int n){ int s=socket(AF_INET,SOCK_DGRAM,0); struct sockaddr_in a={.sin_family=AF_INET,.sin_port=htons(9)}; inet_pton(AF_INET,"127.0.0.1",&a.sin_addr); char b[1000]={0}; for(int i=0;i<n;i++) sendto(s,b,sizeof b,0,(void*)&a,sizeof a); close(s); usleep(200000);}
static void dump(const char*tag){ ipt_tx*t=ipt_tx_begin(AF_INET,"mangle"); ipt_rule_stat st[64]; int n=ipt_chain_stats(t,"MV2R_TRAFFIC",st,64); printf("%s: %d rules:",tag,n); for(int i=0;i<n;i++) printf(" [%s %llu B]",st[i].any?"*":st[i].src,(unsigned long long)st[i].bytes); puts(""); ipt_tx_abort(t);}
static void poll_once(int heal){
  ipt_tx*t=ipt_tx_begin(AF_INET,"mangle"); (void)ipt_new_chain(t,"MV2R_TRAFFIC");
  if(ipt_chain_stats(t,"MV2R_TRAFFIC",NULL,0)==0)(void)ipt_append(t,"MV2R_TRAFFIC",&IPT_SPEC(.target=MVT_COUNT));
  ipt_spec hook=IPT_SPEC(.out_if="lo",.target=MVT_JUMP,.jump_chain="MV2R_TRAFFIC");
  if(ipt_check(t,"POSTROUTING",&hook)!=0)(void)ipt_insert(t,"POSTROUTING",1,&hook);
  if(heal){(void)ipt_dedup_rule(t,"POSTROUTING",&hook);(void)ipt_dedup_chain(t,"MV2R_TRAFFIC");}
  ipt_spec s=IPT_SPEC(.src="127.0.0.1",.target=MVT_COUNT); if(ipt_check(t,"MV2R_TRAFFIC",&s)!=0)(void)ipt_append(t,"MV2R_TRAFFIC",&s);
  ipt_tx_commit(t);}
int main(void){
  poll_once(0);
  ipt_tx*t=ipt_tx_begin(AF_INET,"mangle"); for(int k=0;k<9;k++)(void)ipt_append(t,"MV2R_TRAFFIC",&IPT_SPEC(.src="127.0.0.1",.target=MVT_COUNT)); ipt_tx_commit(t);   /* 10 copies */
  burst(100); dump("10 copies, 100 pkts sent (truth: 100 UDP + 100 ICMP = 200 pkts, 160400 B)");
  poll_once(1); dump("after one poll with self-heal");
  burst(100); dump("+100 pkts (expect 320800 B)");
  return 0;}
