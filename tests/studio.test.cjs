const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
function load(confirm = true) {
  const elements = new Map(), calls = [];
  const el = id => {
    if (!elements.has(id)) elements.set(id, {value:'',textContent:'',hidden:false,children:[],
      querySelectorAll(){return []},replaceChildren(){this.children=[]},appendChild(e){this.children.push(e)}});
    return elements.get(id);
  };
  el('githubBranch').value='main'; el('githubRepo').value='owner/repo'; el('githubPath').value='README.md';
  const context = {document:{getElementById:el,createElement(){return {}}},confirm:()=>confirm,
    window:{AndroidJarvis:{connectorAction(id,action,args){calls.push({id,action,args:JSON.parse(args)})},
      configureConnector(){return 'ok'},connectorStatus(){return '{}'},setAttachmentConsent(v){calls.push({consent:v})}}}};
  vm.runInNewContext(fs.readFileSync('mobile/android/app/src/main/assets/studio.js','utf8'),context);
  return {el,calls,window:context.window};
}
test('write and edit cancellation makes no external call',()=>{
  const {el,calls}=load(false); el('githubWrite').onclick(); el('editImage').onclick();
  assert.equal(calls.length,0);
});
test('file SHA belongs to the exact repository, branch and path',()=>{
  const {el,calls,window}=load(); el('githubRead').onclick();
  window.onJarvisConnectorResult(calls[0].id,JSON.stringify({sha:'old-sha',decoded:'old'}));
  el('githubPath').value='OTHER.md'; el('githubWrite').onclick();
  assert.equal(calls[1].args.sha,undefined); assert.equal(calls[1].args.path,'OTHER.md');
});
test('successful writes invalidate prior SHA before a second write',()=>{
  const {el,calls,window}=load(); el('githubRead').onclick();
  window.onJarvisConnectorResult(calls[0].id,JSON.stringify({sha:'old-sha',decoded:'old'}));
  el('githubWrite').onclick(); assert.equal(calls[1].args.sha,'old-sha');
  window.onJarvisConnectorResult(calls[1].id,'{"text":"saved"}');
  el('githubWrite').onclick(); assert.equal(calls[2].args.sha,undefined);
});
test('attachment and remote content are rendered as text, consent starts off',()=>{
  const {el,window}=load(); window.onJarvisAttachment('{"name":"<img>","text":"<script>bad</script>"}');
  assert.equal(el('attachmentResult').textContent,'<img>\n<script>bad</script>');
  assert.equal(el('attachmentResult').innerHTML,undefined); assert.equal(el('attachmentConsent').checked,false);
});
test('agent proposal executes only after a separate confirmed click',()=>{
  const {el,calls,window}=load(); el('projectTask').value='Fix README'; el('projectAgent').onclick();
  window.onJarvisConnectorResult(calls[0].id,JSON.stringify({proposal:{action:'github.write',args:{path:'README.md',content:'new'}}}));
  assert.equal(calls.length,1); el('applyProposal').onclick();
  assert.equal(calls[1].action,'github.write'); assert.equal(calls[1].args.confirmed,true);
});
test('pending work prevents duplicate paid image requests and releases after error',()=>{
  const {el,calls,window}=load(); el('imagePrompt').value='Photo'; el('generateImage').onclick(); el('generateImage').onclick();
  assert.equal(calls.length,1); window.onJarvisConnectorResult(calls[0].id,'{"error":"HTTP 429"}');
  el('generateImage').onclick(); assert.equal(calls.length,2);
});
