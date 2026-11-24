#!/bin/sh
# usage: test_subupdate.sh <compiled test binary>   (see the header of test_subupdate.c)
set -e
T=/tmp/mvt; rm -rf $T; mkdir -p $T/data
cat > $T/fakecurl <<'EOS'
#!/bin/sh
# fake `xhuskydg_helper curl`: $1 is "curl"; the last argument is the URL, -o names the output
out=""; prev=""; for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; url="$a"; done
case "$url" in
  http://a/plain) printf 'vless://a#x\r\ntrojan://b#y\r\n' > "$out";;
  http://a/b64)   printf 'c3M6Ly9vbmUKdm1lc3M6Ly90d28K' > "$out";;
  *) exit 22;;
esac
EOS
chmod +x $T/fakecurl
printf 'http://a/plain#Plain\nhttp://a/b64#B64\nhttp://a/fail#Fail\n# comment\nftp://x#Bad\nhttp://a/plain#has:colon\n' > $T/data/autocheck_sublist.txt
"$1" $T/fakecurl
echo "--- pending"; cat $T/data/pending_sublist.txt
grep -q '^Plain:' $T/data/pending_sublist.txt && grep -q '^B64:' $T/data/pending_sublist.txt
! grep -q 'Fail\|Bad\|colon' $T/data/pending_sublist.txt
# key / sum compatibility with service.sh
k=$(printf '%s' 'http://a/plain#Plain' | md5sum | cut -d' ' -f1)
[ -f $T/data/sublist_cache/$k.dirty ]
[ "$(cat $T/data/sublist_cache/$k.sum)" = "$(printf 'vless://a#x\ntrojan://b#y\n' | md5sum | cut -d' ' -f1)" ]
payload=$(sed -n 's/^Plain://p' $T/data/pending_sublist.txt)
[ "$(printf '%s' "$payload" | base64 -d)" = "$(printf 'vless://a#x\ntrojan://b#y')" ]
# second round with the pending file taken: unchanged content is not pushed again
rm $T/data/pending_sublist.txt
"$1" $T/fakecurl >/dev/null 2>&1
[ ! -f $T/data/pending_sublist.txt ]
echo "all good"
