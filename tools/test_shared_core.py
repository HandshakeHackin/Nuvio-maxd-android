#!/usr/bin/env python3
"""Run the imported service regression fixtures against the Android-shared sources."""
import concurrent.futures
import argparse
import json
import os
import pathlib
import shlex
import subprocess
import sys

ROOT=pathlib.Path(__file__).resolve().parents[1]
CORE=ROOT/'shared/native-app'
RESULTS=ROOT/'build/shared-tests'


def execute(name,commands):
    output=[]
    try:
        for command in commands:
            result=subprocess.run([str(arg) for arg in command],cwd=CORE,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            output.append(result.stdout)
            if result.returncode:
                raise RuntimeError('Command failed: '+name)
        ok=True
    except Exception as error:
        output.append(str(error));ok=False
    RESULTS.mkdir(parents=True,exist_ok=True)
    (RESULTS/(name+'.log')).write_text('\n'.join(output))
    print(('PASS ' if ok else 'FAIL ')+name,flush=True)
    if not ok:print('\n'.join(output)[-6000:],flush=True)
    return name,ok


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--suites', nargs='+', help='Run selected suites and preserve other recorded results')
    arguments=parser.parse_args()
    (CORE/'test-output').mkdir(exist_ok=True)
    suites={name:[['bash',CORE/'tests'/(name+'.sh')]] for name in [
        'live_tv','live_guide','iptv_library','episode','connected_services','tracking',
        'media_servers','plex','instant_playback','series_updates','trailers']}
    compiler=os.environ.get('HOST_CXX','g++')
    flags=['-std=c++20','-O1','-pthread','-I'+str(CORE/'src'),'-I'+str(CORE/'third_party'),'-I'+str(CORE/'third_party/hui/src')]
    libraries=shlex.split(subprocess.check_output(['pkg-config','--cflags','--libs','openssl','libcurl'],text=True))
    account=[compiler,*flags,CORE/'tests/account_host.cpp',*[CORE/'src'/(name+'.cpp') for name in ['nuvio_account','watch_sync','tracking','stremio','http','util']],*libraries,'-o',CORE/'test-output/account-host']
    suites['account']=[account,[sys.executable,CORE/'tests/account_test.py']]
    quickjs=ROOT/'.native-deps/sources/quickjs'
    if not (quickjs/'libquickjs.a').exists():
        subprocess.run(['make','-C',str(quickjs),'-j4','libquickjs.a'],check=True)
    providers=[compiler,*flags,'-DNUVIO_HAVE_PROVIDERS','-DPROVIDER_TEST_DIAGNOSTICS','-I'+str(quickjs),CORE/'tests/providers_host.cpp',*[CORE/'src'/(name+'.cpp') for name in ['providers','trailers','stremio','http','util']],quickjs/'libquickjs.a',*libraries,'-ldl','-lm','-o',CORE/'test-output/providers-host']
    suites['providers']=[providers,[CORE/'test-output/providers-host',CORE/'third_party/provider-runtime/runtime.js']]
    previous={}
    if arguments.suites:
        unknown=set(arguments.suites)-set(suites)
        if unknown:parser.error('Unknown suites: '+', '.join(sorted(unknown)))
        if (RESULTS/'results.json').exists():previous=json.loads((RESULTS/'results.json').read_text())
        suites={name:commands for name,commands in suites.items() if name in arguments.suites}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2)as pool:
        results={**previous,**dict(pool.map(lambda item:execute(*item),suites.items()))}
    (RESULTS/'results.json').write_text(json.dumps(results,indent=2)+'\n')
    return 0 if all(results.values())else 1


if __name__=='__main__':raise SystemExit(main())
