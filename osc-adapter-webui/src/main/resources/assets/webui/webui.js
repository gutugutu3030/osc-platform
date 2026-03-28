var schema = readJsonScript('webui-schema-data', { messages: [] });
var selectedMessage = null;
var uiConfig = readJsonScript('webui-config-data', {});

(function init() {
  document.getElementById('schema-label').textContent = schema.messages.length + ' messages';
  document.getElementById('target-host').value = uiConfig.defaultTargetHost || '127.0.0.1';
  document.getElementById('target-port').value = uiConfig.defaultTargetPort || 9000;
  renderMessageList(schema.messages || []);
  applyInitialSelection();
})();

function readJsonScript(id, fallbackValue) {
  var element = document.getElementById(id);
  if (!element) {
    return fallbackValue;
  }
  try {
    return JSON.parse(element.textContent || 'null') || fallbackValue;
  } catch (error) {
    console.error('Failed to parse JSON script:', id, error);
    return fallbackValue;
  }
}

function renderMessageList(messages) {
  var container = document.getElementById('message-items');
  container.innerHTML = '';
  messages.forEach(function(msg) {
    var item = document.createElement('div');
    item.className = 'msg-item';
    item.dataset.messageName = msg.name;
    item.dataset.messagePath = msg.path;
    item.innerHTML = '<div class="msg-path">' + esc(msg.path) + '</div><div class="msg-name">' + esc(msg.name) + '</div>';
    item.onclick = function() { selectMessage(msg, item); };
    container.appendChild(item);
  });
}

function applyInitialSelection() {
  if (!uiConfig.initialMessageRef) return;
  var initialItem = Array.prototype.find.call(document.querySelectorAll('.msg-item'), function(item) {
    return item.dataset.messageName === uiConfig.initialMessageRef || item.dataset.messagePath === uiConfig.initialMessageRef;
  });
  if (!initialItem) return;
  var message = (schema.messages || []).find(function(msg) {
    return msg.name === initialItem.dataset.messageName || msg.path === initialItem.dataset.messagePath;
  });
  if (message) {
    selectMessage(message, initialItem);
  }
}

function selectMessage(msg, element) {
  document.querySelectorAll('.msg-item').forEach(function(item) { item.classList.remove('active'); });
  element.classList.add('active');
  selectedMessage = msg;
  showForm(msg);
}

function showForm(msg) {
  document.getElementById('placeholder').style.display = 'none';
  var form = document.getElementById('send-form');
  form.style.display = 'block';
  document.getElementById('form-name').textContent = msg.name;
  document.getElementById('form-path').textContent = msg.path;
  document.getElementById('form-desc').textContent = msg.description || '';
  document.getElementById('send-result').style.display = 'none';
  document.getElementById('target-row').style.display = uiConfig.allowSend ? 'flex' : 'none';
  document.getElementById('send-btn').style.display = uiConfig.allowSend ? 'inline-block' : 'none';

  var fields = document.getElementById('form-fields');
  fields.innerHTML = '';
  msg.args.forEach(function(arg) {
    var group = document.createElement('div');
    group.className = 'field-group';
    var label = document.createElement('div');
    label.className = 'field-label';
    label.textContent = arg.typeLabel;
    group.appendChild(label);
    var input = document.createElement('input');
    input.className = 'field-input';
    input.type = arg.inputType || 'text';
    input.id = 'arg-' + arg.name;
    input.placeholder = arg.placeholder || '';
    if (arg.inputType === 'number') {
      input.step = 'any';
    }
    var initialValue = formatInitialValue(arg.name);
    if (initialValue !== null) {
      input.value = initialValue;
    }
    group.appendChild(input);
    fields.appendChild(group);
  });
}

function formatInitialValue(argName) {
  if (!uiConfig.initialArgs || !(argName in uiConfig.initialArgs)) {
    return null;
  }
  var value = uiConfig.initialArgs[argName];
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function sendMessage() {
  if (!selectedMessage || !uiConfig.allowSend) return;
  var args = {};
  selectedMessage.args.forEach(function(arg) {
    var input = document.getElementById('arg-' + arg.name);
    var value = input ? input.value : '';
    args[arg.name] = parseArgValue(value, arg.type, arg.kind);
  });

  var host = document.getElementById('target-host').value;
  var port = parseInt(document.getElementById('target-port').value, 10);

  fetch('/api/send', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({messageRef: selectedMessage.name, host: host, port: port, args: args})
  }).then(function(response) {
    return response.json();
  }).then(function(data) {
    var result = document.getElementById('send-result');
    result.style.display = 'block';
    if (data.success) {
      result.className = 'result-ok';
      result.textContent = '✓ Sent successfully';
    } else {
      result.className = 'result-err';
      result.textContent = '✗ ' + (data.error || 'Send failed');
    }
  }).catch(function() {
    var result = document.getElementById('send-result');
    result.style.display = 'block';
    result.className = 'result-err';
    result.textContent = '✗ Network error';
  });
}

function parseArgValue(value, type, kind) {
  if (kind === 'array') {
    try { return JSON.parse(value); } catch (error) { return value; }
  }
  if (type === 'int') return parseInt(value, 10);
  if (type === 'float') return parseFloat(value);
  if (type === 'bool') return value === 'true' || value === '1' || value === 'yes';
  return value;
}

function clearLog() {
  document.getElementById('log-entries').innerHTML = '';
}

function addLog(cssClass, text) {
  var entry = document.createElement('div');
  entry.className = 'log-entry ' + cssClass;
  var time = new Date().toTimeString().substring(0, 8);
  entry.innerHTML = '<span class="log-time">' + time + '</span>' + esc(text);
  var container = document.getElementById('log-entries');
  container.insertBefore(entry, container.firstChild);
}

function esc(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

var evtSource = new EventSource('/api/events');
evtSource.onmessage = function(eventMessage) {
  var event;
  try {
    event = JSON.parse(eventMessage.data);
  } catch (error) {
    return;
  }
  switch (event.type) {
    case 'connected':
      addLog('log-conn', 'Connected to event stream'); break;
    case 'received':
      addLog('log-recv', 'recv ' + event.path + ' ' + JSON.stringify(event.args)); break;
    case 'send_started':
      addLog('log-send-start', '→ sending ' + event.messageRef + ' to ' + event.targetHost + ':' + event.targetPort); break;
    case 'send_succeeded':
      addLog('log-send-ok', '✓ sent ' + event.messageRef + ' to ' + event.targetHost + ':' + event.targetPort); break;
    case 'send_failed':
      addLog('log-send-fail', '✗ failed ' + event.messageRef + ': ' + event.error); break;
    case 'validation_error':
      addLog('log-error', 'validation error ' + (event.address || '-') + ': ' + event.reason); break;
    case 'transport_error':
      addLog('log-error', 'transport error: ' + event.message); break;
    case 'mcp_request':
      addLog('log-mcp', 'mcp request ' + event.message); break;
    case 'mcp_success':
      addLog('log-mcp', 'mcp success ' + event.message); break;
    case 'mcp_failure':
      addLog('log-error', 'mcp failure ' + event.message); break;
  }
};

evtSource.onerror = function() {
  addLog('log-error', 'Event stream disconnected, reconnecting...');
};