(function (command) {
  'use strict';
  var output = {schema: 1, questions: [], documents: [], frames: [], blockedFrames: 0, loginDocuments: 0, truncated: false, truncationReasons: [], error: ''};
  // Keep MAX_QUESTIONS in sync with WebReadLimits; regression tests check both sides.
  var MAX_QUESTIONS = 500, MAX_CANDIDATES = 2000, MAX_TEXT = 1000000, MAX_NODES = 200000;
  var selectors = ['.TiMu', '.mark_table', '.questionLi', '.question-item', '.questionItem', '[data-question-id]'];
  var titles = ['.Zy_TItle', '.mark_name', '.question-title', '.questionTitle', '.question-name', '.type_tit'];
  var rows = ['.stem_answer .answerBg', '.Zy_ulTop > li', '.Zy_ul > li', '.mark_letter > li', '.answerList > li', '.answerBg > li', '.answerBg', '.option-item', '[data-option-label]'];
  var optionParts = ['.stem_answer', '.answerBg', '.answer_p', '.num_option', '.num_option_dx', 'ul', 'li'];
  var visited = [], seenFrames = [], budget = 0, characters = 0;
  function truncate(reason, result) {
    output.truncated = true;
    if (result) result.truncated = true;
    if (output.truncationReasons.indexOf(reason) === -1) output.truncationReasons.push(reason);
  }
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
      if (++budget > MAX_NODES) { truncate('NODE_LIMIT', result); break; }
      var parent = node.parentElement;
      if (exclude && parent && exclude.contains(parent)) continue;
      if (!parent || parent.closest('input,textarea,select,button,script,style,noscript,template') || !rendered(parent)) continue;
      var value = node.nodeValue.replace(/\s+/g, ' ').trim();
      if (!value) continue;
      if (length + value.length + 1 > max) { truncate('FIELD_LIMIT', result); break; }
      if (characters + value.length + 1 > MAX_TEXT) { truncate('TEXT_LIMIT', result); break; }
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
  function rootsIn(doc, counts) {
    var roots = [], seen = new Set();
    selectors.forEach(function (selector) {
      var found = doc.querySelectorAll(selector);
      counts.push({selector: selector, count: found.length});
      if (found.length > MAX_CANDIDATES) truncate('CANDIDATE_LIMIT');
      Array.prototype.slice.call(found, 0, MAX_CANDIDATES).forEach(function (el) {
        if (!seen.has(el) && rendered(el)) { seen.add(el); roots.push(el); }
      });
    });
    var containers = new Set();
    roots.forEach(function (root) {
      for (var parent = root.parentElement; parent; parent = parent.parentElement) {
        if (seen.has(parent)) containers.add(parent);
      }
    });
    return roots.filter(function (root) { return !containers.has(root); })
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
      if (found.length > 100) truncate('OPTION_LIMIT', result);
      var visible = found.slice(0, 100).filter(rendered);
      result.optionStats.push({selector: selector, count: found.length, rendered: visible.length});
      if (!optionRows.length && visible.length) { optionRows = visible; result.optionSelector = selector; }
    });
    optionParts.forEach(function (selector) { result.structure.push({selector: selector, count: root.querySelectorAll(selector).length}); });
    if (optionRows.length > 8) truncate('OPTION_LIMIT', result);
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
    if (depth > 5 || visited.length >= 12) { truncate('DOCUMENT_LIMIT'); return; }
    visited.push(doc);
    if (!trusted(doc.location.href)) return;
    if (/^passport/i.test(doc.location.hostname) || /\/(?:m?login|register|findpwd)(?:\/|$)/i.test(doc.location.pathname) || doc.querySelector('input[type="password"]')) {
      output.loginDocuments++; return;
    }
    var counts = [], roots = rootsIn(doc, counts);
    output.documents.push({location: safeLocation(doc.location.href), selectors: counts, candidates: roots.length});
    roots.forEach(function (root) {
      if (output.questions.length >= MAX_QUESTIONS) { truncate('QUESTION_LIMIT'); return; }
      var result = readQuestion(root).data;
      result.target = {frames: framePath, path: pathOf(root)};
      output.questions.push(result);
    });
    var allFrames = doc.querySelectorAll('iframe');
    if (allFrames.length > 20) truncate('FRAME_LIMIT');
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
        if (output.frames.length >= 12) { truncate('FRAME_LIMIT'); return; }
        seenFrames.push(src); output.frames.push({url: src, location: safeLocation(src)});
      }
    });
  }
  if (command) return JSON.stringify({schema: 1, status: 'refused', code: 'READ_ONLY'});
  try { readDocument(document, 0, []); }
  catch (_) { output.error = '网页文字检测未完成，未读取登录表单或整页正文。'; }
  return JSON.stringify(output);
})(/* nativeCommand */ null);
