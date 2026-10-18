import importlib.util
import io
import json
import os
import pathlib
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "vercel-backend" / "api" / "ai" / "chat.py"
SPEC = importlib.util.spec_from_file_location("misha_chat_api", MODULE_PATH)
api = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(api)


class FakeHandler(api.handler):
    def __init__(self, body=b"", headers=None, path="/api/ai/chat"):
        self.rfile = io.BytesIO(body)
        self.wfile = io.BytesIO()
        self.headers = headers or {}
        self.path = path
        self.status = None
        self.response_headers = {}

    def send_response(self, status):
        self.status = status

    def send_header(self, key, value):
        self.response_headers[key] = value

    def end_headers(self):
        pass

    def result(self):
        return self.status, json.loads(self.wfile.getvalue().decode("utf-8"))


class ProviderResponse:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, *args):
        return self.payload


class ChatApiTests(unittest.TestCase):
    def test_root_health_check_returns_200(self):
        h = FakeHandler(path="/")
        h.do_GET()
        status, result = h.result()
        self.assertEqual(status, 200)
        self.assertEqual(result["status"], "online")
        self.assertEqual(result["chat_endpoint"], "POST /api/ai/chat")

    def setUp(self):
        for key in ("ANTHROPIC_API_KEY", "ANTHROPIC_WORKSPACE_ID", "GEMINI_API_KEY", "GOOGLE_API_KEY", "GEMINI_MODEL"):
            os.environ.pop(key, None)

    def test_missing_provider_key_returns_clear_503(self):
        body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 503)
        self.assertIn("not configured", result["error"])

    def test_malformed_json_returns_400(self):
        h = FakeHandler(b"{", {"Content-Length": "1"})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 400)
        self.assertIn("valid JSON", result["error"])

    def test_invalid_message_shape_returns_422(self):
        body = json.dumps({"messages": [{"role": "system", "content": "bad"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 422)

    @patch.object(api, "urlopen")
    def test_gemini_used_when_both_provider_keys_exist(self, mock_urlopen):
        os.environ["ANTHROPIC_API_KEY"] = "anthropic-test-key"
        os.environ["GEMINI_API_KEY"] = "gemini-test-key"
        mock_urlopen.return_value = ProviderResponse({
            "candidates": [{"content": {"parts": [{"text": "Namaste!"}]}}]
        })
        body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 200)
        self.assertEqual(result["reply"], "Namaste!")
        self.assertIn("generativelanguage.googleapis.com", mock_urlopen.call_args.args[0].full_url)
        self.assertIn(api.DEFAULT_GEMINI_MODEL, mock_urlopen.call_args.args[0].full_url)

    @patch.object(api, "urlopen")
    def test_retired_gemini_model_retries_current_default(self, mock_urlopen):
        os.environ["GEMINI_API_KEY"] = "gemini-test-key"
        os.environ["GEMINI_MODEL"] = "gemini-2.5-flash"
        retired = HTTPError(
            "https://generativelanguage.googleapis.com/", 404, "not found", {},
            io.BytesIO(b'{"error":{"message":"model unavailable"}}')
        )
        mock_urlopen.side_effect = [
            retired,
            ProviderResponse({"candidates": [{"content": {"parts": [{"text": "Working reply"}]}}]}),
        ]
        body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 200)
        self.assertEqual(result["reply"], "Working reply")
        self.assertEqual(mock_urlopen.call_count, 2)
        self.assertIn("models/gemini-2.5-flash:", mock_urlopen.call_args_list[0].args[0].full_url)
        self.assertIn("models/" + api.DEFAULT_GEMINI_MODEL + ":", mock_urlopen.call_args_list[1].args[0].full_url)

    @patch.object(api, "urlopen")
    def test_provider_http_error_returns_502_without_crashing(self, mock_urlopen):
        os.environ["GEMINI_API_KEY"] = "gemini-test-key"
        mock_urlopen.side_effect = HTTPError(
            "https://generativelanguage.googleapis.com/", 400, "bad key", {},
            io.BytesIO(b'{"error":{"message":"API key invalid"}}')
        )
        body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 502)
        self.assertEqual(result["provider_status"], 400)


    @patch.object(api, "urlopen")
    def test_gemini_timeout_falls_back_to_anthropic(self, mock_urlopen):
        os.environ["GEMINI_API_KEY"] = "gemini-test-key"
        os.environ["ANTHROPIC_API_KEY"] = "anthropic-test-key"
        mock_urlopen.side_effect = [
            TimeoutError("Gemini read timed out"),
            ProviderResponse({"content": [{"type": "text", "text": "Fallback reply"}]}),
        ]
        body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 200)
        self.assertEqual(result["reply"], "Fallback reply")
        self.assertEqual(mock_urlopen.call_count, 2)
        self.assertIn("generativelanguage.googleapis.com", mock_urlopen.call_args_list[0].args[0].full_url)
        self.assertIn("api.anthropic.com", mock_urlopen.call_args_list[1].args[0].full_url)


    @patch.object(api, "urlopen")
    def test_workspace_scoped_anthropic_key_is_used_directly(self, mock_urlopen):
        os.environ["GEMINI_API_KEY"] = "gemini-test-key"
        os.environ["ANTHROPIC_API_KEY"] = "anthropic-test-key"
        os.environ["ANTHROPIC_WORKSPACE_ID"] = "workspace-test-id"
        mock_urlopen.return_value = ProviderResponse({
            "content": [{"type": "text", "text": "Anthropic reply"}]
        })
        body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
        h = FakeHandler(body, {"Content-Length": str(len(body))})
        h.do_POST()
        status, result = h.result()
        self.assertEqual(status, 200)
        self.assertEqual(result["reply"], "Anthropic reply")
        request = mock_urlopen.call_args.args[0]
        self.assertIn("api.anthropic.com", request.full_url)
        self.assertEqual(request.get_header("Anthropic-workspace-id"), "workspace-test-id")


if __name__ == "__main__":
    unittest.main()
