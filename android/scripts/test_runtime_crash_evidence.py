import copy
import unittest
from runtime_crash_evidence import java_fatal_evidence


class CrashEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.log = "\n".join("10-15 03:48:02.291  7742  8226 E AndroidRuntime: " + message for message in [
            "FATAL EXCEPTION: owned-uncaught-handler",
            "Process: dev.tripoder.runtimeprobe:slot3, PID: 7742",
            "java.lang.RuntimeException: owned-failure-dispatch-contract",
            "\tat dev.tripoder.provideridentity.FailureDispatchChecks.inspect(FailureDispatchChecks.java:9)",
            "\tat dev.tripoder.provideridentity.PackageResourcesActivity.inspect(PackageResourcesActivity.java:73)",
            "\tat java.lang.Thread.run(Thread.java:1572)",
        ])
        self.result = {"passed": True, "hostUid": 10398, "runs": [{"passed": True, "completed": True, "pid": 7742, "uid": 10398,
            "checks": [{"name": "uncaught-observation-preserves-app-handler-and-throwable", "passed": True}]}]}

    def test_expected_keeps_raw_observation(self):
        result = java_fatal_evidence(self.log, self.result, "p2")
        self.assertTrue(result["javaFatalObserved"])
        self.assertFalse(result["unexpectedJavaFatalObserved"])
        self.assertEqual(len(result["expectedFixtureUncaught"]), 1)

    def test_provider_fixture_phases_keep_exact_signature_and_receipt(self):
        for phase in ["providers", "provider-identity"]:
            self.assertFalse(java_fatal_evidence(self.log, self.result, phase)["unexpectedJavaFatalObserved"])
            self.assertTrue(java_fatal_evidence(self.log.replace("owned-uncaught-handler", "main"), self.result, phase)["unexpectedJavaFatalObserved"])
            self.assertTrue(java_fatal_evidence(self.log, dict(self.result, passed=False), phase)["unexpectedJavaFatalObserved"])

    def test_missing_or_failed_receipt_never_exempts(self):
        for result in [None, {}, dict(self.result, passed=False)]:
            self.assertTrue(java_fatal_evidence(self.log, result, "p2")["unexpectedJavaFatalObserved"])
        result = copy.deepcopy(self.result)
        result["runs"][0]["checks"][0]["passed"] = False
        self.assertTrue(java_fatal_evidence(self.log, result, "p2")["unexpectedJavaFatalObserved"])

    def test_wrong_phase_identity_or_signature_never_exempts(self):
        self.assertTrue(java_fatal_evidence(self.log, self.result, "p4")["unexpectedJavaFatalObserved"])
        for before, after in [("7742", "7743"), ("owned-uncaught-handler", "main"),
                              ("owned-failure-dispatch-contract", "other"),
                              ("FailureDispatchChecks.inspect", "OtherActivity.inspect"),
                              ("java.lang.Thread.run", "java.lang.IllegalStateException"),
                              ("runtimeprobe:slot3", "engine:slot3")]:
            with self.subTest(before=before):
                self.assertTrue(java_fatal_evidence(self.log.replace(before, after), self.result, "p2")["unexpectedJavaFatalObserved"])

    def test_second_event_never_exempts(self):
        self.assertTrue(java_fatal_evidence(self.log + "\n" + self.log, self.result, "p2")["unexpectedJavaFatalObserved"])

    def test_unrecognized_fatal_format_never_disappears(self):
        self.assertTrue(java_fatal_evidence("FATAL EXCEPTION: unknown", self.result, "p2")["unexpectedJavaFatalObserved"])

    def test_clean_log(self):
        result = java_fatal_evidence("", self.result, "p2")
        self.assertFalse(result["javaFatalObserved"])
        self.assertFalse(result["unexpectedJavaFatalObserved"])


if __name__ == "__main__":
    unittest.main()
