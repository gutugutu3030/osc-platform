var debounceTimer = null;
var editor = document.getElementById('editor');
var preview = document.getElementById('preview');
var statusEl = document.getElementById('status');
var acPopup = document.getElementById('ac-popup');
var cursorMirror = document.getElementById('cursor-mirror');
var acIndex = -1;
var acItems = [];
var acVisible = false;
var acPrefix = '';

var completions = {
  top: [
    { label: 'oscSchema', insert: 'oscSchema {\n    \n}', kind: 'function', detail: 'スキーマ定義のルート' }
  ],
  schema: [
    { label: 'message', insert: 'message("', kind: 'function', detail: 'メッセージ定義' },
    { label: 'bundle', insert: 'bundle("', kind: 'function', detail: 'バンドル定義' }
  ],
  message: [
    { label: 'description', insert: 'description("', kind: 'function', detail: 'メッセージの説明' },
    { label: 'name', insert: 'name("', kind: 'function', detail: 'メッセージの論理名' },
    { label: 'scalar', insert: 'scalar("', kind: 'function', detail: 'スカラー引数' },
    { label: 'arg', insert: 'arg("', kind: 'function', detail: 'スカラー引数 (別名)' },
    { label: 'array', insert: 'array("', kind: 'function', detail: '配列引数' }
  ],
  array: [
    { label: 'scalar', insert: 'scalar(', kind: 'function', detail: 'スカラー配列要素' },
    { label: 'tuple', insert: 'tuple {\n', kind: 'function', detail: 'タプル配列要素' }
  ],
  tuple: [
    { label: 'field', insert: 'field("', kind: 'function', detail: 'タプルフィールド' }
  ],
  bundle: [
    { label: 'description', insert: 'description("', kind: 'function', detail: 'バンドルの説明' },
    { label: 'message', insert: 'message("', kind: 'function', detail: 'メッセージ参照' }
  ],
  types: [
    { label: 'INT', insert: 'INT', kind: 'type', detail: '整数型' },
    { label: 'FLOAT', insert: 'FLOAT', kind: 'type', detail: '浮動小数点型' },
    { label: 'STRING', insert: 'STRING', kind: 'type', detail: '文字列型' },
    { label: 'BOOL', insert: 'BOOL', kind: 'type', detail: '真偽型' },
    { label: 'BLOB', insert: 'BLOB', kind: 'type', detail: 'バイナリ型' }
  ],
  roles: [
    { label: 'LENGTH', insert: 'LENGTH', kind: 'role', detail: '長さロール' },
    { label: 'VALUE', insert: 'VALUE', kind: 'role', detail: '値ロール' }
  ],
  namedParams: [
    { label: 'role', insert: 'role = ', kind: 'param', detail: 'スカラーのロール指定' },
    { label: 'length', insert: 'length = ', kind: 'param', detail: '固定長指定' },
    { label: 'lengthFrom', insert: 'lengthFrom = "', kind: 'param', detail: '長さ参照フィールド' }
  ]
};

function detectContext(text, cursorPos) {
  var beforeCursor = text.substring(0, cursorPos);
  var scopeStack = ['top'];
  var index = 0;
  var inString = false;
  var stringChar = '';

  while (index < beforeCursor.length) {
    var ch = beforeCursor[index];
    if (!inString && (ch === '"' || ch === "'")) {
      inString = true;
      stringChar = ch;
      index++;
      continue;
    }
    if (inString) {
      if (ch === '\\') { index += 2; continue; }
      if (ch === stringChar) { inString = false; }
      index++;
      continue;
    }
    if (ch === '{') {
      var before = beforeCursor.substring(0, index).trimEnd();
      var keyword = getLastKeyword(before);
      if (keyword === 'oscSchema') scopeStack.push('schema');
      else if (keyword === 'message') scopeStack.push('message');
      else if (keyword === 'array') scopeStack.push('array');
      else if (keyword === 'tuple') scopeStack.push('tuple');
      else if (keyword === 'bundle') scopeStack.push('bundle');
      else scopeStack.push(scopeStack[scopeStack.length - 1]);
    } else if (ch === '}') {
      if (scopeStack.length > 1) scopeStack.pop();
    }
    index++;
  }

  return { scope: scopeStack[scopeStack.length - 1], inArgs: isInsideArgs(beforeCursor) };
}

function getLastKeyword(text) {
  var match = text.match(/(\w+)\s*\([^)]*\)\s*$/);
  if (match) return match[1];
  match = text.match(/(\w+)\s*$/);
  if (match) return match[1];
  return '';
}

function isInsideArgs(text) {
  var depth = 0;
  var inStr = false;
  var strCh = '';
  for (var index = text.length - 1; index >= 0; index--) {
    var ch = text[index];
    if (inStr) {
      if (ch === strCh && (index === 0 || text[index - 1] !== '\\')) inStr = false;
      continue;
    }
    if (ch === '"' || ch === "'") { inStr = true; strCh = ch; continue; }
    if (ch === ')') depth++;
    if (ch === '(') {
      if (depth === 0) return true;
      depth--;
    }
    if (ch === '{' || ch === '}' || ch === '\n') {
      if (depth <= 0) return false;
    }
  }
  return false;
}

function getCurrentWord(text, cursorPos) {
  var before = text.substring(0, cursorPos);
  var match = before.match(/(\w+)$/);
  return match ? match[1] : '';
}

function getSuggestions(context, prefix) {
  var items = [];
  if (context.inArgs) {
    items = items.concat(completions.types, completions.roles, completions.namedParams);
  } else {
    items = items.concat(completions[context.scope] || []);
    if (context.scope !== 'top') {
      items = items.concat(completions.types);
    }
  }
  if (prefix) {
    var lowerPrefix = prefix.toLowerCase();
    items = items.filter(function(item) {
      return item.label.toLowerCase().indexOf(lowerPrefix) === 0;
    });
  }
  return items;
}

function getCursorCoords() {
  var text = editor.value.substring(0, editor.selectionStart);
  cursorMirror.style.width = editor.clientWidth + 'px';
  cursorMirror.textContent = text;
  var span = document.createElement('span');
  span.textContent = '|';
  cursorMirror.appendChild(span);

  var spanRect = span.getBoundingClientRect();
  var mirrorRect = cursorMirror.getBoundingClientRect();
  return {
    left: spanRect.left - mirrorRect.left,
    top: spanRect.top - mirrorRect.top - editor.scrollTop + editor.offsetTop
  };
}

function showAc(items, prefix) {
  if (items.length === 0) { hideAc(); return; }
  acItems = items;
  acPrefix = prefix;
  acIndex = 0;
  acVisible = true;

  var html = '';
  items.forEach(function(item, idx) {
    html += '<div class="ac-item' + (idx === 0 ? ' ac-active' : '') + '" data-idx="' + idx + '">';
    html += '<span class="ac-label">' + esc(item.label) + '</span>';
    html += '<span class="ac-kind">' + esc(item.detail || item.kind) + '</span>';
    html += '</div>';
  });
  html += '<div class="ac-hint">↑↓ 選択 · Tab/Enter 確定 · Esc 閉じる</div>';
  acPopup.innerHTML = html;

  var coords = getCursorCoords();
  acPopup.style.left = coords.left + 'px';
  acPopup.style.top = (coords.top + 22) + 'px';
  acPopup.style.display = 'block';

  acPopup.querySelectorAll('.ac-item').forEach(function(element) {
    element.addEventListener('mousedown', function(event) {
      event.preventDefault();
      acIndex = parseInt(element.dataset.idx, 10);
      acceptAc();
    });
  });
}

function hideAc() {
  acPopup.style.display = 'none';
  acVisible = false;
  acItems = [];
  acIndex = -1;
}

function acceptAc() {
  if (acIndex < 0 || acIndex >= acItems.length) { hideAc(); return; }
  var item = acItems[acIndex];
  var pos = editor.selectionStart;
  var before = editor.value.substring(0, pos);
  var after = editor.value.substring(pos);
  var newBefore = before.substring(0, before.length - acPrefix.length) + item.insert;
  editor.value = newBefore + after;
  editor.selectionStart = editor.selectionEnd = newBefore.length;
  editor.focus();
  hideAc();
  triggerEvaluate();
}

function updateAcSelection() {
  acPopup.querySelectorAll('.ac-item').forEach(function(element, idx) {
    if (idx === acIndex) {
      element.classList.add('ac-active');
      element.scrollIntoView({ block: 'nearest' });
    } else {
      element.classList.remove('ac-active');
    }
  });
}

function triggerAc() {
  var pos = editor.selectionStart;
  var text = editor.value;
  var prefix = getCurrentWord(text, pos);
  var beforeCursor = text.substring(0, pos);
  var lastLine = beforeCursor.split('\n').pop() || '';
  var lineQuotes = (lastLine.match(/"/g) || []).length;
  if (lineQuotes % 2 === 1) { hideAc(); return; }

  var context = detectContext(text, pos);
  var suggestions = getSuggestions(context, prefix);
  if (!prefix && suggestions.length > 5) { hideAc(); return; }
  showAc(suggestions, prefix);
}

editor.addEventListener('keydown', function(event) {
  if (acVisible) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      acIndex = (acIndex + 1) % acItems.length;
      updateAcSelection();
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      acIndex = (acIndex - 1 + acItems.length) % acItems.length;
      updateAcSelection();
      return;
    }
    if (event.key === 'Enter' || (event.key === 'Tab' && !event.shiftKey)) {
      event.preventDefault();
      acceptAc();
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      hideAc();
      return;
    }
  }

  if (event.key === 'Tab' && !acVisible) {
    event.preventDefault();
    var start = this.selectionStart;
    var end = this.selectionEnd;
    this.value = this.value.substring(0, start) + '    ' + this.value.substring(end);
    this.selectionStart = this.selectionEnd = start + 4;
    triggerEvaluate();
  }

  if ((event.ctrlKey || event.metaKey) && event.key === ' ') {
    event.preventDefault();
    var pos = editor.selectionStart;
    showAc(getSuggestions(detectContext(editor.value, pos), getCurrentWord(editor.value, pos)), getCurrentWord(editor.value, pos));
  }

  if ((event.ctrlKey || event.metaKey) && event.shiftKey && (event.key === 'f' || event.key === 'F')) {
    event.preventDefault();
    formatCode();
    return;
  }

  var pairs = { '{': '}', '(': ')', '[': ']', '"': '"' };
  if (pairs[event.key] && !event.ctrlKey && !event.metaKey && !event.altKey) {
    var pairStart = this.selectionStart;
    var pairEnd = this.selectionEnd;
    var value = this.value;
    if (pairStart !== pairEnd) {
      event.preventDefault();
      var selected = value.substring(pairStart, pairEnd);
      this.value = value.substring(0, pairStart) + event.key + selected + pairs[event.key] + value.substring(pairEnd);
      this.selectionStart = pairStart + 1;
      this.selectionEnd = pairEnd + 1;
      triggerEvaluate();
      return;
    }
    if (event.key === '"') {
      var beforeCursor = value.substring(0, pairStart);
      var lastLine = beforeCursor.split('\n').pop() || '';
      var lineQuotes = (lastLine.match(/"/g) || []).length;
      if (lineQuotes % 2 === 1) return;
    }
    event.preventDefault();
    this.value = value.substring(0, pairStart) + event.key + pairs[event.key] + value.substring(pairEnd);
    this.selectionStart = this.selectionEnd = pairStart + 1;
    triggerEvaluate();
    triggerAc();
    return;
  }

  var closers = ['}', ')', ']', '"'];
  if (closers.indexOf(event.key) !== -1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    var closerStart = this.selectionStart;
    if (this.value[closerStart] === event.key) {
      event.preventDefault();
      this.selectionStart = this.selectionEnd = closerStart + 1;
      return;
    }
  }

  if (event.key === 'Backspace' && !event.ctrlKey && !event.metaKey) {
    var backspaceStart = this.selectionStart;
    if (backspaceStart > 0 && backspaceStart === this.selectionEnd) {
      var before = this.value[backspaceStart - 1];
      var after = this.value[backspaceStart];
      if ((before === '{' && after === '}') || (before === '(' && after === ')') ||
          (before === '[' && after === ']') || (before === '"' && after === '"')) {
        event.preventDefault();
        this.value = this.value.substring(0, backspaceStart - 1) + this.value.substring(backspaceStart + 1);
        this.selectionStart = this.selectionEnd = backspaceStart - 1;
        triggerEvaluate();
        return;
      }
    }
  }

  if (event.key === 'Enter' && !acVisible) {
    var enterStart = this.selectionStart;
    var currentValue = this.value;
    var beforeCursorText = currentValue.substring(0, enterStart);
    var afterCursorText = currentValue.substring(enterStart);
    var currentLine = beforeCursorText.split('\n').pop() || '';
    var indent = currentLine.match(/^(\s*)/)[1];
    var charBefore = beforeCursorText.trimEnd().slice(-1);
    if (charBefore === '{') {
      event.preventDefault();
      var newIndent = indent + '    ';
      if (afterCursorText.trimStart()[0] === '}') {
        var insert = '\n' + newIndent + '\n' + indent;
        this.value = beforeCursorText + insert + afterCursorText;
        this.selectionStart = this.selectionEnd = enterStart + 1 + newIndent.length;
      } else {
        var deeperInsert = '\n' + newIndent;
        this.value = beforeCursorText + deeperInsert + afterCursorText;
        this.selectionStart = this.selectionEnd = enterStart + deeperInsert.length;
      }
      triggerEvaluate();
      return;
    }
    if (indent) {
      event.preventDefault();
      var sameIndentInsert = '\n' + indent;
      this.value = beforeCursorText + sameIndentInsert + afterCursorText;
      this.selectionStart = this.selectionEnd = enterStart + sameIndentInsert.length;
      triggerEvaluate();
      return;
    }
  }
});

editor.addEventListener('input', function() {
  triggerEvaluate();
  triggerAc();
});

editor.addEventListener('blur', function() {
  setTimeout(hideAc, 200);
});

function triggerEvaluate() {
  clearTimeout(debounceTimer);
  var text = editor.value.trim();
  if (!text) {
    showEmpty();
    return;
  }
  setStatus('loading', '評価中...');
  debounceTimer = setTimeout(function() { evaluate(text); }, 600);
}

function evaluate(dslText) {
  fetch('/api/evaluate', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({dsl: dslText})
  }).then(function(response) {
    return response.json();
  }).then(function(data) {
    if (data.success) {
      setStatus('ok', 'スキーマ有効 ✓');
      renderSchema(data.schema);
    } else {
      setStatus('err', 'エラー ✗');
      showError(data.error);
    }
  }).catch(function(error) {
    setStatus('err', 'ネットワークエラー');
    showError('サーバーに接続できません: ' + error.message);
  });
}

function setStatus(type, text) {
  statusEl.textContent = text;
  statusEl.className = 'status-' + type;
}

function showEmpty() {
  setStatus('idle', '入力待ち');
  preview.innerHTML = '<div class="empty-state"><div class="icon">📝</div>'
    + '<div>左のエディタに Kotlin DSL を入力してください</div>'
    + '<div style="margin-top:8px;font-size:11px;color:#475569;">入力するとリアルタイムでスキーマが可視化されます</div>'
    + '<button class="template-btn" onclick="loadTemplate()" style="margin-top:16px;">サンプルを挿入して始める</button>'
    + '</div>';
}

function showError(message) {
  preview.innerHTML = '<div id="error-display" style="display:block"><pre>' + esc(message) + '</pre></div>';
}

function renderSchema(schema) {
  var html = '';
  if (schema.messages && schema.messages.length > 0) {
    html += '<div class="section-title">Messages (' + schema.messages.length + ')</div>';
    schema.messages.forEach(function(msg) {
      html += '<div class="msg-card">';
      html += '<div class="msg-path">' + esc(msg.path) + '</div>';
      html += '<div class="msg-name">' + esc(msg.name) + '</div>';
      if (msg.description) {
        html += '<div class="msg-desc">' + esc(msg.description) + '</div>';
      }
      if (msg.args && msg.args.length > 0) {
        html += '<div class="msg-args"><div class="msg-args-title">Arguments</div>';
        msg.args.forEach(function(arg) {
          html += renderArg(arg);
        });
        html += '</div>';
      }
      html += '</div>';
    });
  }
  if (schema.bundles && schema.bundles.length > 0) {
    html += '<div class="section-title" style="margin-top:16px;">Bundles (' + schema.bundles.length + ')</div>';
    schema.bundles.forEach(function(bundle) {
      html += '<div class="bundle-card">';
      html += '<div class="bundle-name">' + esc(bundle.name) + '</div>';
      if (bundle.description) {
        html += '<div class="bundle-desc">' + esc(bundle.description) + '</div>';
      }
      if (bundle.messageRefs && bundle.messageRefs.length > 0) {
        html += '<div class="bundle-refs">';
        bundle.messageRefs.forEach(function(ref) {
          html += '<div class="bundle-ref">' + esc(ref) + '</div>';
        });
        html += '</div>';
      }
      html += '</div>';
    });
  }
  if (!html) {
    html = '<div class="empty-state"><div>スキーマにメッセージが定義されていません</div></div>';
  }
  preview.innerHTML = html;
}

function renderArg(arg) {
  if (arg.kind === 'scalar') {
    var scalarHtml = '<div class="arg-item">';
    scalarHtml += '<span class="arg-name">' + esc(arg.name) + '</span>';
    scalarHtml += '<span class="arg-type">' + esc(arg.type) + '</span>';
    if (arg.role && arg.role !== 'value') {
      scalarHtml += '<span class="arg-role">' + esc(arg.role) + '</span>';
    }
    scalarHtml += '</div>';
    return scalarHtml;
  }
  if (arg.kind === 'array') {
    var arrayHtml = '<div class="array-item">';
    arrayHtml += '<div class="array-header">';
    arrayHtml += '<span class="arg-name">' + esc(arg.name) + '</span>';
    arrayHtml += '<span class="arg-type">array</span>';
    if (arg.length) {
      if (arg.length.kind === 'fixed') {
        arrayHtml += '<span class="array-length">length: ' + arg.length.size + '</span>';
      } else if (arg.length.kind === 'fromField') {
        arrayHtml += '<span class="array-length">length from: ' + esc(arg.length.fieldName) + '</span>';
      }
    }
    arrayHtml += '</div>';
    if (arg.item) {
      arrayHtml += '<div class="array-children">';
      if (arg.item.kind === 'scalar') {
        arrayHtml += '<div class="arg-item"><span class="arg-type">' + esc(arg.item.type) + '</span></div>';
      } else if (arg.item.kind === 'tuple' && arg.item.fields) {
        arg.item.fields.forEach(function(field) {
          arrayHtml += '<div class="arg-item"><span class="arg-name">' + esc(field.name) + '</span><span class="arg-type">' + esc(field.type) + '</span></div>';
        });
      }
      arrayHtml += '</div>';
    }
    arrayHtml += '</div>';
    return arrayHtml;
  }
  return '';
}

function formatCode() {
  var text = editor.value;
  if (!text.trim()) return;
  var lines = text.split('\n');
  var result = [];
  var depth = 0;

  for (var index = 0; index < lines.length; index++) {
    var trimmed = lines[index].trim();
    if (!trimmed) {
      result.push('');
      continue;
    }
    var leadingClose = trimmed[0] === '}';
    if (leadingClose && depth > 0) depth--;

    var indent = '';
    for (var depthIndex = 0; depthIndex < depth; depthIndex++) indent += '    ';
    result.push(indent + trimmed);

    var localInString = false;
    for (var charIndex = 0; charIndex < trimmed.length; charIndex++) {
      var ch = trimmed[charIndex];
      if (localInString) {
        if (ch === '\\') { charIndex++; continue; }
        if (ch === '"') localInString = false;
        continue;
      }
      if (ch === '"') { localInString = true; continue; }
      if (ch === '{') depth++;
      else if (ch === '}' && !(leadingClose && charIndex === 0)) depth--;
    }
  }

  var oldPos = editor.selectionStart;
  var beforeCursor = text.substring(0, oldPos);
  var cursorLine = beforeCursor.split('\n').length - 1;
  var cursorCol = (beforeCursor.split('\n').pop() || '').length;

  editor.value = result.join('\n');
  var newLines = editor.value.split('\n');
  var newPos = 0;
  for (var lineIndex = 0; lineIndex < Math.min(cursorLine, newLines.length); lineIndex++) {
    newPos += newLines[lineIndex].length + 1;
  }
  if (cursorLine < newLines.length) {
    newPos += Math.min(cursorCol, newLines[cursorLine].length);
  }
  editor.selectionStart = editor.selectionEnd = Math.min(newPos, editor.value.length);
  editor.focus();
  triggerEvaluate();
}

function loadTemplate() {
  editor.value = 'oscSchema {\n'
    + '    message("/light/color") {\n'
    + '        description("RGB カラーを設定する")\n'
    + '        scalar("r", INT)\n'
    + '        scalar("g", INT)\n'
    + '        scalar("b", INT)\n'
    + '    }\n'
    + '\n'
    + '    message("/synth/volume") {\n'
    + '        description("シンセサイザーの音量を設定する")\n'
    + '        scalar("level", FLOAT)\n'
    + '    }\n'
    + '\n'
    + '    message("/mesh/points") {\n'
    + '        description("XYZ 座標点群を設定する")\n'
    + '        scalar("pointCount", INT, role = LENGTH)\n'
    + '        array("points", lengthFrom = "pointCount") {\n'
    + '            tuple {\n'
    + '                field("x", INT)\n'
    + '                field("y", INT)\n'
    + '                field("z", FLOAT)\n'
    + '            }\n'
    + '        }\n'
    + '    }\n'
    + '\n'
    + '    bundle("SceneBundle") {\n'
    + '        description("シーン管理バンドル")\n'
    + '        message("/light/color")\n'
    + '        message("/synth/volume")\n'
    + '    }\n'
    + '}';
  triggerEvaluate();
}

function esc(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}