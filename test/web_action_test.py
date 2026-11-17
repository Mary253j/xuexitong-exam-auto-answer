"""Read-only web regression: old operation commands cannot mutate the document."""
from pathlib import Path
import json
from playwright.sync_api import sync_playwright
script=(Path(__file__).resolve().parents[1]/'app/src/main/res/raw/web_read.js').read_text()
count=0
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    page=browser.new_page()
    html='''<!doctype html><meta charset=utf-8><form><div class=questionLi><h3 class=mark_name>1. (多选题) 本地测试题干</h3><div class=stem_answer><div class=answerBg><span class=num_option_dx data=A></span><p class=answer_p>正文甲</p><input type=checkbox checked></div><div class=answerBg><span class=num_option_dx data=B></span><p class=answer_p>正文乙</p><input type=checkbox></div></div></div><textarea>private-draft</textarea><button>提交</button></form><script>window.actions=0;document.addEventListener('click',()=>actions++);document.addEventListener('change',()=>actions++);document.addEventListener('submit',e=>{actions++;e.preventDefault()});</script>'''
    page.route('**/*',lambda r:r.fulfill(content_type='text/html; charset=utf-8',body=html))
    page.goto('https://mooc1.chaoxing.com/work')
    before=page.evaluate('document.body.innerHTML')
    for operation in ['arm','select','cancel','tap','save','submit']:
        result=json.loads(page.evaluate(script.replace('/* nativeCommand */ null',json.dumps({'operation':operation,'systemTouch':True,'answers':['B']}))))
        assert result['code']=='READ_ONLY';count+=1
        assert page.evaluate('actions')==0 and page.evaluate('document.body.innerHTML')==before;count+=1
    for _ in range(3):
        data=json.loads(page.evaluate(script));assert data['questions'][0]['options']==['A. 正文甲','B. 正文乙'];count+=1
        assert page.evaluate('actions')==0 and page.evaluate('document.body.innerHTML')==before;count+=1
    page.locator('input[type=checkbox]').nth(1).click()
    data=json.loads(page.evaluate(script));assert page.locator('input[type=checkbox]').nth(1).is_checked();count+=1
    assert 'private-draft' not in json.dumps(data);count+=1
    browser.close()
print(f'Web read-only operation checks passed: {count} (local fixtures, no live API or screen capture)')
