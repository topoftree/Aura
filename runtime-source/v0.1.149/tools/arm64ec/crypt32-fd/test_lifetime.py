#!/usr/bin/env python3
"""Run the real before/after import functions with deterministic FD reuse.

The simulated FILE/descriptor boundary exposes the old double close. It does
not bypass Android fdsan and is not a Wine/Steam device test.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def function(text, name):
    start = text.index('static void '+name+'(')
    brace = text.index('{',start); depth=1; end=brace+1
    while depth:
        depth += (text[end]=='{') - (text[end]=='}'); end+=1
    return text[start:end]


PRELUDE = r'''
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <sys/stat.h>
#include <fcntl.h>
typedef int BOOL;
typedef const char *LPCSTR;
#define TRUE 1
#define FALSE 0
#define TRACE(...) ((void)0)
#define WARN(...) ((void)0)
#define ERR(...) ((void)0)
struct DynamicBuffer { unsigned allocated,used; char *data; };
static int mode, live, original_closes, unrelated_closes, duplicate_closes, line_index, certs;
static FILE *mock_fdopen(int fd,const char *m) { assert(fd==7 && live==1 && !strcmp(m,"r")); return mode==2?NULL:(FILE *)1; }
static int mock_close(int fd) { assert(fd==7); if(live==1)original_closes++; else if(live==2)unrelated_closes++; else duplicate_closes++; live=0; return 0; }
static int mock_fclose(FILE *f) { assert(f==(FILE*)1 && live==1);original_closes++;live=mode==1?2:0;return mode==4?EOF:0; }
static char *mock_fgets(char *p,int n,FILE *f) {
 const char *lines[]={"-----BEGIN CERTIFICATE-----\n","AQID\n","-----END CERTIFICATE-----\n"};
 assert(f==(FILE*)1); if(mode==3 || line_index==3)return NULL;
 assert((int)strlen(lines[line_index])<n);strcpy(p,lines[line_index++]);return p;
}
static int mock_open(const char *p,int flags) { (void)p; assert(flags==O_RDONLY);if(mode==8)return -1;live=1;return 7; }
static int mock_fstat(int fd,struct stat *st) { assert(fd==7);st->st_mode=mode==6?S_IFDIR:mode==7?S_IFIFO:S_IFREG;return mode==5?-1:0; }
static void reset_buffer(struct DynamicBuffer *b) { b->used=0; }
static void add_line_to_buffer(struct DynamicBuffer *b,const char *line) {free(b->data);b->data=strdup(line);}
static BOOL base64_to_cert(const char *text) { assert(!strcmp(text,"AQID\n"));certs++;return TRUE; }
static void import_certs_from_dir(const char *p) { (void)p; }
#define fdopen mock_fdopen
#define fclose mock_fclose
#define fgets mock_fgets
#define open mock_open
#define close mock_close
#define fstat mock_fstat
'''
MAIN = r'''
int main(int argc,char **argv) {
 assert(argc==2);int fixed=atoi(argv[1]);
 for(mode=0;mode<9;mode++) {
  live=original_closes=unrelated_closes=duplicate_closes=line_index=certs=0;
  import_certs_from_path("fixture",TRUE);
  int normal_stream=(mode==0||mode==1||mode==3||mode==4);
  assert(original_closes==(mode==8?0:1));
  assert(certs==((mode==0||mode==1||mode==4)?1:0));
  if(fixed) {assert(!duplicate_closes && !unrelated_closes);assert(live==(mode==1?2:0));}
  else if(normal_stream) {assert(duplicate_closes+unrelated_closes==1);assert(!live);}
  else {assert(!duplicate_closes && !unrelated_closes && !live);}
 }
 puts("9 cases passed");
}
'''


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline',type=Path,required=True);p.add_argument('--fixed',type=Path,required=True)
    p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    with tempfile.TemporaryDirectory(prefix='aura-crypt32-lifetime-') as t:
        path=Path(t)
        for fixed,source in [(0,a.baseline),(1,a.fixed)]:
            text=source.read_text()
            # Select the definition rather than the forward declaration.
            path_start=text.index('static void import_certs_from_path(LPCSTR path, BOOL allow_dir)\n{')
            body=PRELUDE+'\n'+function(text,'import_certs_from_file')+'\n'+function(text[path_start:],'import_certs_from_path')+'\n'+MAIN
            (path/'test.c').write_text(body)
            subprocess.run(['cc','-Wall','-Wextra','-Wno-unused-but-set-variable','-fsanitize=undefined','-fno-sanitize-recover=all',str(path/'test.c'),'-o',str(path/'test')],check=True)
            subprocess.run([str(path/'test'),str(fixed)],check=True)
    report={'baseline_cases':9,'fixed_cases':9,'old_duplicate_close_negative_controls':4,
        'cases':['certificate_import','concurrent_number_reuse','fdopen_failure','read_failure','fclose_failure','fstat_failure','directory','unsupported_type','open_failure'],
        'method':'Actual source function bodies; deterministic libc/FD boundary model with UBSan',
        'certificates_preserved':True,'device_tested':False}
    a.report.write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':main()
