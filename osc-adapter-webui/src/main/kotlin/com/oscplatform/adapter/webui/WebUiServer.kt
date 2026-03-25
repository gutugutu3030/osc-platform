package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.transport.OscTarget
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

enum class WebUiMode {
  MONITOR,
  SENDER,
  MCP,
}

data class WebUiServerConfig(
    val mode: WebUiMode,
    val httpPort: Int,
    val defaultTargetHost: String = "127.0.0.1",
    val defaultTargetPort: Int = 9000,
    val initialMessageRef: String? = null,
    val initialArgs: Map<String, Any?> = emptyMap(),
)

data class WebUiLogEvent(
    val type: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

class WebUiServer(
    private val schema: OscSchema,
    private val runtime: OscRuntime,
    private val config: WebUiServerConfig,
    private val additionalEvents: Flow<WebUiLogEvent> = emptyFlow(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  val port: Int
    get() = config.httpPort

  private var httpServer: HttpServer? = null
  private val sseClients = CopyOnWriteArrayList<SseClient>()
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  fun start() {
    val server = HttpServer.create(InetSocketAddress(config.httpPort), 0)
    server.createContext("/") { exchange -> dispatch(exchange) }
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.start()
    httpServer = server

    scope.launch { runtime.events.collect { event -> broadcastJson(serializeRuntimeEvent(event)) } }
    scope.launch {
      additionalEvents.collect { event ->
        broadcastJson(
            toJson(
                mapOf(
                    "type" to event.type,
                    "message" to event.message,
                    "details" to event.details,
                ),
            ),
        )
      }
    }
  }

  fun stop() {
    httpServer?.stop(0)
    httpServer = null
    scope.cancel()
  }

  private fun dispatch(exchange: HttpExchange) {
    try {
      val path = exchange.requestURI.path
      val method = exchange.requestMethod
      when {
        method == "GET" && path == "/" -> serveHtml(exchange)
        method == "GET" && path == "/api/schema" -> serveSchema(exchange)
        method == "POST" && path == "/api/send" -> handleSend(exchange)
        method == "GET" && path == "/api/events" -> handleEvents(exchange)
        else -> sendResponse(exchange, 404, "text/plain", "Not Found")
      }
    } catch (e: Exception) {
      try {
        sendResponse(exchange, 500, "application/json", toJson(mapOf("error" to e.message)))
      } catch (_: Exception) {}
    }
  }

  private fun serveHtml(exchange: HttpExchange) {
    sendResponse(exchange, 200, "text/html; charset=utf-8", buildHtml())
  }

  private fun serveSchema(exchange: HttpExchange) {
    val schemaJson =
        mapOf(
            "messages" to
                schema.messages.map { spec ->
                  mapOf(
                      "path" to spec.path,
                      "name" to spec.name,
                      "description" to (spec.description ?: ""),
                      "args" to spec.args.map { arg -> buildArgJson(arg) },
                  )
                },
        )
    sendResponse(exchange, 200, "application/json", toJson(schemaJson))
  }

  private fun buildArgJson(arg: OscArgNode): Map<String, Any?> {
    return when (arg) {
      is ScalarArgNode -> {
        val roleSuffix = if (arg.role == ScalarRole.LENGTH) " [length]" else ""
        mapOf(
            "name" to arg.name,
            "kind" to "scalar",
            "type" to arg.type.name.lowercase(),
            "typeLabel" to "${arg.name}: ${arg.type.name.lowercase()}$roleSuffix",
            "inputType" to scalarInputType(arg.type),
            "placeholder" to scalarPlaceholder(arg.type),
        )
      }

      is ArrayArgNode -> {
        val lengthLabel =
            when (val len = arg.length) {
              is LengthSpec.Fixed -> "[${len.size}]"
              is LengthSpec.FromField -> "[from=${len.fieldName}]"
            }
        val itemLabel =
            when (val item = arg.item) {
              is ArrayItemSpec.ScalarItem -> item.type.name.lowercase()
              is ArrayItemSpec.TupleItem ->
                  "tuple(${item.fields.joinToString(",") { "${it.name}:${it.type.name.lowercase()}" }})"
            }
        mapOf(
            "name" to arg.name,
            "kind" to "array",
            "type" to "array",
            "typeLabel" to "${arg.name}: array<$itemLabel>$lengthLabel",
            "inputType" to "text",
            "placeholder" to "JSON array, e.g. [1,2,3]",
        )
      }
    }
  }

  private fun scalarInputType(type: OscType): String =
      when (type) {
        OscType.INT -> "number"
        OscType.FLOAT -> "number"
        OscType.BOOL -> "text"
        OscType.STRING -> "text"
        OscType.BLOB -> "text"
      }

  private fun scalarPlaceholder(type: OscType): String =
      when (type) {
        OscType.INT -> "0"
        OscType.FLOAT -> "0.0"
        OscType.BOOL -> "true / false"
        OscType.STRING -> ""
        OscType.BLOB -> "base64"
      }

  private fun sendingEnabled(): Boolean = config.mode != WebUiMode.MONITOR

  private fun handleSend(exchange: HttpExchange) {
    if (!sendingEnabled()) {
      sendResponse(
          exchange,
          403,
          "application/json",
          toJson(mapOf("success" to false, "error" to "send is disabled in monitor mode")),
      )
      return
    }

    val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
    val req = mapper.readValue(body, Map::class.java)
    val messageRef = req["messageRef"] as? String
    val host = req["host"] as? String
    val port = (req["port"] as? Number)?.toInt()

    if (messageRef == null || host == null || port == null) {
      sendResponse(
          exchange,
          400,
          "application/json",
          toJson(mapOf("error" to "messageRef, host, and port are required")),
      )
      return
    }

    @Suppress("UNCHECKED_CAST") val args = (req["args"] as? Map<String, Any?>) ?: emptyMap()

    runBlocking {
      try {
        runtime.send(
            messageRef = messageRef,
            rawArgs = args,
            target = OscTarget(host = host, port = port),
        )
        sendResponse(exchange, 200, "application/json", toJson(mapOf("success" to true)))
      } catch (e: Exception) {
        sendResponse(
            exchange,
            400,
            "application/json",
            toJson(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))),
        )
      }
    }
  }

  private fun handleEvents(exchange: HttpExchange) {
    exchange.responseHeaders.add("Content-Type", "text/event-stream")
    exchange.responseHeaders.add("Cache-Control", "no-cache")
    exchange.responseHeaders.add("Connection", "keep-alive")
    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.sendResponseHeaders(200, 0)

    val client = SseClient(PrintWriter(exchange.responseBody, true))
    sseClients.add(client)
    client.send("data: ${toJson(mapOf("type" to "connected"))}\n\n")

    try {
      client.writeLoop()
    } finally {
      sseClients.remove(client)
      try {
        exchange.responseBody.close()
      } catch (_: Exception) {}
    }
  }

  private fun broadcastJson(json: String) {
    val sseData = "data: $json\n\n"
    val deadClients = mutableListOf<SseClient>()
    sseClients.forEach { client ->
      if (!client.send(sseData)) {
        deadClients.add(client)
      }
    }
    sseClients.removeAll(deadClients)
  }

  private fun serializeRuntimeEvent(event: OscRuntimeEvent): String =
      when (event) {
        is OscRuntimeEvent.Received ->
            toJson(
                mapOf(
                    "type" to "received",
                    "path" to event.spec.path,
                    "args" to event.namedArgs,
                ),
            )

        is OscRuntimeEvent.ValidationError ->
            toJson(
                mapOf(
                    "type" to "validation_error",
                    "address" to event.address,
                    "reason" to event.reason,
                ),
            )

        is OscRuntimeEvent.TransportErrorEvent ->
            toJson(
                mapOf(
                    "type" to "transport_error",
                    "message" to event.error.cause.message,
                ),
            )

        is OscRuntimeEvent.SendStarted ->
            toJson(
                mapOf(
                    "type" to "send_started",
                    "messageRef" to event.messageRef,
                    "args" to event.args,
                    "targetHost" to event.target.host,
                    "targetPort" to event.target.port,
                ),
            )

        is OscRuntimeEvent.SendSucceeded ->
            toJson(
                mapOf(
                    "type" to "send_succeeded",
                    "messageRef" to event.messageRef,
                    "args" to event.args,
                    "targetHost" to event.target.host,
                    "targetPort" to event.target.port,
                ),
            )

        is OscRuntimeEvent.SendFailed ->
            toJson(
                mapOf(
                    "type" to "send_failed",
                    "messageRef" to event.messageRef,
                    "error" to event.cause.message,
                    "args" to event.args,
                    "targetHost" to event.target.host,
                    "targetPort" to event.target.port,
                ),
            )
      }

  private fun sendResponse(exchange: HttpExchange, status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun toJson(obj: Any?): String = mapper.writeValueAsString(obj)

  private fun buildHtml(): String {
    val messagesJs =
        schema.messages.joinToString(",\n") { spec ->
          val argsJs = spec.args.joinToString(",\n") { arg -> toJson(buildArgJson(arg)) }
          """{"path":${toJson(spec.path)},"name":${toJson(spec.name)},"description":${toJson(spec.description ?: "")},"args":[$argsJs]}"""
        }

    val title =
        when (config.mode) {
          WebUiMode.MONITOR -> "OSC Monitor"
          WebUiMode.SENDER -> "OSC Sender"
          WebUiMode.MCP -> "OSC MCP Console"
        }
    val subtitle =
        when (config.mode) {
          WebUiMode.MONITOR -> "Receive OSC traffic and inspect the schema."
          WebUiMode.SENDER -> "Select a message and send it to an OSC target."
          WebUiMode.MCP -> "Inspect MCP requests while testing OSC sends from the browser."
        }

    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>$title</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: 'Courier New', monospace; background: #0f172a; color: #e2e8f0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
    header { background: #1e293b; padding: 10px 20px; border-bottom: 1px solid #334155; flex-shrink: 0; }
    header h1 { font-size: 16px; color: #60a5fa; }
    header p { font-size: 11px; color: #94a3b8; margin-top: 2px; }
    .main-container { display: flex; flex: 1; overflow: hidden; }
    #schema-list { width: 240px; background: #1e293b; border-right: 1px solid #334155; overflow-y: auto; padding: 8px; flex-shrink: 0; }
    #schema-list h3 { font-size: 11px; color: #64748b; text-transform: uppercase; letter-spacing: 1px; padding: 4px 0 8px; }
    .msg-item { padding: 7px 8px; margin: 2px 0; border-radius: 5px; cursor: pointer; border: 1px solid transparent; }
    .msg-item:hover { background: #1e3a5f; border-color: #3b82f6; }
    .msg-item.active { background: #1e3a5f; border-color: #60a5fa; }
    .msg-path { font-size: 12px; color: #93c5fd; }
    .msg-name { font-size: 10px; color: #64748b; margin-top: 1px; }
    #form-panel { flex: 1; overflow-y: auto; padding: 20px; }
    #form-panel h2 { font-size: 15px; color: #60a5fa; margin-bottom: 4px; }
    .msg-detail-path { font-size: 12px; color: #38bdf8; margin-bottom: 3px; }
    .msg-detail-desc { font-size: 12px; color: #64748b; margin-bottom: 14px; }
    .field-group { margin-bottom: 10px; }
    .field-label { font-size: 10px; color: #94a3b8; margin-bottom: 3px; text-transform: uppercase; letter-spacing: 0.5px; }
    .field-input { width: 100%; padding: 7px 9px; background: #1e293b; border: 1px solid #334155; color: #e2e8f0; border-radius: 4px; font-family: 'Courier New', monospace; font-size: 13px; }
    .field-input:focus { outline: none; border-color: #60a5fa; }
    .target-row { display: flex; gap: 10px; margin: 14px 0 16px; }
    .target-row .field-group { flex: 1; }
    #send-btn { background: #3b82f6; color: white; border: none; padding: 9px 22px; border-radius: 5px; cursor: pointer; font-size: 13px; font-family: 'Courier New', monospace; }
    #send-btn:hover { background: #2563eb; }
    #send-result { margin-top: 10px; padding: 7px 10px; border-radius: 4px; font-size: 12px; display: none; }
    .result-ok { background: #064e3b; color: #6ee7b7; }
    .result-err { background: #7f1d1d; color: #fca5a5; }
    #placeholder { color: #475569; text-align: center; padding-top: 60px; font-size: 13px; }
    #event-log { height: 200px; background: #1e293b; border-top: 1px solid #334155; display: flex; flex-direction: column; flex-shrink: 0; }
    #log-header { padding: 6px 14px; background: #0f172a; font-size: 11px; color: #64748b; border-bottom: 1px solid #334155; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; }
    #log-entries { overflow-y: auto; flex: 1; padding: 4px 0; }
    .log-entry { padding: 2px 14px; font-size: 11px; }
    .log-recv { color: #34d399; }
    .log-send-start { color: #60a5fa; }
    .log-send-ok { color: #93c5fd; }
    .log-send-fail, .log-error { color: #f87171; }
    .log-conn { color: #a78bfa; }
    .log-mcp { color: #fbbf24; }
    .log-time { color: #475569; margin-right: 6px; }
    #clear-btn { background: none; border: none; color: #64748b; cursor: pointer; font-size: 11px; font-family: inherit; }
    #clear-btn:hover { color: #94a3b8; }
  </style>
</head>
<body>
  <header>
    <h1>$title</h1>
    <p>$subtitle</p>
    <p id="schema-label">Loading schema...</p>
  </header>
  <div class="main-container">
    <div id="schema-list">
      <h3>Messages</h3>
      <div id="message-items">Loading...</div>
    </div>
    <div id="form-panel">
      <div id="placeholder">&#8592; Select a message to inspect</div>
      <div id="send-form" style="display:none">
        <h2 id="form-name"></h2>
        <div class="msg-detail-path" id="form-path"></div>
        <div class="msg-detail-desc" id="form-desc"></div>
        <div id="form-fields"></div>
        <div class="target-row" id="target-row">
          <div class="field-group">
            <div class="field-label">Target Host</div>
            <input type="text" id="target-host" class="field-input">
          </div>
          <div class="field-group">
            <div class="field-label">Target Port</div>
            <input type="number" id="target-port" class="field-input">
          </div>
        </div>
        <button id="send-btn" onclick="sendMessage()">Send</button>
        <div id="send-result"></div>
      </div>
    </div>
  </div>
  <div id="event-log">
    <div id="log-header">
      <span>Event Log</span>
      <button id="clear-btn" onclick="clearLog()">Clear</button>
    </div>
    <div id="log-entries"></div>
  </div>
  <script>
    var schema = {messages: [$messagesJs]};
    var selectedMessage = null;
    var uiConfig = {
      mode: ${toJson(config.mode.name.lowercase())},
      allowSend: ${toJson(sendingEnabled())},
      defaultTargetHost: ${toJson(config.defaultTargetHost)},
      defaultTargetPort: ${toJson(config.defaultTargetPort)},
      initialMessageRef: ${toJson(config.initialMessageRef)},
      initialArgs: ${toJson(config.initialArgs)}
    };

    (function init() {
      document.getElementById('schema-label').textContent = schema.messages.length + ' messages';
      document.getElementById('target-host').value = uiConfig.defaultTargetHost;
      document.getElementById('target-port').value = uiConfig.defaultTargetPort;
      renderMessageList(schema.messages);
      applyInitialSelection();
    })();

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
      var message = schema.messages.find(function(msg) {
        return msg.name === initialItem.dataset.messageName || msg.path === initialItem.dataset.messagePath;
      });
      if (message) {
        selectMessage(message, initialItem);
      }
    }

    function selectMessage(msg, element) {
      document.querySelectorAll('.msg-item').forEach(function(e) { e.classList.remove('active'); });
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
        if (arg.inputType === 'number') { input.step = 'any'; }
        var initialValue = formatInitialValue(arg.name);
        if (initialValue !== null) { input.value = initialValue; }
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
        var val = input ? input.value : '';
        args[arg.name] = parseArgValue(val, arg.type, arg.kind);
      });

      var host = document.getElementById('target-host').value;
      var port = parseInt(document.getElementById('target-port').value, 10);

      fetch('/api/send', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({messageRef: selectedMessage.name, host: host, port: port, args: args})
      }).then(function(r) { return r.json(); }).then(function(data) {
        var result = document.getElementById('send-result');
        result.style.display = 'block';
        if (data.success) {
          result.className = 'result-ok';
          result.textContent = '\u2713 Sent successfully';
        } else {
          result.className = 'result-err';
          result.textContent = '\u2717 ' + (data.error || 'Send failed');
        }
      }).catch(function() {
        var result = document.getElementById('send-result');
        result.style.display = 'block';
        result.className = 'result-err';
        result.textContent = '\u2717 Network error';
      });
    }

    function parseArgValue(val, type, kind) {
      if (kind === 'array') {
        try { return JSON.parse(val); } catch(e) { return val; }
      }
      if (type === 'int') return parseInt(val, 10);
      if (type === 'float') return parseFloat(val);
      if (type === 'bool') return val === 'true' || val === '1' || val === 'yes';
      return val;
    }

    function clearLog() {
      document.getElementById('log-entries').innerHTML = '';
    }

    function addLog(cssClass, text) {
      var el = document.createElement('div');
      el.className = 'log-entry ' + cssClass;
      var d = new Date();
      var time = d.toTimeString().substring(0, 8);
      el.innerHTML = '<span class="log-time">' + time + '</span>' + esc(text);
      var container = document.getElementById('log-entries');
      container.insertBefore(el, container.firstChild);
    }

    function esc(s) {
      return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    var evtSource = new EventSource('/api/events');
    evtSource.onmessage = function(e) {
      var event;
      try { event = JSON.parse(e.data); } catch(ex) { return; }
      switch (event.type) {
        case 'connected':
          addLog('log-conn', 'Connected to event stream'); break;
        case 'received':
          addLog('log-recv', 'recv ' + event.path + ' ' + JSON.stringify(event.args)); break;
        case 'send_started':
          addLog('log-send-start', '\u2192 sending ' + event.messageRef + ' to ' + event.targetHost + ':' + event.targetPort); break;
        case 'send_succeeded':
          addLog('log-send-ok', '\u2713 sent ' + event.messageRef + ' to ' + event.targetHost + ':' + event.targetPort); break;
        case 'send_failed':
          addLog('log-send-fail', '\u2717 failed ' + event.messageRef + ': ' + event.error); break;
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
  </script>
</body>
</html>
""".trimIndent()
  }
}

private class SseClient(private val writer: PrintWriter) {
  private val queue = LinkedBlockingQueue<String>(1024)

  fun send(data: String): Boolean {
    return queue.offer(data)
  }

  fun writeLoop() {
    while (true) {
      val data = queue.poll(5, TimeUnit.SECONDS)
      if (data != null) {
        writer.print(data)
        writer.flush()
        if (writer.checkError()) break
      } else {
        writer.print(":\n\n")
        writer.flush()
        if (writer.checkError()) break
      }
    }
  }
}
