/* Real UI acceptance against the local cloud, using synthetic photographs only. */
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const runtime=process.env.PLAYWRIGHT_MODULE||'/Users/nian_yh/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright';
const {chromium}=require(runtime);
const root=path.resolve(__dirname,'..');
const artifacts=path.join(root,'artifacts');
const steps=[];
async function record(name,fn){await fn();steps.push(name);console.log('PASS '+name)}
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 const ownerContext=await browser.newContext({viewport:{width:1440,height:1000}});
 const customerContext=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
 const owner=await ownerContext.newPage(),customer=await customerContext.newPage();
 const errors=[];for(const page of [owner,customer])page.on('pageerror',error=>errors.push(error.message));
 const email='qa-'+Date.now()+'@example.test',name='验收样例 · 秋日人像';
 try {
  await record('邀请注册与工作台',async()=>{
   await owner.goto('http://localhost:5177');
   await owner.getByRole('button',{name:'使用邀请码注册',exact:true}).click();
   await owner.getByLabel('邮箱',{exact:true}).fill(email);await owner.getByLabel('密码',{exact:true}).fill('Local-QA-Password-2026!');await owner.getByLabel('邀请码',{exact:true}).fill('local-dev-only');
   await owner.getByRole('button',{name:'创建账号',exact:true}).click();await owner.getByRole('button',{name:'新建拍摄项目',exact:true}).waitFor();
  });
  await record('新建项目与真实文件上传',async()=>{
   await owner.getByRole('button',{name:'新建拍摄项目',exact:true}).click();await owner.getByLabel('项目名称').fill(name);await owner.getByRole('button',{name:'创建项目',exact:true}).click();
   const choose=owner.waitForEvent('filechooser');await owner.getByRole('button',{name:'加入预览',exact:true}).click();await(await choose).setFiles([1,2,3].map(i=>path.join(artifacts,'fixtures','QA_DSC'+String(i).padStart(5,'0')+'.JPG')));
   await owner.getByRole('button',{name:'查看 QA_DSC00003.JPG',exact:true}).waitFor();assert.equal(await owner.locator('input[type=file]:visible').count(),0);
  });
  let shareUrl,code;
  await record('创建客户链接',async()=>{
   await owner.getByRole('button',{name:'客户分享',exact:true}).click();await owner.getByRole('button',{name:'生成7天有效链接',exact:true}).click();
   shareUrl=await owner.getByLabel('客户链接').inputValue();code=await owner.locator('.share-code strong').innerText();assert.equal(new URL(shareUrl).origin,'http://localhost:5177');
  });
  await record('访问码拒绝与正确解锁',async()=>{
   await customer.goto(shareUrl);await customer.getByRole('textbox',{name:'访问码'}).fill('000000');await customer.getByRole('button',{name:'打开我的照片'}).click();await customer.getByText('访问码错误',{exact:true}).waitFor();
   await customer.getByRole('textbox',{name:'访问码'}).fill(code);await customer.getByRole('button',{name:'打开我的照片'}).click();await customer.getByRole('heading',{name,exact:true}).waitFor();
  });
  await record('手机选片、留言与刷新保留',async()=>{
   await customer.getByRole('button',{name:'选择 QA_DSC00001.JPG',exact:true}).click();await customer.getByRole('button',{name:'选择 QA_DSC00002.JPG',exact:true}).click();
   await customer.getByRole('button',{name:'查看 QA_DSC00001.JPG',exact:true}).click();await customer.getByRole('textbox',{name:'给摄影师的留言'}).fill('喜欢这个表情，希望稍微提亮。');await customer.getByRole('button',{name:'关闭照片预览'}).click();
   await customer.getByText('选择已保存',{exact:true}).waitFor();await customer.reload();await customer.getByRole('button',{name:'取消选择 QA_DSC00001.JPG',exact:true}).waitFor();await customer.getByText('喜欢这个表情，希望稍微提亮。',{exact:true}).waitFor();
   const layout=await customer.evaluate(()=>({width:innerWidth,overflow:document.documentElement.scrollWidth>innerWidth,dock:document.querySelector('.client-dock').getBoundingClientRect().toJSON()}));assert.equal(layout.overflow,false);assert.ok(layout.dock.left>=0&&layout.dock.right<=layout.width);
   await customer.screenshot({path:path.join(artifacts,'web-client-mobile.png')});
  });
  await record('客户提交与摄影师确认',async()=>{
   customer.once('dialog',dialog=>dialog.accept());await customer.getByRole('button',{name:'提交选择',exact:true}).click();await customer.getByRole('heading',{name:'选择已提交给摄影师',exact:true}).waitFor();
   await owner.getByRole('button',{name:'关闭',exact:true}).click();await owner.bringToFront();await owner.getByRole('button',{name:'确认本轮选片',exact:true}).waitFor({timeout:20000});
   await owner.getByRole('button',{name:'确认本轮选片',exact:true}).click();await owner.getByRole('heading',{name:'本轮选择已确认，可以交付成片',exact:true}).waitFor();
  });
  await record('成片关联与下载授权',async()=>{
   const choose=owner.waitForEvent('filechooser');await owner.getByRole('button',{name:/上传对应成片/}).first().click();await(await choose).setFiles(path.join(artifacts,'fixtures','QA_DSC00001.JPG'));
   await owner.locator('.activity-bar').waitFor({state:'hidden'});await owner.getByRole('button',{name:'客户分享',exact:true}).click();await owner.getByRole('checkbox',{name:'允许客户下载已交付成片'}).click();await owner.waitForFunction(()=>document.querySelector('.check-label input')?.checked===true);
   await customer.reload();await customer.getByRole('heading',{name:'你的成片，已准备好。',exact:true}).waitFor();
   const incoming=customer.waitForEvent('download');await customer.getByRole('button',{name:/QA_DSC00001.JPG.*KB/}).click();const downloaded=await incoming;const actual=await downloaded.path();assert.deepEqual(fs.readFileSync(actual),fs.readFileSync(path.join(artifacts,'fixtures','QA_DSC00001.JPG')));
   await owner.getByRole('button',{name:'关闭',exact:true}).click();await owner.screenshot({path:path.join(artifacts,'web-owner-desktop.png')});
  });
  await record('链接撤销立即阻止后续访问',async()=>{
   await owner.getByRole('button',{name:'客户分享',exact:true}).click();owner.once('dialog',dialog=>dialog.accept());await owner.getByRole('button',{name:'撤销访问',exact:true}).click();
   await owner.getByRole('button',{name:'关闭',exact:true}).click();await customer.reload();await customer.getByText('链接已到期或已被摄影师撤销',{exact:true}).waitFor();
  });
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(artifacts,'web-e2e-results.json'),JSON.stringify({passed:true,steps,viewports:['1440x1000','390x844'],syntheticData:true,checkedAt:new Date().toISOString()},null,2));
 } catch(error){await owner.screenshot({path:path.join(artifacts,'web-e2e-owner-failure.png')}).catch(()=>{});await customer.screenshot({path:path.join(artifacts,'web-e2e-client-failure.png')}).catch(()=>{});fs.writeFileSync(path.join(artifacts,'web-e2e-results.json'),JSON.stringify({passed:false,steps,error:String(error),errors},null,2));throw error}
 finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
