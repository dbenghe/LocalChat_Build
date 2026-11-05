"""Official installed Codex app-server contract against a loopback fake Responses API.
No ChatGPT inference, production inputs, credential files or persistent config edits.
"""
import http.server
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time


def main():
    candidate = Path(os.environ.get('LOCALAPPDATA', '')) / 'Programs/OpenAI/Codex/bin/codex.exe'
    exe = str(candidate) if candidate.is_file() else shutil.which('codex.exe')
    if not exe:
        print('SKIP: native Codex contract fixture (Codex executable not installed)')
        return
    requests = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            assert not self.headers.get('Authorization'), 'fake provider must never receive auth'
            requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            item = ({'id': 'ct1', 'type': 'custom_tool_call', 'status': 'completed',
                     'call_id': 'fake1', 'namespace': 'functions', 'name': 'exec',
                     'input': 'text(await tools.localchat__probe({value:"OK"}));'}
                    if len(requests) == 1 else
                    {'id': 'msg1', 'type': 'message', 'role': 'assistant', 'phase': 'final_answer',
                     'status': 'completed', 'content': [{'type': 'output_text', 'text': 'FIXTURE_OK', 'annotations': []}]})
            response = {'id': 'r' + str(len(requests)), 'object': 'response', 'status': 'completed',
                        'model': 'gpt-6.1-sol', 'output': [item],
                        'usage': {'input_tokens': 10, 'output_tokens': 2, 'total_tokens': 12}}
            events = [{'type': 'response.created', 'response': dict(response, status='in_progress', output=[])},
                      {'type': 'response.output_item.added', 'output_index': 0, 'item': item}]
            if item['type'] == 'message':
                events.append({'type': 'response.output_text.delta', 'output_index': 0, 'content_index': 0,
                               'item_id': item['id'], 'delta': 'FIXTURE_OK'})
            events += [{'type': 'response.output_item.done', 'output_index': 0, 'item': item},
                       {'type': 'response.completed', 'response': response}]
            body = ''.join('event: ' + event['type'] + '\ndata: ' + json.dumps(event) + '\n\n'
                           for event in events).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix='localchat-codex-contract-') as scratch:
        args = [exe, 'app-server', '--stdio']
        flags = ['shell_tool', 'multi_agent', 'multi_agent_v2', 'hooks', 'apps', 'plugins', 'remote_plugin',
                 'memories', 'skill_search', 'skill_mcp_dependency_install', 'tool_suggest', 'goals',
                 'sleep_tool', 'browser_use', 'computer_use', 'image_generation', 'view_image', 'daemon_auto_start']
        overrides = ['features.' + flag + '=false' for flag in flags]
        overrides += ['features.code_mode_host=true', 'agents.enabled=false', 'notify=[]',
                      'web_search="disabled"', 'project_doc_max_bytes=0', 'skills.include_instructions=false',
                      'skills.bundled.enabled=false', 'tools.update_plan.enabled=false',
                      'include_apps_instructions=false', 'include_collaboration_mode_instructions=false',
                      'model_provider="localchat_fixture"', 'model_providers.localchat_fixture.name="Fixture"',
                      'model_providers.localchat_fixture.base_url="http://127.0.0.1:' + str(server.server_port) + '/v1"',
                      'model_providers.localchat_fixture.wire_api="responses"',
                      'model_providers.localchat_fixture.requires_openai_auth=false',
                      'model_providers.localchat_fixture.supports_websockets=false']
        for override in overrides:
            args += ['-c', override]
        env = {k: v for k, v in os.environ.items()
               if (not k.upper().startswith('CODEX_') or k.upper() in {'CODEX_HOME', 'CODEX_PERMISSION_PROFILE', 'CODEX_SANDBOX_NETWORK_DISABLED', 'CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY'})
               and not k.upper().startswith('OPENAI_')}
        process = subprocess.Popen(args, cwd=scratch, env=env, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW)
        messages = queue.Queue()

        def read():
            for line in process.stdout:
                try:
                    messages.put(json.loads(line))
                except ValueError:
                    pass
            messages.put({'eof': True})

        threading.Thread(target=read, daemon=True).start()

        def send(value):
            process.stdin.write(json.dumps(value) + '\n')
            process.stdin.flush()

        def rpc(number, method, params):
            send({'id': number, 'method': method, 'params': params})
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                message = messages.get(timeout=30)
                assert not message.get('eof'), 'app-server disconnected'
                if message.get('id') == number and not message.get('method'):
                    assert 'error' not in message, (method, message.get('error'))
                    return message['result']
            raise AssertionError('RPC timeout: ' + method)

        try:
            rpc(1, 'initialize', {'clientInfo': {'name': 'localchat', 'title': 'LocalChat contract test', 'version': 'test'},
                                 'capabilities': {'experimentalApi': True}})
            send({'method': 'initialized'})
            config = rpc(2, 'config/read', {'includeLayers': False})
            servers = config.get('config', {}).get('mcp_servers', {})
            thread = rpc(3, 'thread/start', {
                'model': 'gpt-6.1-sol', 'allowProviderModelFallback': False, 'cwd': scratch,
                'sandbox': 'read-only', 'approvalPolicy': 'never', 'ephemeral': True,
                'environments': [], 'selectedCapabilityRoots': [],
                'config': {'mcp_servers': {name: {'enabled': False} for name in servers}},
                'baseInstructions': 'Synthetic protocol verification; use only the supplied probe.',
                'dynamicTools': [{'type': 'namespace', 'name': 'localchat', 'description': 'Synthetic tools',
                                  'tools': [{'type': 'function', 'name': 'probe', 'description': 'Harmless probe',
                                             'inputSchema': {'type': 'object', 'properties': {'value': {'type': 'string'}},
                                                             'required': ['value'], 'additionalProperties': False}}]}]})
            assert thread['modelProvider'] == 'localchat_fixture'
            tid = thread['thread']['id']
            rpc(4, 'turn/start', {'threadId': tid, 'input': [{'type': 'text', 'text': 'Synthetic request', 'text_elements': []}]})
            calls, text, status = 0, '', None
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                event = messages.get(timeout=30)
                assert not event.get('eof'), 'app-server EOF'
                method, params = event.get('method'), event.get('params', {})
                if method == 'item/tool/call':
                    assert params['threadId'] == tid and params['tool'] == 'probe'
                    assert params['namespace'] == 'localchat' and params['arguments'] == {'value': 'OK'}
                    calls += 1
                    send({'id': event['id'], 'result': {'contentItems': [{'type': 'inputText', 'text': 'OK'}], 'success': True}})
                elif event.get('id') is not None and method:
                    raise AssertionError('Unexpected server request: ' + method)
                if method == 'item/agentMessage/delta':
                    text += params['delta']
                if method == 'turn/completed':
                    status = params['turn']['status']
                    assert not params['turn'].get('error'), params['turn'].get('error')
                    break
            assert status == 'completed' and text == 'FIXTURE_OK' and calls == 1
            assert len(requests) == 2
            namespaces = [tool.get('name') for item in requests[0].get('input', [])
                          if item.get('type') == 'additional_tools' for tool in item.get('tools', [])]
            assert 'collaboration' not in namespaces, 'native agents exposed'
            print('PASS: installed Codex contract fixture; dynamic tool bridge, namespace, tool-host, MCP isolation; no real inference')
        finally:
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    main()
