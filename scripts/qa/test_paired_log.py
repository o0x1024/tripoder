import unittest
from paired_log import structural_log


class StructuralLogTests(unittest.TestCase):
    def test_guest_network_values_never_retained(self):
        for value in ('signValue： private-value', 'body={"account":"private-value"}', 'token=private-value'):
            self.assertNotIn('private-value', structural_log(' 1792829358.256 25892 25954 E network_gateway: ' + value))
        self.assertNotIn('private-value', structural_log(' 1792829358.256 25892 25954 I TripoderFixture: Authorization: private-value'))
        self.assertNotIn('private-value', structural_log(' 1792829358.256 25892 25954 I TripoderFixture: {"access_token":"private-value"}'))
        redacted = structural_log(' 1792829358.256 25892 25954 I GuestNetwork: password:private-value')
        self.assertEqual(redacted, structural_log(redacted))

    def test_runtime_contract_and_failure_structure_retained(self):
        for message in ('I TripoderNativeLibraryContract: {"passed":true,"checks":7}',
                        'E AndroidRuntime: java.lang.IllegalStateException: PROCESS_STOP_UNCONFIRMED',
                        'F libc    : Fatal signal 11 (SIGSEGV)',
                        'F DEBUG   :       #00 pc 000000001 /owned/lib.so (owned+4)'):
            line = ' 1792829358.256 25892 25954 ' + message
            self.assertEqual(line, structural_log(line))


if __name__ == '__main__':
    unittest.main()
