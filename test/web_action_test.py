"""Exercise the packaged selection code with locally intercepted quiz fixtures only."""
from pathlib import Path
import json,time
from playwright.sync_api import sync_playwright

SCRIPT=(Path(__file__).resolve().parents[1]/'app/src/main/res/raw/web_read.js').read_text()
TOKEN='local-unit-run-00001'
count=0
def check(ok,message):
    global count
    assert ok,message
    count+=1

def fixture(kind='单选题',picked='',label_class=None,controls=False,body=''):
    cls=label_class or ('num_option_dx' if kind=='多选题' else 'num_option')
    rows=''.join(f'<div class="answerBg clearfix"><span class="{cls}{" check_answer_dx" if kind=="多选题" and label in picked else " check_answer" if label in picked else ""}" data="{label}"></span><p class="answer_p">正文{label}</p>{"<input type=checkbox>" if controls else ""}</div>' for label in 'ABCD')
    return f'''<form id="quiz"><div class="questionLi"><h3 class="mark_name">1. ({kind}) {body or '测试题干内容'}</h3><div class="stem_answer">{rows}</div></div><textarea>private-draft</textarea><input type="hidden" value="private-hidden"><button type="submit">提交</button><button type="button" id="save">保存</button></form>
    <script>window.clicks=0;window.submits=0;window.saves=0;
    document.querySelector('#quiz').addEventListener('submit',e=>{{e.preventDefault();submits++}});
    document.querySelector('#save').addEventListener('click',()=>saves++);
    document.querySelectorAll('.answerBg').forEach(row=>row.addEventListener('click',()=>{{
      clicks++;let n=row.querySelector('span[data]');
      if('{kind}'==='多选题')n.classList.toggle('check_answer_dx');
      else{{document.querySelectorAll('span[data]').forEach(n=>n.classList.remove('check_answer'));n.classList.add('check_answer')}}
    }}));</script>'''

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    context=browser.new_context();page=context.new_page();html='';serial=0
    context.route('**/*',lambda route:route.fulfill(status=200,content_type='text/html; charset=utf-8',body=html))
    def load(body,host='mooc1.chaoxing.com'):
        global html,serial
        serial+=1;html='<!doctype html><meta charset="utf-8"><body>'+body+'</body>'
        page.goto(f'https://{host}/mooc-ans/mooc2/work/dowork?fixture={serial}',wait_until='load')
        return json.loads(page.evaluate(SCRIPT))['questions'][0]
    def execute(command):
        return json.loads(page.evaluate(SCRIPT.replace('/* nativeCommand */ null',json.dumps(command,ensure_ascii=True))))
    def arm():return execute({'operation':'arm','token':TOKEN})
    def request(q,answers,kind='single',previous=None):
        value={'operation':'select','token':TOKEN,'expires':int(time.time()*1000)+10000,'expected':{'title':q['title'],'options':q['options']},'target':q['target'],'kind':kind,'answers':answers}
        if previous:value['previous']=previous
        return value
    def chosen():return page.evaluate('Array.from(document.querySelectorAll(".check_answer,.check_answer_dx")).map(n=>n.getAttribute("data")).join("")')

    q=load(fixture());check(arm()['status']=='armed','arm an explicit selection session')
    page.evaluate('document.addEventListener("click",event=>window.lastClickTrusted=event.isTrusted,true)')
    r=execute(request(q,['B']));check(r['status']=='clicked' and r['label']=='B','single answer clicks the matched row once')
    r=execute(request(q,['B'],previous={'label':'B','desired':True}));check(r['status']=='done' and chosen()=='B','confirm state after the click')
    check(page.evaluate('clicks')==1 and page.evaluate('submits+saves')==0,'do not save, submit or repeat the selection')
    check(page.evaluate('window.lastClickTrusted') is False,'script clicks remain observable as untrusted events; no detection or event-origin spoofing')
    check(page.locator('textarea').input_value()=='private-draft','leave free-text drafts untouched')
    execute(request(q,['B']));check(page.evaluate('clicks')==1,'already correct answers are not clicked again')

    q=load(fixture(picked='D'));arm();execute(request(q,['B']));check(chosen()=='B','single-choice correction replaces an existing wrong choice')
    q=load(fixture('多选题',picked='B'));arm();previous=None;clicks=0
    for _ in range(10):
        r=execute(request(q,['A','C'],'multiple',previous))
        if r['status']=='done':break
        check(r['status']=='clicked','multi-choice performs one verified toggle at a time')
        clicks+=1;previous={'label':r['label'],'desired':r['desired']}
    check(chosen()=='AC' and clicks==3,'multi-choice adds correct choices and removes a known wrong choice')
    execute(request(q,['A','C'],'multiple'));check(page.evaluate('clicks')==3,'do not toggle correct multi-choice answers off')

    q=load(fixture(label_class='option-label'));arm();r=execute(request(q,['B']))
    check(r.get('code')=='UNKNOWN_STATE' and page.evaluate('clicks')==0,'unknown selected state must not be guessed')
    q=load(fixture(picked='B',controls=True));arm();r=execute(request(q,['A']))
    check(r.get('code')=='UNKNOWN_STATE' and page.evaluate('clicks')==0,'conflicting checkbox and CSS states must stop selection')

    q=load(fixture());arm();page.evaluate('document.querySelector(".answer_p").textContent="内容已变化"')
    check(execute(request(q,['B'])).get('code')=='STALE' and page.evaluate('clicks')==0,'changed option bodies invalidate an answer before any click')
    q=load(fixture());arm();page.evaluate('document.querySelector(".mark_name").textContent="1. (单选题) 题干已变化"')
    check(execute(request(q,['B'])).get('code')=='STALE' and page.evaluate('clicks')==0,'changed stem invalidates an answer')
    q=load(fixture());arm();page.evaluate('document.body.append(document.querySelector(".questionLi").cloneNode(true))')
    check(execute(request(q,['B'])).get('code')=='AMBIGUOUS' and page.evaluate('clicks')==0,'duplicate identical questions cannot be chosen by an ambiguous match')
    q=load(fixture());arm();page.evaluate('document.body.prepend(document.createElement("header"))')
    check(execute(request(q,['B']))['status']=='clicked' and chosen()=='B','unrelated page nodes do not defeat the unique content check')

    q=load(fixture());arm();execute({'operation':'cancel','token':TOKEN})
    check(execute(request(q,['B'])).get('code')=='NOT_ACTIVE' and page.evaluate('clicks')==0,'cancelled sessions cannot click')
    q=load(fixture());arm();expired=request(q,['B']);expired['expires']=0
    check(execute(expired).get('code')=='EXPIRED' and page.evaluate('clicks')==0,'old queued operations expire')
    q=load(fixture());arm();page.evaluate('Object.defineProperty(document,"visibilityState",{get:()=>"hidden"})')
    check(execute(request(q,['B'])).get('code')=='HIDDEN' and page.evaluate('clicks')==0,'a hidden document cannot be selected')

    q=load(fixture());arm();r=execute(request(q,['Z']))
    check(r.get('code')=='BAD_REQUEST' and page.evaluate('clicks')==0,'invalid answer labels cannot target arbitrary elements')
    q=load(fixture());arm();r=execute(request(q,['A','B']))
    check(r.get('code')=='BAD_REQUEST' and page.evaluate('clicks')==0,'single-choice cardinality is checked in the page too')
    q=load(fixture());arm();r=execute(request(q,['A','A'],'multiple'))
    check(r['status']=='refused' and page.evaluate('clicks')==0,'mismatched question kinds or duplicate labels are refused')

    q=load(fixture());arm();page.evaluate('document.querySelectorAll(".answerBg").forEach(row=>{row.onclick=null;row.replaceWith(row.cloneNode(true))})')
    page.evaluate('window.rawClicks=0;document.addEventListener("click",()=>rawClicks++,true)')
    r=execute(request(q,['B']));check(r['status']=='clicked','record a click attempt whose handler is unavailable')
    r=execute(request(q,['B'],previous={'label':'B','desired':True}));check(r['status']=='pending','wait instead of clicking again when the result cannot be confirmed')
    check(page.evaluate('rawClicks')==1,'a pending click is never sent twice')

    q=load(fixture(body='题干里提及多选题，但本题类型仍为单选题'));arm()
    check(execute(request(q,['B']))['status']=='clicked','use the header kind rather than words later in the stem')
    q=load(fixture());arm();page.evaluate('document.querySelector(".answerBg").innerHTML+="<button type=submit>其他操作</button>"')
    check(execute(request(q,['A'])).get('code')=='UNSAFE_TARGET' and page.evaluate('submits+saves')==0,'do not target an option containing a submit button')

    load(fixture('判断题'));page.evaluate('''document.querySelectorAll('.answerBg').forEach((row,i)=>{if(i>1){row.remove();return}row.querySelector('span').setAttribute('data',i?'false':'true');row.querySelector('.answer_p').textContent=i?'错':'对'})''')
    q=json.loads(page.evaluate(SCRIPT))['questions'][0];arm();r=execute(request(q,['B'],'boolean'))
    check(r['status']=='clicked' and chosen()=='false','true-false choices use their explicit semantic labels')
    check(execute(request(q,['B'],'boolean',{'label':'B','desired':True}))['status']=='done','confirm a true-false selection')

    q=load(fixture(picked='B'));page.evaluate('document.querySelector(".check_answer").className="check_answer"');arm()
    check(execute(request(q,['B']))['status']=='done' and page.evaluate('clicks')==0,'recognize selected-label classes even when they replace the base class')

    groups=''.join(f'<div class="questionLi"><h3 class="mark_name">{i}. (单选题) 本页测试题{i}内容</h3><div class="stem_answer">'+''.join(f'<div class="answerBg"><span class="num_option" data="{label}"></span><p class="answer_p">第{i}题正文{label}</p></div>' for label in 'ABCD')+'</div></div>' for i in range(1,11))
    handlers='''<script>window.clicks=0;document.querySelectorAll('.answerBg').forEach(row=>row.onclick=()=>{clicks++;row.closest('.questionLi').querySelectorAll('.check_answer').forEach(n=>n.classList.remove('check_answer'));row.querySelector('.num_option').classList.add('check_answer')});</script>'''
    load(groups+handlers);snapshot=json.loads(page.evaluate(SCRIPT))['questions'];arm()
    expected=[]
    for i,q in enumerate(snapshot):
        label='ABCD'[i%4];expected.append(label)
        check(execute(request(q,[label]))['status']=='clicked','each question chooses only its own matched option')
        check(execute(request(q,[label],previous={'label':label,'desired':True}))['status']=='done','each question verifies its own result')
    actual=page.evaluate('Array.from(document.querySelectorAll(".questionLi")).map(q=>q.querySelector(".check_answer").getAttribute("data"))')
    check(actual==expected and page.evaluate('clicks')==10,'ten questions finish without changing choices in earlier questions')

    context.route('**/frame-inner',lambda route:route.fulfill(status=200,content_type='text/html; charset=utf-8',body='<!doctype html><meta charset="utf-8">'+fixture()))
    q=load('<iframe src="/frame-inner"></iframe>');arm()
    page.evaluate('document.querySelector("iframe").style.display="none"')
    r=execute(request(q,['B']))
    check(r.get('code')=='FRAME_UNAVAILABLE' and page.frames[1].evaluate('clicks')==0,'a frame hidden after reading cannot receive a queued answer')

    # Exhaust every initial four-choice state against each nonempty target set.
    q=load(fixture('多选题'));arm()
    for initial in range(16):
        for desired in range(1,16):
            before=['ABCD'[i] for i in range(4) if initial&(1<<i)]
            wanted=['ABCD'[i] for i in range(4) if desired&(1<<i)]
            page.evaluate('picked=>{window.clicks=0;document.querySelectorAll("span[data]").forEach(n=>n.classList.toggle("check_answer_dx",picked.includes(n.getAttribute("data"))))}',before)
            previous=None
            for step in range(5):
                r=execute(request(q,wanted,'multiple',previous))
                if r['status']=='done':break
                assert r['status']=='clicked',r
                previous={'label':r['label'],'desired':r['desired']}
            check(r['status']=='done' and chosen()==''.join(wanted) and page.evaluate('clicks')==(initial^desired).bit_count(),'all four-choice combinations reach exactly the desired state with one click per mismatch')

    q=load(fixture('多选题'));arm()
    page.evaluate('''()=>{const box=document.querySelector('.stem_answer');for(const letter of 'EFGH'){const row=box.firstElementChild.cloneNode(true);row.querySelector('span').setAttribute('data',letter);row.querySelector('.answer_p').textContent='正文'+letter;row.onclick=()=>{clicks++;row.querySelector('span').classList.toggle('check_answer_dx')};box.append(row)}}''')
    q=json.loads(page.evaluate(SCRIPT))['questions'][0];previous=None
    for step in range(9):
        r=execute(request(q,list('ABCDEFGH'),'multiple',previous))
        if r['status']=='done':break
        assert r['status']=='clicked',r
        previous={'label':r['label'],'desired':r['desired']}
    check(r['status']=='done' and chosen()=='ABCDEFGH' and page.evaluate('clicks')==8,'the maximum eight-option multi-choice question completes without an extra toggle')

    for reversed_order in [False,True]:
        load(fixture('判断题'))
        page.evaluate('''reverse=>document.querySelectorAll('.answerBg').forEach((row,i)=>{if(i>1){row.remove();return}const truth=reverse?i===1:i===0;row.querySelector('span').setAttribute('data',truth?'true':'false');row.querySelector('.answer_p').textContent=truth?'正确':'错误'})''',reversed_order)
        q=json.loads(page.evaluate(SCRIPT))['questions'][0];arm()
        for initial in ['', 'true','false']:
            for answer in ['A','B']:
                page.evaluate('picked=>{clicks=0;document.querySelectorAll("span[data]").forEach(n=>n.classList.toggle("check_answer",n.getAttribute("data")===picked))}',initial)
                r=execute(request(q,[answer],'boolean'))
                if r['status']=='clicked':r=execute(request(q,[answer],'boolean',{'label':answer,'desired':True}))
                index='AB'.index(answer);truth=(index==1 if reversed_order else index==0);target='true' if truth else 'false'
                check(r['status']=='done' and chosen()==target and page.evaluate('clicks')==(0 if initial==target else 1),'true-false state transitions respect the displayed order, including reversed semantics')

    q=load(fixture('多选题'));arm();page.evaluate('''()=>{document.querySelectorAll('.answerBg').forEach(old=>{const row=old.cloneNode(true);old.replaceWith(row);row.onclick=()=>{clicks++;setTimeout(()=>row.querySelector('span').classList.toggle('check_answer_dx'),50)}})}''')
    r=execute(request(q,['B','D'],'multiple'));previous={'label':r['label'],'desired':r['desired']}
    r=execute(request(q,['B','D'],'multiple',previous))
    check(r['status']=='pending' and page.evaluate('clicks')==1,'asynchronous multi-choice handlers wait for the first result without another click')
    page.wait_for_function('document.querySelectorAll(".check_answer_dx").length===1')
    r=execute(request(q,['B','D'],'multiple',previous));previous={'label':r['label'],'desired':r['desired']}
    page.wait_for_function('document.querySelectorAll(".check_answer_dx").length===2')
    check(execute(request(q,['B','D'],'multiple',previous))['status']=='done' and chosen()=='BD' and page.evaluate('clicks')==2,'asynchronous multi-choice changes are verified individually')

    q=load(fixture());arm();r=execute(request(q,['B']));execute({'operation':'cancel','token':TOKEN})
    check(execute(request(q,['B'],previous={'label':'B','desired':True})).get('code')=='NOT_ACTIVE' and page.evaluate('clicks')==1,'stopping between a click and its verification prevents further actions')

    context.close();browser.close()
print(f'Web selection fixture checks passed: {count} (no real account, API call or screen capture)')
