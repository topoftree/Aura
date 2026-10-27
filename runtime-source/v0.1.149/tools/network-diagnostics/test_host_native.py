#!/usr/bin/env python3
"""Run the production host JNI adapter with fake JNI arrays and resolver APIs.

Checks fixed inputs, error domains, bindings and ownership; no Android or network.
Pass --jdk to select the JNI headers used by the host compiler.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_native import ROOT, HEADER, MOCK

MAIN = r'''
#include <jni.h>
#include <netdb.h>
#include <errno.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern int calls, active_calls, binding_calls, original_frees, control_frees, raw_queries;
extern jintArray Java_com_winlator_core_NetworkHostNativeJni_query(JNIEnv *,jobject,jlong,jint,jboolean);
extern jintArray Java_com_winlator_core_NetworkHostNativeJni_readBindings(JNIEnv *,jobject,jlong);
static jint values[5];
static int length;
static jintArray create(JNIEnv *env, jsize count) {
    (void)env; assert(count==4 || count==5); length=count;
    memset(values,0,sizeof(values)); return (jintArray)values;
}
static void store(JNIEnv *env,jintArray array,jsize start,jsize count,const jint *data) {
    (void)env; assert(array==(jintArray)values && start==0 && count==length);
    memcpy(values,data,count*sizeof(jint));
}
int main(void) {
    const char *mode=getenv("CASE");
    struct JNINativeInterface_ table={.NewIntArray=create,.SetIntArrayRegion=store};
    JNIEnv env=&table;
    for (int target=0; target<2; ++target) {
        Java_com_winlator_core_NetworkHostNativeJni_readBindings(&env,NULL,123);
        int relation=strstr(mode,"binding_error")?3:strstr(mode,"bound_match")?1:strstr(mode,"bound_other")?2:0;
        assert(values[0]==(relation==3?EPERM:0) && values[2]==values[0]);
        assert(values[1]==relation && values[3]==relation);
        for (int active=0; active<2; ++active) {
            Java_com_winlator_core_NetworkHostNativeJni_query(&env,NULL,123,target,active);
            int fail=active?strstr(mode,"active_fail")!=NULL:strstr(mode,"default_fail")!=NULL;
            assert(values[0]==(fail?(active?EAI_SYSTEM:EAI_NONAME):0));
            assert(values[1]==(fail?(active?EPERM:ENOENT):(active?0:79)));
            assert(values[2]==(fail||active?0:1)); assert(values[3]==(fail?0:1));
            assert(values[4]==0);
        }
    }
    assert(calls==2 && active_calls==2 && binding_calls==4 && raw_queries==0);
    assert(original_frees==(strstr(mode,"default_fail")?0:2));
    assert(control_frees==(strstr(mode,"active_fail")?0:2));
    // Invalid target or missing explicit network never becomes an implicit DNS request.
    Java_com_winlator_core_NetworkHostNativeJni_query(&env,NULL,123,-1,0);
    assert(values[0]==EAI_SYSTEM && values[1]==EINVAL);
    Java_com_winlator_core_NetworkHostNativeJni_query(&env,NULL,123,2,0);
    assert(values[0]==EAI_SYSTEM && values[1]==EINVAL);
    Java_com_winlator_core_NetworkHostNativeJni_query(&env,NULL,0,0,1);
    assert(values[0]==EAI_SYSTEM && values[1]==EINVAL);
    assert(calls==2 && active_calls==2 && raw_queries==0);
    puts("HOST_JNI_OK");
}
'''

class HostAdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='aura-host-dns-')
        cls.work=p=Path(cls.temp.name)
        (p/'android').mkdir()
        (p/'android/multinetwork.h').write_text(HEADER)
        (p/'mock.c').write_text(MOCK)
        (p/'main.c').write_text(MAIN)
        subprocess.run(['cc','-Wall','-Wextra','-Werror','-I'+str(p),'-I'+str(JDK/'include'),
            '-I'+str(JDK/'include/linux'),str(p/'main.c'),str(p/'mock.c'),
            str(ROOT/'engine/src/main/cpp/aura_network_probe/host_resolver.c'),'-o',str(p/'main')],check=True)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_success_failures_and_all_binding_relations(self):
        for case in ['', 'default_fail','active_fail','default_fail active_fail','bound_match','bound_other','binding_error']:
            with self.subTest(case=case):
                result=subprocess.run([self.work/'main'],env=dict(os.environ,CASE='host_check '+case),
                    capture_output=True,text=True,timeout=3)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(result.stdout,'HOST_JNI_OK\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--jdk',type=Path,required=True)
    args,remaining=parser.parse_known_args()
    JDK=args.jdk
    unittest.main(argv=[__file__,*remaining],verbosity=2)
