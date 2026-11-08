#!/usr/bin/env python3
"""Real Chromium fetch/CORS tests; report the explicitly selected backend and endpoint scope."""
import argparse, asyncio, json, os, pathlib, shutil
from playwright.async_api import async_playwright

async def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--page-url',default='http://127.0.0.1:8081/')
    parser.add_argument('--api-base',default='http://127.0.0.1:8080')
    parser.add_argument('--backend-label',choices=['fake','native-candybox'],default='fake')
    args=parser.parse_args()
    screenshot=pathlib.Path(__file__).resolve().parents[1]/'target/browser-demo.png'
    screenshot.parent.mkdir(exist_ok=True)
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=True,executable_path=os.environ.get('CHROMIUM_PATH',shutil.which('chromium')),args=['--no-sandbox'])
        page=await browser.new_page()
        await page.goto(args.page_url)
        result=await page.evaluate('''async ({base}) => {
          const {createNamespace,storageExample} = await import('./storage.js');
          const ns=await createNamespace(base), store=storageExample(base,ns.id);
          await store.setItem('', '');
          if(await store.getItem('')!=='') throw Error('empty string/key');
          await store.setJSON('null',null);
          if(JSON.stringify(await store.getJSON('null'))!==JSON.stringify({found:true,value:null})) throw Error('JSON null');
          if((await store.getJSON('missing')).found!==false) throw Error('missing');
          await store.setItem('😀/%', 'ciphertext:SGVsbG8=');
          if(await store.getItem('😀/%')!=='ciphertext:SGVsbG8=') throw Error('unicode/reserved/encrypted');
          await store.setItem('😀/%', 'replacement');
          if(await store.getItem('😀/%')!=='replacement') throw Error('overwrite');
          const keys=[]; for await(const k of store.keys()) keys.push(k);
          if(keys.length!==3) throw Error('list');
          const path=`${base}/v1/namespaces/${ns.id}/items/~`;
          const preflight=await fetch(path,{method:'OPTIONS',headers:{'Content-Type':'application/json','X-Request-ID':'browser-test'}});
          if(preflight.status!==204) throw Error('preflight');
          const errors=[];
          for(const [body,type,wanted] of [['{"value":null}','application/json',400],['x','text/plain',415],['{"value":"'+'x'.repeat(1048577)+'"}','application/json',413]]) {
            const r=await fetch(path,{method:'PUT',headers:{'Content-Type':type},body});
            if(r.status!==wanted || !r.headers.get('X-Request-ID')) throw Error('error CORS '+wanted);
            errors.push(r.status);
          }
          await store.removeItem('😀/%'); await store.clear(); if(await store.length()!==0) throw Error('clear');
          return {namespace:ns.id,keys,errors,preflight:preflight.status};
        }''', {'base':args.api_base})
        # Sandbox without allow-same-origin creates an actual opaque/null-origin browsing context.
        opaque=await page.evaluate(r'''({base}) => new Promise((resolve,reject) => {
          window.addEventListener('message', e => {if(e.data?.opaqueTest) e.data.error ? reject(Error(e.data.error)) : resolve(e.data);},{once:true});
          const frame=document.createElement('iframe');frame.sandbox='allow-scripts';
          frame.srcdoc=`<script>fetch('${base}/v1/namespaces',{method:'POST',credentials:'omit'}).then(async r=>parent.postMessage({opaqueTest:true,status:r.status,id:(await r.json()).id},'*')).catch(e=>parent.postMessage({opaqueTest:true,error:String(e)},'*'))<\/script>`;
          document.body.append(frame);
        })''', {'base':args.api_base})
        assert opaque['status']==201, opaque
        await page.screenshot(path=str(screenshot),full_page=True)
        await browser.close()
        print(json.dumps({'crossOrigin':result,'opaqueOrigin':opaque,'scope':f'local HTTP + {args.backend_label} backend; public HTTPS launch drill pending'},indent=2))

if __name__=='__main__': asyncio.run(main())
