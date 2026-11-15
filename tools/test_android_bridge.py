#!/usr/bin/env python3
"""Exercise the Android dispatcher and streaming reader against owned local fixtures.

Build the host nuvio_core_cli CMake target first. Host builds disable DHT, UPnP
and the default public trackers, so this test uses only its localhost web seed.
"""
import hashlib
import http.server
import json
import pathlib
import selectors
import subprocess
import tempfile
import threading
import urllib.parse

ROOT=pathlib.Path(__file__).resolve().parents[1]
DATA=bytes((index*17+31)%256 for index in range(131072))

def bencode(value):
    if isinstance(value,int):return b'i'+str(value).encode()+b'e'
    if isinstance(value,str):value=value.encode()
    if isinstance(value,bytes):return str(len(value)).encode()+b':'+value
    if isinstance(value,list):return b'l'+b''.join(map(bencode,value))+b'e'
    return b'd'+b''.join(bencode(key)+bencode(value[key])for key in sorted(value))+b'e'

class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_POST(self):
        self.body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or '{}')
        self.do_GET()
    def do_GET(self):
        path=urllib.parse.unquote(self.path).split('?',1)[0]
        origin=self.server.origin
        if path.startswith('/jellyfin/'):
            endpoint=path.removeprefix('/jellyfin')
            if endpoint=='/System/Info/Public':return self.send({'ProductName':'Jellyfin','ServerName':'Owned server'})
            if endpoint=='/Users/AuthenticateByName':
                assert self.body=={'Username':'alice','Pw':'test-password'}
                return self.send({'User':{'Id':'user-1'},'AccessToken':'private-fixture-token'})
            assert 'Token="private-fixture-token"' in self.headers.get('Authorization','')
            if endpoint.endswith('/Images/Primary'):return self.send(b'owned-image-bytes','image/png')
            if endpoint.startswith('/Sessions/'):return self.send({})
            if endpoint.endswith('/PlaybackInfo'):return self.send({'PlaySessionId':'session-1','MediaSources':[{'Id':'source-1','SupportsDirectPlay':True,'SupportsDirectStream':False}]})
            if endpoint.endswith('/Items/movie-1'):return self.send({'Id':'movie-1','Name':'Owned server movie','Type':'Movie','UserData':{'PlaybackPositionTicks':250000000},'RunTimeTicks':9000000000})
            return self.send({'Items':[{'Id':'movie-1','Name':'Owned server movie','Type':'Movie','UserData':{'PlaybackPositionTicks':250000000},'RunTimeTicks':9000000000}],'TotalRecordCount':1})
        if path=='/player_api.php':
            query=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            assert query['username']==['fixture'] and query['password']==['owned-password']
            action=query.get('action',[''])[0]
            values={
                '':{'user_info':{'auth':1,'status':'Active'}},
                'get_vod_categories':[{'category_id':'1','category_name':'Owned movies'}],
                'get_series_categories':[{'category_id':'2','category_name':'Owned series'}],
                'get_vod_streams':[{'stream_id':10,'name':'Fixture movie','category_id':'1','container_extension':'mp4'}],
                'get_series':[{'series_id':20,'name':'Fixture series','category_id':'2'}],
                'get_series_info':{'episodes':{'1':[{'id':30,'episode_num':1,'title':'Owned episode','container_extension':'mp4'}]}}
            }
            return self.send(values[action])
        manifest={'id':'android-fixture','name':'Android bridge fixture','version':'1.0.0','types':['movie','series'],
                  'resources':['catalog','meta','stream','subtitles'],
                  'catalogs':[{'id':'fixture','type':'movie','name':'Fixture movies','extra':[{'name':'search'},{'name':'skip'},{'name':'genre','options':['Drama']}]}]}
        if path=='/manifest.json':return self.send(manifest)
        if path.startswith('/catalog/'):return self.send({'metas':[{'id':'tt123','type':'movie','name':'Owned test title','poster':origin+'/poster.png'}]})
        if path.startswith('/meta/'):return self.send({'meta':{'id':'tt123','type':'movie','name':'Owned test title','description':'Fixture metadata'}})
        if path.startswith('/stream/'):return self.send({'streams':[
            {'name':'Direct fixture 1080p','url':origin+'/owned.mp4','behaviorHints':{'proxyHeaders':{'request':{'Authorization':'Bearer fixture-only'}}}},
            {'name':'Torrent fixture','infoHash':'a'*40}]})
        if path.startswith('/subtitles/'):return self.send({'subtitles':[{'id':'fixture-en','lang':'eng','url':origin+'/captions.vtt'}]})
        if path=='/channels.m3u':return self.send('#EXTM3U\n#EXTINF:-1 tvg-id="fixture" group-title="Local",Owned channel\n'+origin+'/owned.mp4\n','application/x-mpegURL')
        if path=='/fixture.torrent':return self.send(self.server.torrent,'application/x-bittorrent')
        if path=='/owned.mp4':
            if self.headers.get('Range'):
                start,end=self.headers['Range'].removeprefix('bytes=').split('-');start=int(start);end=int(end)if end else len(DATA)-1
                self.send_response(206);self.send_header('Content-Range',f'bytes {start}-{end}/{len(DATA)}');self.send_header('Content-Length',str(end-start+1));self.end_headers();self.wfile.write(DATA[start:end+1]);return
            return self.send(DATA,'application/octet-stream')
        self.send_error(404)
    def send(self,value,content_type='application/json'):
        data=value if isinstance(value,bytes)else (value.encode()if isinstance(value,str)else json.dumps(value).encode())
        self.send_response(200);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)

def main():
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture)
    server.origin='http://127.0.0.1:'+str(server.server_port)
    info={'length':len(DATA),'name':'owned.mp4','piece length':16384,'pieces':b''.join(hashlib.sha1(DATA[offset:offset+16384]).digest()for offset in range(0,len(DATA),16384))}
    server.torrent=bencode({'info':info,'url-list':[server.origin+'/owned.mp4']})
    threading.Thread(target=server.serve_forever,daemon=True).start()
    count=0
    (ROOT/'build/shared-tests').mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='nuvio-bridge-')as folder:
        data=pathlib.Path(folder);guest=data/'guest';guest.mkdir()
        # Prevent optional default addon discovery from contacting the Internet.
        (guest/'android-state.json').write_text(json.dumps({'addons':[{'transportUrl':server.origin+'/manifest.json','manifest':{'id':'android-fixture','name':'Fixture','types':['movie','series'],'resources':['catalog','meta','stream','subtitles'],'catalogs':[{'id':'fixture','type':'movie','name':'Movies','extra':[{'name':'search'}]}]}}]}))
        with (ROOT/'build/shared-tests/android-bridge-native.log').open('w')as log:
            process=subprocess.Popen([ROOT/'build/host-core/nuvio_core_cli'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1)
            selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
            def request(command,arguments=None,ok=True):
                nonlocal count
                process.stdin.write(json.dumps({'command':command,'arguments':arguments or {}})+'\n');process.stdin.flush()
                if not selector.select(70):raise AssertionError('Dispatcher timed out: '+command)
                response=json.loads(process.stdout.readline());assert response['ok']==ok,(command,response)
                count+=1
                return response.get('result')
            try:
                context=request('init',{'dataDir':str(data),'assetsDir':str(ROOT/'app/src/main/assets/core'),'caBundle':'/etc/ssl/certs/ca-certificates.crt'})
                assert context['saved']is False and context['profileKey']==''
                request('settings',{'profileKey':'old-account'},False)
                assert request('settings.save',{'settings':{'gain':125,'introSkip':False}})['gain']==125
                assert request('settings')['gain']==125
                request('addons.add',{'url':server.origin+'/manifest.json'})
                catalogs=request('catalogs');assert catalogs[0]['id']=='fixture'
                assert request('catalog',catalogs[0])[0]['name']=='Owned test title'
                assert request('search',{'query':'Owned'})[0]['id']=='tt123'
                assert request('detail',{'id':'tt123','type':'movie'})['description']=='Fixture metadata'
                sources=request('streams',{'id':'tt123','videoId':'tt123','type':'movie','refresh':True})
                assert len(sources['streams'])==1,'Raw torrents must be hidden until explicitly enabled'
                direct=sources['streams'][0]
                assert 'fixture-only' in json.dumps(direct)
                assert request('streams.resolve',{'stream':direct})['url']==server.origin+'/owned.mp4'
                assert request('subtitles',{'type':'movie','videoId':'tt123'})[0]['lang']=='eng'
                request('resume.put',{'id':'owned','state':{'timeOffset':12000}})
                assert request('resume.get',{'id':'owned'})['timeOffset']==12000
                request('library.put',{'item':{'_id':'tt123','type':'movie','state':{'timeOffset':9000,'duration':60000}}})
                row=request('library',{'sync':False})[0];assert row['state']['timeOffset']==9000 and row['removed']is True
                request('library.save',{'item':{'_id':'tt123','type':'movie','name':'Owned saved title','removed':False}})
                row=request('library',{'sync':False})[0];assert row['removed']is False and row['state']['timeOffset']==9000
                request('watched.set',{'id':'tt123','videoId':'tt123','watched':True})
                assert request('watched')['tt123']is True
                request('live.add',{'source':{'type':'m3u','name':'Local','url':server.origin+'/channels.m3u'}})
                source=request('live.sources')[0]['id'];channels=request('live.channels',{'source':source})['channels'];assert len(channels)==1
                channel=channels[0]['id'];request('live.favorite',{'source':source,'id':channel})
                assert request('live.channels',{'source':source,'group':'Favorites'})['channels'][0]['favorite']is True
                assert request('live.play',{'source':source,'id':channel})['url']==server.origin+'/owned.mp4'
                assert request('live.recent')[0]['id']==channel
                request('live.add',{'source':{'type':'xtream','name':'Owned IPTV','url':server.origin,'username':'fixture','password':'owned-password'}})
                xtream=request('live.sources')[1]['id']
                assert request('iptv.probe',{'source':xtream})['movies'][0]['name']=='Owned movies'
                catalog=request('iptv.catalog',{'source':xtream,'kind':'movies','category':'1'})
                entry=catalog['entries'][0];assert entry['name']=='Fixture movie'
                assert '/movie/fixture/owned-password/10.mp4' in request('iptv.play',{'source':xtream,'kind':'movies','entry':entry})['url']
                assert request('iptv.search',{'query':'Fixture'})[0]['source']==xtream
                assert request('iptv.catalog',{'source':xtream,'kind':'episodes','category':'20'})['entries'][0]['episode']==1
                servers=request('servers.add',{'kind':'jellyfin','url':server.origin+'/jellyfin','username':'alice','password':'test-password'})
                server_id=servers[0]['id'];assert 'private-fixture-token' not in json.dumps(request('servers'))
                assert request('servers.browse',{'server':server_id,'type':'items'})['Items'][0]['Id']=='movie-1'
                image=request('servers.image',{'server':server_id,'id':'movie-1'})
                assert pathlib.Path(image['path']).read_bytes()==b'owned-image-bytes'
                playback=request('servers.play',{'server':server_id,'id':'movie-1','name':'Owned server movie','resume':25,'duration':900})
                assert playback['type']=='media-server' and playback['resume']==25
                request('servers.report',{'playback':playback,'position':30,'paused':False,'event':'start'})
                request('servers.remove',{'server':server_id});assert request('servers')==[]
                request('torrents.start',{'input':server.origin+'/fixture.torrent'},False)
                request('settings.save',{'settings':{'torrents':True}})
                torrent=request('torrents.start',{'input':server.origin+'/fixture.torrent'})
                assert torrent['hash']==hashlib.sha1(bencode(info)).hexdigest()
                received=b''
                while len(received)<4096:
                    read=request('test.torrent.read',{'hash':torrent['hash'],'index':0,'offset':32000+len(received),'length':4096-len(received)})
                    chunk=bytes.fromhex(read['hex']);assert chunk and read['size']==len(DATA)
                    received+=chunk
                assert received==DATA[32000:36096]
                read=request('test.torrent.read',{'hash':torrent['hash'],'index':0,'offset':len(DATA)-17,'length':4096})
                assert bytes.fromhex(read['hex'])==DATA[-17:]
                request('settings.save',{'settings':{'torrents':False}})
                request('torrents.web.start',ok=False)
                request('app.background');request('app.foreground')
            finally:
                process.stdin.close()
                try:process.wait(timeout=20)
                except subprocess.TimeoutExpired:process.kill();process.wait()
                selector.close()
    server.shutdown()
    print(f'PASS Android bridge: {count} dispatcher calls, profile rejection, addon/catalog/stream/subtitle flow, local progress/save, live favorites/recents, IPTV, media-server auth/artwork/playback/report, opt-in torrent and verified ranged reader bytes')

if __name__=='__main__':main()
