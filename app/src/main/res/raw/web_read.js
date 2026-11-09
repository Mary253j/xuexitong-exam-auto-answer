(function (command) {
  'use strict';
  var output = {schema: 1, questions: [], documents: [], frames: [], blockedFrames: 0, loginDocuments: 0, truncated: false, error: ''};
  var selectors = ['.TiMu', '.mark_table', '.questionLi', '.question-item', '.questionItem', '[data-question-id]'];
  var titles = ['.Zy_TItle', '.mark_name', '.question-title', '.questionTitle', '.question-name', '.type_tit'];
  var rows = ['.stem_answer .answerBg', '.Zy_ulTop > li', '.Zy_ul > li', '.mark_letter > li', '.answerList > li', '.answerBg > li', '.answerBg', '.option-item', '[data-option-label]'];
  var optionParts = ['.stem_answer', '.answerBg', '.answer_p', '.num_option', '.num_option_dx', 'ul', 'li'];
  var visited = [], seenFrames = [], budget = 0, characters = 0;
  function trusted(value) {
    try { var u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') && /(^|\.)chaoxing\.com$/i.test(u.hostname); }
    catch (_) { return false; }
  }
  function safeLocation(value) {
    try { var u = new URL(value); return u.hostname + u.pathname.replace(/[0-9]{5,}/g, '[数字]').slice(0, 180); }
    catch (_) { return '<地址未取得>'; }
  }
  function rendered(el) {
    if (!el || !el.ownerDocument.defaultView) return false;
    var win = el.ownerDocument.defaultView, current = el;
    while (current && current.nodeType === 1) {
      var style = win.getComputedStyle(current);
      if (current.hidden || style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || style.opacity === '0') return false;
      current = current.parentElement;
    }
    var rect = el.getBoundingClientRect(); return (rect.width > 0 && rect.height > 0) || win.getComputedStyle(el).display === 'contents';
  }
  function textOf(el, max, result, exclude) {
    if (!el) return '';
    var walker = el.ownerDocument.createTreeWalker(el, 4), parts = [], node, length = 0;
    while ((node = walker.nextNode())) {
      if (++budget > 20000) { result.truncated = true; output.truncated = true; break; }
      var parent = node.parentElement;
      if (exclude && parent && exclude.contains(parent)) continue;
      if (!parent || parent.closest('input,textarea,select,button,script,style,noscript,template') || !rendered(parent)) continue;
      var value = node.nodeValue.replace(/\s+/g, ' ').trim();
      if (!value) continue;
      if (length + value.length + 1 > max || characters + value.length + 1 > 60000) { result.truncated = true; output.truncated = true; break; }
      parts.push(value); length += value.length + 1; characters += value.length + 1;
    }
    return parts.join(' ').replace(/\s+/g, ' ').trim();
  }
  function letter(value) {
    value = (value || '').replace(/\s+/g, '').replace(/[.．、:：)）]$/, '');
    if (!/^[A-Ha-hＡ-Ｈ]$/.test(value)) return '';
    var code = value.toUpperCase().charCodeAt(0); return String.fromCharCode(code > 255 ? code - 65248 : code);
  }
  function optionText(row, result) {
    var labelNode = row.querySelector('.num_option, .num_option_dx, .check_answer, .check_answer_dx, .option-label, .optionLabel');
    var data = labelNode ? (labelNode.getAttribute('data') || '').trim() : '';
    var declared = letter(data || row.getAttribute('data-option-label'));
    var shown = labelNode ? letter(textOf(labelNode, 80, result)) : '';
    if (declared && shown && declared !== shown) result.optionIssue = '选项字母标签存在冲突。';
    var label = declared || shown;
    var bodyNode = row.querySelector('.answer_p, .after, .option-text, .optionText');
    if (bodyNode && !rendered(bodyNode)) bodyNode = null;
    var body = textOf(bodyNode || row, 3500, result, labelNode);
    if (/判断题/.test(result.title) && /^(true|false)$/i.test(data || '')) {
      var truth = data.toLowerCase() === 'true';
      if (body && !/^(正确|错误|对|错|是|否|√|×)$/.test(body)) result.optionIssue = '判断选项的文字与标签无法核对。';
      if (body && /^(正确|对|是|√)$/.test(body) !== truth) result.optionIssue = '判断选项的文字与标签不一致。';
      return body || (truth ? '正确' : '错误');
    }
    if (!label) return body;
    if (!bodyNode && !labelNode) {
      var prefix = body.match(/^([A-HＡ-Ｈ])(?:[.．、:：)）]\s*|\s+)(.*)$/);
      if (prefix) {
        if (letter(prefix[1]) !== label) result.optionIssue = '选项正文前的字母与标签不一致。';
        body = prefix[2].trim();
      } else if (letter(body) === label) body = '';
    }
    return label + '. ' + body;
  }
  function rootsIn(doc) {
    var roots = [];
    selectors.forEach(function (selector) {
      Array.prototype.slice.call(doc.querySelectorAll(selector), 0, 200).forEach(function (el) {
        if (roots.indexOf(el) === -1 && rendered(el)) roots.push(el);
      });
    });
    return roots.filter(function (root) { return !roots.some(function (other) { return root !== other && root.contains(other); }); })
      .sort(function (a, b) { return a.compareDocumentPosition(b) & 4 ? -1 : 1; });
  }
  function pathOf(el) {
    var path = [], doc = el.ownerDocument;
    while (el !== doc.documentElement) {
      if (!el.parentElement || path.length > 64) return null;
      path.unshift(Array.prototype.indexOf.call(el.parentElement.children, el)); el = el.parentElement;
    }
    return path;
  }
  function atPath(doc, path) {
    if (!Array.isArray(path) || path.length > 64) return null;
    var el = doc.documentElement;
    for (var i = 0; i < path.length; i++) {
      if (!Number.isInteger(path[i]) || path[i] < 0 || path[i] > 10000 || !el) return null;
      el = el.children[path[i]];
    }
    return el || null;
  }
  function titleOf(root, result) {
    for (var i = 0; i < titles.length; i++) {
      var candidate = root.querySelector(titles[i]);
      if (candidate && rendered(candidate)) return {el: candidate, text: textOf(candidate, 6000, result)};
    }
    return {el: null, text: ''};
  }
  function readQuestion(root) {
    var result = {title: '', options: [], media: false, truncated: false, customFont: false, optionIssue: '', optionSelector: '', optionStats: [], structure: []};
    var title = titleOf(root, result); result.title = title.text;
    var optionRows = [];
    rows.forEach(function (selector) {
      var found = Array.prototype.slice.call(root.querySelectorAll(selector));
      if (found.length > 100) { result.truncated = true; output.truncated = true; }
      var visible = found.slice(0, 100).filter(rendered);
      result.optionStats.push({selector: selector, count: found.length, rendered: visible.length});
      if (!optionRows.length && visible.length) { optionRows = visible; result.optionSelector = selector; }
    });
    optionParts.forEach(function (selector) { result.structure.push({selector: selector, count: root.querySelectorAll(selector).length}); });
    if (optionRows.length > 8) result.truncated = true;
    optionRows.slice(0, 8).forEach(function (row) { result.options.push(optionText(row, result)); });
    result.selection = optionRows.slice(0, 8).map(selected);
    [title.el].concat(optionRows).forEach(function (area) {
      if (!area) return;
      if (Array.prototype.some.call(area.querySelectorAll('img,canvas,svg,math,video,audio'), rendered)) result.media = true;
      if (area.matches('.font-cxsecret') || area.querySelector('.font-cxsecret')) result.customFont = true;
    });
    return {data: result, rows: optionRows};
  }
  function selected(row) {
    var values = [], controls = row.querySelectorAll('input[type="radio"],input[type="checkbox"]');
    if (controls.length > 1) return null;
    if (controls.length === 1) values.push(!!controls[0].checked);
    var labelNode = row.querySelector('.num_option,.num_option_dx,.check_answer,.check_answer_dx');
    [row, labelNode].forEach(function (el) {
      if (!el) return;
      var value = el.getAttribute('aria-checked');
      if (value === 'true' || value === 'false') values.push(value === 'true');
    });
    if (labelNode && (letter(labelNode.getAttribute('data')) || /^(true|false)$/i.test(labelNode.getAttribute('data') || ''))) {
      values.push(!!row.querySelector('.check_answer,.check_answer_dx'));
    }
    if (!values.length || values.some(function (value) { return value !== values[0]; })) return null;
    return values[0];
  }
  function readDocument(doc, depth, framePath) {
    if (!doc || visited.indexOf(doc) !== -1) return;
    if (depth > 5 || visited.length >= 12) { output.truncated = true; return; }
    visited.push(doc);
    if (!trusted(doc.location.href)) return;
    if (/^passport/i.test(doc.location.hostname) || /\/(?:m?login|register|findpwd)(?:\/|$)/i.test(doc.location.pathname) || doc.querySelector('input[type="password"]')) {
      output.loginDocuments++; return;
    }
    var counts = [], roots = [];
    selectors.forEach(function (selector) {
      var found = Array.prototype.slice.call(doc.querySelectorAll(selector));
      counts.push({selector: selector, count: found.length});
      if (found.length > 200) output.truncated = true;
      found.slice(0, 200).forEach(function (el) { if (roots.indexOf(el) === -1 && rendered(el)) roots.push(el); });
    });
    roots = rootsIn(doc);
    output.documents.push({location: safeLocation(doc.location.href), selectors: counts, candidates: roots.length});
    roots.forEach(function (root) {
      if (output.questions.length >= 40) { output.truncated = true; return; }
      var result = readQuestion(root).data;
      result.target = {frames: framePath, path: pathOf(root)};
      output.questions.push(result);
    });
    var allFrames = doc.querySelectorAll('iframe');
    if (allFrames.length > 20) output.truncated = true;
    Array.prototype.slice.call(allFrames).slice(0, 20).forEach(function (frame) {
      var src;
      if (!rendered(frame)) return;
      if (!frame.getAttribute('src')) return;
      try { src = new URL(frame.getAttribute('src'), doc.location.href).href; } catch (_) { output.blockedFrames++; return; }
      if (!trusted(src)) { output.blockedFrames++; return; }
      var child = null;
      try { child = frame.contentDocument; if (child && child.location.href === 'about:blank') child = null; } catch (_) {}
      if (child) readDocument(child, depth + 1, framePath.concat([pathOf(frame)]));
      else if (seenFrames.indexOf(src) === -1) {
        if (output.frames.length >= 12) { output.truncated = true; return; }
        seenFrames.push(src); output.frames.push({url: src, location: safeLocation(src)});
      }
    });
  }
  function action(request) {
    function refuse(code) { return {schema: 1, status: 'refused', code: code}; }
    if (!trusted(document.location.href)) return refuse('WRONG_ORIGIN');
    if (request.operation === 'arm') {
      if (typeof request.token !== 'string' || !/^[a-zA-Z0-9-]{8,100}$/.test(request.token)) return refuse('BAD_REQUEST');
      window.__quizhelperActiveToken = request.token; return {schema: 1, status: 'armed'};
    }
    if (request.operation === 'cancel') {
      if (window.__quizhelperActiveToken === request.token) window.__quizhelperActiveToken = null;
      return {schema: 1, status: 'cancelled'};
    }
    if (request.operation !== 'select') return refuse('BAD_REQUEST');
    if (window.__quizhelperActiveToken !== request.token) return refuse('NOT_ACTIVE');
    if (document.visibilityState !== 'visible') return refuse('HIDDEN');
    if (typeof request.expires !== 'number' || Date.now() > request.expires) return refuse('EXPIRED');
    if (!request.expected || !Array.isArray(request.expected.options) || !request.target || !Array.isArray(request.target.frames) || request.target.frames.length > 5) return refuse('BAD_REQUEST');
    var doc = document;
    for (var f = 0; f < request.target.frames.length; f++) {
      var frame = atPath(doc, request.target.frames[f]);
      if (!frame || !rendered(frame)) return refuse('FRAME_UNAVAILABLE');
      try { doc = frame && frame.tagName === 'IFRAME' ? frame.contentDocument : null; } catch (_) { return refuse('FRAME_UNAVAILABLE'); }
      if (!doc || !trusted(doc.location.href)) return refuse('FRAME_UNAVAILABLE');
    }
    if (/^passport/i.test(doc.location.hostname) || doc.querySelector('input[type="password"]')) return refuse('LOGIN_PAGE');
    var matches = [];
    rootsIn(doc).forEach(function (root) {
      var scratch = {truncated: false};
      if (titleOf(root, scratch).text !== request.expected.title || scratch.truncated) return;
      var candidate = readQuestion(root);
      if (!candidate.data.truncated && !candidate.data.media && !candidate.data.customFont && !candidate.data.optionIssue && JSON.stringify(candidate.data.options) === JSON.stringify(request.expected.options)) matches.push(candidate);
    });
    if (matches.length !== 1) return refuse(matches.length ? 'AMBIGUOUS' : 'STALE');
    var current = matches[0], data = current.data, marker = data.title.match(/单选题|多选题|判断题|简答题|问答题|论述题/);
    var kind = marker && marker[0] === '多选题' ? 'multiple' : marker && marker[0] === '判断题' ? 'boolean' : marker && marker[0] === '单选题' ? 'single' : 'text';
    if (request.kind !== kind || kind === 'text') return refuse('BAD_REQUEST');
    if (data.optionSelector !== '.stem_answer .answerBg' && data.optionSelector !== '.answerBg') return refuse('UNSUPPORTED_LAYOUT');
    if (data.options.length < 2 || data.options.length > 8 || (kind === 'boolean' && data.options.length !== 2)) return refuse('BAD_REQUEST');
    var labels = [], checked = [];
    for (var i = 0; i < data.options.length; i++) {
      var match = data.options[i].match(/^([A-H])(?:[.．、:：)）]\s*|\s+).+$/), label = match ? match[1] : '';
      if (!label && kind === 'boolean' && /^(正确|错误|对|错|是|否|√|×)$/.test(data.options[i])) label = String.fromCharCode(65 + i);
      if (label !== String.fromCharCode(65 + i)) return refuse('LABEL_MISMATCH');
      labels.push(label); checked.push(selected(current.rows[i]));
    }
    if (!Array.isArray(request.answers) || !request.answers.length || (kind !== 'multiple' && request.answers.length !== 1)) return refuse('BAD_REQUEST');
    var wanted = [];
    for (var a = 0; a < request.answers.length; a++) {
      if (labels.indexOf(request.answers[a]) < 0 || wanted.indexOf(request.answers[a]) !== -1) return refuse('BAD_REQUEST');
      wanted.push(request.answers[a]);
    }
    if (request.previous) {
      var previousIndex = labels.indexOf(request.previous.label);
      if (previousIndex < 0 || typeof request.previous.desired !== 'boolean') return refuse('BAD_REQUEST');
      if (checked[previousIndex] !== request.previous.desired) return {schema: 1, status: 'pending', code: 'PENDING_VERIFY'};
    }
    if (checked.some(function (value) { return value === null; })) return refuse('UNKNOWN_STATE');
    var complete = labels.every(function (label, index) { return checked[index] === (wanted.indexOf(label) !== -1); });
    if (complete) return {schema: 1, status: 'done'};
    var index = kind === 'multiple' ? labels.findIndex(function (label, position) { return checked[position] !== (wanted.indexOf(label) !== -1); }) : labels.indexOf(wanted[0]);
    if (kind !== 'multiple' && checked[index]) return {schema: 1, status: 'pending', code: 'PENDING_VERIFY'};
    var row = current.rows[index];
    if (!row || !row.isConnected || !rendered(row) || !row.matches('.answerBg') || row.closest('button,a,[type="submit"]') || row.querySelector('button,[type="submit"],[type="button"]')) return refuse('UNSAFE_TARGET');
    row.click();
    return {schema: 1, status: 'clicked', label: labels[index], desired: wanted.indexOf(labels[index]) !== -1};
  }
  if (command) {
    try { return JSON.stringify(action(command)); } catch (_) { return JSON.stringify({schema: 1, status: 'refused', code: 'SCRIPT_ERROR'}); }
  }
  try { readDocument(document, 0, []); }
  catch (_) { output.error = '网页文字检测未完成，未读取登录表单或整页正文。'; }
  return JSON.stringify(output);
})(/* nativeCommand */ null);
