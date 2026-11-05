import json
import sys
import unittest
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).parents[1]))
from mock_datadog import MockDatadog


class MockTest(unittest.TestCase):
    def test_preview_routes_enforce_zero_requests_or_reads_only(self):
        mock = MockDatadog()
        try:
            for method, path in (
                ("POST", "/scenarios/incidents.Create-dry/api/v2/incidents"),
                ("PUT", "/scenarios/dashboards.Import-plan/api/v1/dashboard/abc-def-ghi"),
                ("POST", "/scenarios/monitors.Apply-plan/api/v1/monitor/validate"),
            ):
                with self.subTest(path=path):
                    status, _ = mock.answer(method, path, {})
                    self.assertEqual(400, status)
            status, _ = mock.answer("GET", "/scenarios/monitors.Apply-plan/api/v1/monitor", None)
            self.assertEqual(200, status)
        finally:
            mock.server.server_close()

    def test_unknown_paths_fail_and_credentials_are_not_recorded(self):
        mock = MockDatadog().start()
        try:
            request = Request(mock.url + "/unknown", headers={"DD-API-KEY": "fixture-key-value"})
            with self.assertRaises(HTTPError) as failure:
                urlopen(request)
            self.assertEqual(404, failure.exception.code)
            self.assertTrue(mock.requests[0]["apiKeyPresent"])
            self.assertNotIn("fixture-key-value", json.dumps(mock.requests))
        finally:
            mock.stop()

    def test_downtime_creation_and_cancellation_share_state(self):
        mock = MockDatadog().start()
        try:
            body = json.dumps({"data": {"attributes": {"scope": "env:e2e", "monitor_identifier": {"monitor_id": 12345}}}}).encode()
            with urlopen(Request(mock.url + "/api/v2/downtime", data=body, headers={"Content-Type": "application/json"})) as response:
                value = json.load(response)
            self.assertEqual("active", value["data"]["attributes"]["status"])
            self.assertIn(value["data"]["id"], mock.downtimes)
            with urlopen(Request(mock.url + "/api/v2/downtime/" + value["data"]["id"], method="DELETE")) as response:
                self.assertEqual(204, response.status)
            self.assertFalse(mock.downtimes)
        finally:
            mock.stop()


if __name__ == "__main__":
    unittest.main()
