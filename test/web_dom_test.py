"""Execute the packaged read-only extractor against local HTML fixtures in Chromium.

All requests are intercepted locally. No real account, image capture or live quiz.
"""
from pathlib import Path
from urllib.parse import urlsplit
import json
from playwright.sync_api import sync_playwright

SCRIPT=(Path(__file__).resolve().parents[1]/'app/src/main/res/raw/web_read.js').read_text()
count=0
def check(value,message):
    global count
    assert value,message
    count+=1

def question(number=1,kind='单选题',root='TiMu',title='Zy_TItle',options='Zy_ulTop',extra=''):
    return f'''<div class="{root}"><div class="{title}">{number}. ({kind}, 3.0分) 办公自动化属于哪个应用领域？</div>
    <ul class="{options}"><li><span>A.</span> 科学计算领域</li><li><span>B.</span> 数据处理领域</li>
    <li><span>C.</span> 自动控制领域</li><li><span>D.</span> 辅助设计领域</li></ul>{extra}</div>'''

def modern_question(number=1,kind='单选题',label_class='num_option',labels=None,bodies=None):
    labels=labels or ['A','B','C','D']
    bodies=bodies or ['科学计算领域','数据处理领域','自动控制领域','辅助设计领域']
    options=''.join(f'<div class="clearfix answerBg"><span class="{label_class}" data="{label}"></span><p class="answer_p"><span class="workTextWrap">{body}</span></p></div>' for label,body in zip(labels,bodies))
    return f'<div class="questionLi"><h3 class="mark_name">{number}. ({kind}) 计算机的一项应用称为"办公自动化",按计算机应用的分类,应该属于______。</h3><div class="stem_answer">{options}</div></div>'

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    context=browser.new_context()
    fixtures={}
    def intercept(route):
        url=urlsplit(route.request.url)
        route.fulfill(status=200,content_type='text/html; charset=utf-8',body=fixtures.get((url.hostname,url.path),'<!doctype html><html><body>fixture</body></html>'))
    context.route('**/*',intercept)
    page=context.new_page()
    serial=0
    def read(body,host='i.chaoxing.com'):
        global serial
        serial+=1
        path=f'/fixture-{serial}'
        fixtures[(host,path)]='<!doctype html><html><meta charset="utf-8"><body>'+body+'</body></html>'
        page.goto('https://'+host+path,wait_until='load')
        return json.loads(page.evaluate(SCRIPT))

    body='<header>private-student-name private-student-number</header>'+question()+question(2,'多选题')
    data=read(body)
    check(len(data['questions'])==2,'read separate questions on one page')
    check('多选题' in data['questions'][1]['title'],'retain question type')
    check(data['questions'][0]['options'][1]=='B. 数据处理领域','pair letter and body within option row')
    check('private-student' not in json.dumps(data),'do not export surrounding account/header text')
    check(not data['truncated'] and not data['error'],'complete fixture has no implicit truncation')

    # Structural sample of the new work-page family; not the user's private HTML.
    data=read('<div class="TiMu"><div class="mark_table">'+''.join(modern_question(i) for i in range(1,11))+'</div></div>')
    check(len(data['questions'])==10,'new work page groups ten independent questionLi containers')
    check(all(len(q['options'])==4 for q in data['questions']),'read four div-based options for each question')
    check(data['questions'][0]['options']==['A. 科学计算领域','B. 数据处理领域','C. 自动控制领域','D. 辅助设计领域'],'pair explicit label attributes with independent answer paragraphs')
    check(all(q['optionSelector']=='.stem_answer .answerBg' for q in data['questions']),'report the selected option rule')
    check(any(s['selector']=='.stem_answer .answerBg' and s['count']==4 for s in data['questions'][0]['optionStats']),'report option candidate counts before future adaptation')
    check(any(s['selector']=='.answer_p' and s['count']==4 for s in data['questions'][0]['structure']),'include bounded structure counts without HTML dumps')
    check(not data['truncated'] and not any(q['optionIssue'] for q in data['questions']),'complete modern fixture is unambiguous')

    data=read(modern_question(kind='多选题',label_class='num_option_dx'))
    check(data['questions'][0]['options'][1]=='B. 数据处理领域','read multi-choice label attributes without toggling anything')
    data=read(modern_question(labels=['A','C','D','E']))
    check(data['questions'][0]['options'][1].startswith('C. '),'preserve missing-letter evidence instead of renumbering rows')
    data=read(modern_question().replace('data="A"></span>','data="A">B</span>',1))
    check(data['questions'][0]['optionIssue'],'conflicting explicit and displayed letters must be flagged')
    data=read(modern_question(labels=['not-a-label','B','C','D']))
    check(data['questions'][0]['options'][0]=='科学计算领域','do not invent a label from row position')
    data=read(modern_question(bodies=['B. 是正文的一部分','数据处理领域','自动控制领域','辅助设计领域']))
    check(data['questions'][0]['options'][0]=='A. B. 是正文的一部分' and not data['questions'][0]['optionIssue'],'do not strip meaningful body text beginning with another letter')
    combined='<div class="questionLi"><h3 class="mark_name">1. (单选题) 测试题干内容</h3><div class="stem_answer">'+''.join(f'<div class="answerBg"><p class="answer_p"><span class="num_option" data="{letter}">{letter}.</span><span>选项正文{letter}</span></p></div>' for letter in 'AB')+'</div></div>'
    data=read(combined)
    check(data['questions'][0]['options']==['A. 选项正文A','B. 选项正文B'],'a label nested inside the body is included exactly once')
    data=read(modern_question(kind='判断题',labels=['true','false'],bodies=['对','错']))
    check(data['questions'][0]['options']==['对','错'] and not data['questions'][0]['optionIssue'],'boolean metadata agrees with visible true-false text')
    data=read(modern_question(kind='判断题',labels=['true','false'],bodies=['错','对']))
    check(data['questions'][0]['optionIssue'],'do not accept contradictory true-false metadata')

    data=read(question(root='mark_table',title='mark_name',options='mark_letter'))
    check(len(data['questions'])==1 and len(data['questions'][0]['options'])==4,'alternate desktop layout')

    data=read('<div class="mark_table">'+question()+question(2)+'</div>')
    check(len(data['questions'])==2,'nested selector matches must not duplicate whole questions')

    data=read('<input type="password" value="private-login-secret">'+question())
    check(data['loginDocuments']==1 and not data['questions'],'skip a document containing a login form')
    check('private-login-secret' not in json.dumps(data),'never export password values')

    data=read(question(extra='<input value="private-input"><textarea>private-draft</textarea>'))
    check('private-input' not in json.dumps(data) and 'private-draft' not in json.dumps(data),'do not read answers/drafts outside selected text nodes')
    injected=question().replace('科学计算领域','科学计算领域<input value="private-input"><textarea>private-draft</textarea>')
    data=read(injected)
    check('private-input' not in json.dumps(data) and 'private-draft' not in json.dumps(data),'skip controls even inside option rows')

    data=read('<div style="display:none">'+question()+'</div>'+question(2))
    check(len(data['questions'])==1 and data['questions'][0]['title'].startswith('2.'),'skip hidden question copies')
    data=read(question().replace('<li><span>A.</span>','<li style="display:none"><span>A.</span>'))
    check(len(data['questions'][0]['options'])==3,'do not silently recover hidden option text')

    data=read(question().replace('科学计算领域','科学计算领域<canvas width="10" height="10"></canvas>'))
    check(data['questions'][0]['media'],'mark non-text content for conservative parsing')
    data=read(question().replace('科学计算领域','<span class="font-cxsecret">科学计算领域</span>'))
    check(data['questions'][0]['customFont'],'flag custom-font markers for user verification')

    fixtures[('i.chaoxing.com','/same-frame')]='<html><body>'+question()+'</body></html>'
    data=read('<iframe src="https://i.chaoxing.com/same-frame"></iframe>')
    check(len(data['questions'])==1 and len(data['documents'])==2,'read authorized same-origin frame DOM')
    data=read('<iframe style="display:none" src="https://i.chaoxing.com/same-frame"></iframe>')
    check(not data['questions'] and len(data['documents'])==1,'a hidden same-origin frame must not contribute questions')
    fixtures[('mooc1.chaoxing.com','/work')]='<html><body>'+question()+'</body></html>'
    data=read('<iframe src="https://mooc1.chaoxing.com/work?token=fixture-only"></iframe>')
    check(not data['questions'] and len(data['frames'])==1,'do not bypass cross-origin DOM access')
    check('?' not in data['frames'][0]['location'],'frame display omits URL parameters')

    data=read('<iframe src="https://foreign.invalid/private"></iframe>'+question())
    check(data['blockedFrames']==1 and len(data['questions'])==1,'skip foreign-domain frames')
    check('foreign.invalid' not in json.dumps(data),'do not expose external frame contents or full URLs')
    data=read(question(),host='chaoxing.com.foreign.invalid')
    check(not data['questions'] and not data['documents'],'reject origin suffix spoofing')

    data=read('<div>private-page-content</div>')
    check(not data['questions'] and 'private-page-content' not in json.dumps(data),'unsupported layouts do not trigger whole-page dumps')
    data=read(question()+('<div style="margin-top:5000px">'+question(2)+'</div>'))
    check(len(data['questions'])==2,'loaded multi-question document can be read without scrolling or capture')

    data=read(''.join(question(i) for i in range(41)))
    check(len(data['questions'])==40 and data['truncated'],'question cap is explicitly reported')
    data=read(question().replace('科学计算领域','文'*4000))
    check(data['truncated'] and data['questions'][0]['truncated'],'field truncation is explicit')

    body=question(extra='<input type="checkbox" checked><textarea>private-draft</textarea>')+'<script>window.actions=0;document.addEventListener("click",()=>actions++);document.addEventListener("change",()=>actions++);</script>'
    read(body)
    before=page.evaluate('({html:document.body.innerHTML,scroll:scrollY,checked:document.querySelector("input").checked})')
    page.evaluate(SCRIPT)
    after=page.evaluate('({html:document.body.innerHTML,scroll:scrollY,checked:document.querySelector("input").checked,actions:window.actions})')
    check(before['html']==after['html'] and before['scroll']==after['scroll'] and before['checked']==after['checked'] and after['actions']==0,'extractor must not modify answers, DOM, scroll or click/change events')

    read(modern_question().replace('class="num_option"','class="num_option check_answer"',1))
    before=page.evaluate('document.body.innerHTML')
    page.evaluate(SCRIPT)
    check(before==page.evaluate('document.body.innerHTML'),'modern selected-state classes must remain unchanged')

    context.close();browser.close()
print(f'Web DOM fixture checks passed: {count} (local fixtures; no real account or device claim)')
