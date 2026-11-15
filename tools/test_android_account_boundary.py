#!/usr/bin/env python3
"""Verify automatic sign-out cannot publish an account's data into Guest."""
import importlib.util
import json
import pathlib
import shutil
import subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('maxd_account_fixture',ROOT/'shared/native-app/tests/account_test.py')
fixtures=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixtures)

class BoundaryBackend(fixtures.Backend):
    def respond(self,body):
        if self.server.state.get('user_offline')and self.path=='/auth/v1/user':
            self.send_json(503,{});return
        if self.server.state.get('expire_now')and self.path.endswith('sync_pull_library'):
            self.send_json(401,{});return
        super().respond(body)

class Environment(fixtures.AccountTests):
    def launch(self,ca):self.ca=ca

def main():
    environment=Environment();environment.setUp()
    environment.server.RequestHandlerClass=BoundaryBackend
    assets=environment.path/'assets';shutil.copytree(ROOT/'app/src/main/assets/core',assets)
    shutil.copyfile(environment.config,assets/'account-config.json')
    count=0
    (ROOT/'build/shared-tests').mkdir(parents=True,exist_ok=True)
    try:
        with (ROOT/'build/shared-tests/android-account-boundary-native.log').open('w')as log:
            process=subprocess.Popen([ROOT/'build/host-core/nuvio_core_cli'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1)
            def request(command,arguments=None):
                nonlocal count
                process.stdin.write(json.dumps({'command':command,'arguments':arguments or {}})+'\n');process.stdin.flush()
                response=json.loads(process.stdout.readline());count+=1;return response
            def success(command,arguments=None):
                response=request(command,arguments);assert response['ok'],(command,response)
                return response['result']
            try:
                success('init',{'dataDir':str(environment.path/'data'),'assetsDir':str(assets),'caBundle':str(environment.ca)})
                success('settings.save',{'settings':{'gain':75}})
                success('library.save',{'item':{'_id':'guest-only','name':'Guest title','removed':False}})
                pairing=success('account.pair');environment.state['approved']=True
                response=request('account.poll',{'code':pairing['code']});assert response['ok']and response['context']['saved']
                account_key=response['context']['profileKey']
                success('settings.save',{'profileKey':account_key,'settings':{'gain':150}})
                success('library.save',{'profileKey':account_key,'item':{'_id':'account-only','name':'Account title','removed':False}})
                environment.state.update(expire_now=True,revoked=True)
                response=request('library',{'profileKey':account_key,'sync':True})
                assert response['ok']is False,'A revoked account must not return its cached library as Guest'
                assert response['context']['saved']is False and response['context']['profileKey']==''
                assert response['context']['profilePath']==str(environment.path/'data/guest')
                assert success('settings',{'profileKey':''})['gain']==75
                rows=success('library',{'profileKey':'','sync':False})
                assert [row['_id']for row in rows]==['guest-only'],rows
                response=request('settings',{'profileKey':account_key});assert response['ok']is False and response['context']['profileKey']==''
                environment.state.update(expire_now=False,revoked=False,user_offline=True)
                pairing=success('account.pair')
                response=request('account.poll',{'code':pairing['code']});assert response['ok']and response['context']['pending']
                pending_key=response['context']['profileKey'];pending_path=response['context']['profilePath']
                assert '/pending/' in pending_path and pending_path!=str(environment.path/'data/guest')
                success('settings.save',{'profileKey':pending_key,'settings':{'gain':225}})
                environment.state['user_offline']=False
                response=request('profiles',{'profileKey':pending_key})
                assert response['context']['saved']and not response['context']['pending']
                verified_key=response['context']['profileKey']
                assert response['context']['profilePath']!=pending_path
                assert success('settings',{'profileKey':verified_key})['gain']==150,'Pending settings must not overwrite verified account settings'
                success('account.logout',{'profileKey':verified_key})
                assert success('settings',{'profileKey':''})['gain']==75
            finally:
                process.stdin.close()
                try:process.wait(timeout=20)
                except subprocess.TimeoutExpired:process.kill();process.wait()
    finally:
        environment.server.shutdown();environment.server.server_close();environment.thread.join(timeout=5);environment.temp.cleanup()
    print(f'PASS Android account boundary: {count} calls; revoked-session context, guest isolation, unverified-login storage, verified settings preservation, stale profile rejection')

if __name__=='__main__':main()
