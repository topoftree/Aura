#!/usr/bin/env python3
"""Execute the real preload on a host with deterministic resolver/Android stubs.

No Internet requests. This checks interposition, errno/result preservation,
bounded targets and output, not Android DNS connectivity or Wine integration.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HEADER = r'''
#include <stdint.h>
#include <stddef.h>
#include <netdb.h>
typedef uint64_t net_handle_t;
#define NETWORK_UNSPECIFIED ((net_handle_t)0)
int android_getprocnetwork(net_handle_t *);
int android_getprocdns(net_handle_t *);
int android_getaddrinfofornetwork(net_handle_t, const char *, const char *, const struct addrinfo *, struct addrinfo **);
int android_res_nquery(net_handle_t, const char *, int, int, uint32_t);
int android_res_nresult(int, int *, uint8_t *, size_t);
void android_res_cancel(int);
'''
MOCK = r'''
#include <android/multinetwork.h>
#include <assert.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
int calls, active_calls, binding_calls, original_frees, control_frees;
int raw_queries, raw_results, raw_cancels;
static int query_fd=-1, writer_fd=-1, query_type;
static struct addrinfo original_v6 = {.ai_family=AF_INET6};
static struct addrinfo original = {.ai_family=AF_INET, .ai_next=&original_v6};
static struct addrinfo control = {.ai_family=AF_INET6};
static int mode(const char *name) { const char *s=getenv("CASE"); return s && strstr(s,name); }
int getaddrinfo(const char *n, const char *s, const struct addrinfo *h, struct addrinfo **r) {
    (void)n; (void)s; (void)h;
    if (mode("host_check")) {
        assert(!strcmp(n,"store.steampowered.com") || !strcmp(n,"api.steampowered.com"));
        assert(!strcmp(s,"80") || !strcmp(s,"443"));
        assert(h->ai_family==AF_UNSPEC && h->ai_socktype==SOCK_STREAM && h->ai_protocol==IPPROTO_TCP && !h->ai_flags);
    }
    calls++;
    if (mode("default_fail")) { *r=NULL; errno=ENOENT; return EAI_NONAME; }
    *r=&original;
    errno=79;
    return 0;
}
void freeaddrinfo(struct addrinfo *r) {
    if (r==&original) original_frees++;
    else { assert(r==&control); control_frees++; }
    errno=97;
}
int android_getprocnetwork(net_handle_t *n) {
    binding_calls++;
    *n=mode("bound_match")?123:mode("bound_other")?456:0;
    if (mode("binding_error")) { errno=EPERM; return -1; }
    return 0;
}
int android_getprocdns(net_handle_t *n) { return android_getprocnetwork(n); }
int android_getaddrinfofornetwork(net_handle_t n, const char *node, const char *service,
                                 const struct addrinfo *h, struct addrinfo **r) {
    assert(n==123);
    assert(!strcmp(node,"store.steampowered.com") || !strcmp(node,"api.steampowered.com"));
    assert(!strcmp(service,"80") || !strcmp(service,"443"));
    assert(h->ai_family==AF_UNSPEC && h->ai_socktype==SOCK_STREAM && h->ai_protocol==IPPROTO_TCP && !h->ai_flags);
    active_calls++;
    if (mode("hang")) for (;;) pause();
    if (mode("active_fail")) { *r=NULL; errno=EPERM; return EAI_SYSTEM; }
    *r=&control; errno=0; return 0;
}

int android_res_nquery(net_handle_t n, const char *node, int cls, int type, uint32_t flags) {
    assert(n==123 && cls==1 && (type==1 || type==28) && flags==0);
    assert(!strcmp(node,"store.steampowered.com") || !strcmp(node,"api.steampowered.com"));
    assert(query_fd==-1 && writer_fd==-1);
    raw_queries++;
    if (mode("raw_query_fail")) return -EACCES;
    int fds[2]; assert(pipe(fds)==0);
    query_fd=fds[0]; writer_fd=fds[1]; query_type=type;
    if (!mode("raw_timeout")) assert(write(writer_fd,"x",1)==1);
    return query_fd;
}
void android_res_cancel(int fd) {
    assert(fd==query_fd && writer_fd>=0);
    assert(close(fd)==0); assert(close(writer_fd)==0);
    query_fd=writer_fd=-1;
    raw_cancels++; errno=97;
}
int android_res_nresult(int fd, int *rcode, uint8_t *answer, size_t size) {
    assert(fd==query_fd && writer_fd>=0 && size>=2048);
    assert(close(fd)==0); assert(close(writer_fd)==0);
    query_fd=writer_fd=-1; raw_results++;
    if (mode("raw_result_fail")) return -EIO;
    memset(answer,0,size);
    *rcode=mode("raw_nxdomain")?3:0;
    if (mode("raw_malformed")) return 3;
    answer[2]=mode("raw_tc")?0x83:0x81; answer[3]=0x80 | *rcode;
    answer[5]=1; answer[7]=mode("raw_nxdomain")?0:mode("raw_many")?65:1;
    // Question "a", followed by compressed owner names; real system parser below.
    answer[12]=1; answer[13]='a'; answer[14]=0; answer[16]=query_type; answer[18]=1;
    int pos=19;
    for (int i=0; i<answer[7]; i++) {
        int length=mode("raw_bad_length")?3:query_type==1?4:16;
        const uint8_t record[]={0xc0,0x0c,0,(uint8_t)query_type,0,1,0,0,0,1,0,(uint8_t)length};
        memcpy(answer+pos,record,sizeof(record)); pos+=sizeof(record);
        memset(answer+pos,1,length); pos+=length;
    }
    errno=97; return pos;
}
'''
MAIN = r'''
#include <assert.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern int calls, active_calls, binding_calls, original_frees, control_frees;
extern int raw_queries, raw_results, raw_cancels;
int main(void) {
    const char *mode=getenv("CASE");
    int fail=mode && strstr(mode,"default_fail");
    struct addrinfo h={.ai_family=AF_UNSPEC,.ai_socktype=SOCK_STREAM,.ai_protocol=IPPROTO_TCP}, *r;
    const char *names[]={"store.steampowered.com","api.steampowered.com","private.invalid"};
    const char *ports[]={"80","443","80"};
    for (int round=0; round<3; round++) for (int i=0;i<3;i++) {
        if (mode && strstr(mode,"wrong_hints")) h.ai_family=AF_INET;
        errno=66; r=NULL;
        int ret=getaddrinfo(names[i],ports[i],&h,&r);
        assert(ret==(fail?EAI_NONAME:0));
        assert(errno==(fail?ENOENT:79));
        assert(original_frees==(fail?0:round*3+i));
        if (!fail) { assert(r && r->ai_family==AF_INET && r->ai_next->ai_family==AF_INET6); freeaddrinfo(r); }
    }
    assert(calls==9);
    printf("CHECK calls=%d active=%d bindings=%d original_free=%d control_free=%d\n",
        calls,active_calls,binding_calls,original_frees,control_frees);
    printf("RAW_CHECK queries=%d results=%d cancels=%d\n",raw_queries,raw_results,raw_cancels);
}
'''


class NativeResolverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='aura-native-dns-')
        cls.work = p = Path(cls.temp.name)
        (p/'android').mkdir()
        (p/'android/multinetwork.h').write_text(HEADER)
        (p/'mock.c').write_text(MOCK)
        (p/'main.c').write_text(MAIN)
        for source, output in [(p/'mock.c', 'mock.so'),
                               (ROOT/'engine/src/main/cpp/aura_network_probe/network_probe.c', 'observer.so')]:
            extra = [str(ROOT/'engine/src/main/cpp/aura_network_probe/raw_dns.c'),'-lresolv'] if output=='observer.so' else []
            subprocess.run(['cc','-shared','-fPIC','-Wall','-Wextra','-Werror','-I'+str(p),
                            str(source),*extra,'-ldl','-pthread','-o',str(p/output)], check=True)
        subprocess.run(['cc','-Wall','-Wextra','-Werror',str(p/'main.c'),str(p/'mock.so'),
                        '-Wl,-rpath,'+str(p),'-o',str(p/'main')], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, case='', enabled='1', handle='123', timeout=3):
        env = dict(os.environ, LD_PRELOAD=str(self.work/'observer.so'), CASE=case,
                   AURA_NETWORK_PROBE=enabled, AURA_NETWORK_HANDLE=handle)
        result = subprocess.run([self.work/'main'], env=env, capture_output=True, text=True, timeout=timeout)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('private.invalid', result.stdout)
        self.assertNotIn('steampowered.com', result.stdout)
        self.assertLess(len(result.stdout), 8192)
        return result.stdout

    def test_same_answers_and_errno_are_returned(self):
        text = self.run_case()
        self.assertEqual(text.count('stage=DEFAULT_DNS '), 2)
        self.assertEqual(text.count('stage=ACTIVE_DNS '), 2)
        self.assertEqual(text.count('target=RUNTIME stage=DONE '), 1)
        self.assertIn('active=2 bindings=4 original_free=9 control_free=2', text)
        self.assertIn('stage=DEFAULT_V4 code=0 value=1 family=2', text)
        self.assertIn('stage=DEFAULT_V6 code=0 value=1 family=23', text)
        self.assertEqual(text.count('stage=RAW_ANSWERS code=0 value=1'),4)
        self.assertEqual(text.count('target=RUNTIME stage=RAW_DONE'),1)
        self.assertIn('RAW_CHECK queries=4 results=4 cancels=0', text)

    def test_default_failure_is_preserved_even_if_active_succeeds(self):
        text = self.run_case('default_fail')
        self.assertIn('stage=DEFAULT_DNS code=-2 value=2', text)  # host libc's EAI_NONAME/ENOENT
        self.assertIn('stage=ACTIVE_DNS code=0 value=0', text)
        self.assertIn('original_free=0 control_free=2', text)

    def test_active_failure_does_not_replace_success(self):
        text = self.run_case('active_fail')
        self.assertIn('stage=ACTIVE_DNS code=-11 value=1', text)  # host EAI_SYSTEM/EPERM
        self.assertIn('original_free=9 control_free=0', text)

    def test_disabled_preload_is_inert(self):
        text = self.run_case(enabled='0')
        self.assertNotIn('NET_NATIVE', text)
        self.assertIn('active=0 bindings=0', text)
        self.assertIn('RAW_CHECK queries=0 results=0 cancels=0', text)

    def test_other_queries_and_hints_are_not_observed(self):
        text = self.run_case('wrong_hints')
        self.assertNotIn('NET_NATIVE', text)
        self.assertIn('active=0 bindings=0', text)

    def test_unavailable_network_still_retains_default_evidence(self):
        for handle in ['0','','-1','123x',' 123','18446744073709551616']:
            with self.subTest(handle=handle):
                text = self.run_case(handle=handle)
                self.assertIn('stage=ACTIVE_UNAVAILABLE', text)
                self.assertNotIn('stage=ACTIVE_DNS', text)
                self.assertIn('active=0 bindings=4', text)
                self.assertNotIn('stage=RAW_DONE', text)
                self.assertIn('RAW_CHECK queries=0 results=0 cancels=0', text)

    def test_raw_error_namespaces_and_fd_lifetime(self):
        for case, stage, code, results in [('raw_query_fail','RAW_QUERY',-13,0),('raw_result_fail','RAW_RESULT',-5,4)]:
            with self.subTest(case=case):
                text=self.run_case(case)
                self.assertEqual(text.count(f'stage={stage} code={code}'),4)
                self.assertNotIn('stage=RAW_RCODE',text)
                self.assertIn(f'RAW_CHECK queries=4 results={results} cancels=0',text)
                self.assertIn('original_free=9 control_free=2',text)

    def test_raw_nxdomain_is_not_transport_or_parse_failure(self):
        text=self.run_case('raw_nxdomain')
        self.assertEqual(text.count('stage=RAW_RCODE code=3'),4)
        self.assertEqual(text.count('stage=RAW_ANSWERS code=0 value=0'),4)

    def test_raw_malformed_truncated_oversized_or_wrong_length_answers_are_not_counted(self):
        for case,code in [('raw_malformed',-74),('raw_tc',-90),('raw_many',-75),('raw_bad_length',-74)]:
            with self.subTest(case=case):
                text=self.run_case(case)
                self.assertEqual(text.count(f'stage=RAW_ANSWERS code={code} value=-1'),4)
                self.assertIn('RAW_CHECK queries=4 results=4 cancels=0',text)

    def test_raw_timeout_cancels_only_owned_unconsumed_fd(self):
        text=self.run_case('raw_timeout',timeout=12)
        self.assertEqual(text.count('stage=RAW_WAIT code=-110'),4)
        self.assertNotIn('stage=RAW_RESULT',text)
        self.assertIn('RAW_CHECK queries=4 results=0 cancels=4',text)

    def test_bindings_are_only_relationships(self):
        for mode, value in [('',0),('bound_match',1),('bound_other',2),('binding_error',3)]:
            with self.subTest(mode=mode):
                text = self.run_case(mode)
                code = 1 if value==3 else 0
                self.assertIn(f'stage=PROCESS_DNS code={code} value={value}',text)
                self.assertIn(f'stage=PROCESS_NETWORK code={code} value={value}',text)

    def test_blocked_native_lookup_has_no_false_completion_and_is_killable(self):
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            self.run_case('hang',timeout=0.25)
        self.assertIn(b'stage=ACTIVE_BEGIN',caught.exception.stdout)
        self.assertNotIn(b'stage=DONE',caught.exception.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
