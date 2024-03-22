#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from pyspark.sql.tests.streaming.test_streaming import StreamingTestsMixin
from pyspark.testing.connectutils import ReusedConnectTestCase


class StreamingParityTests(StreamingTestsMixin, ReusedConnectTestCase):
    def _assert_exception_tree_contains_msg(self, exception, msg):
        self.assertTrue(
            msg in exception._message,
            "Exception tree doesn't contain the expected message: %s" % msg,
        )


if __name__ == "__main__":
    import unittest
    from pyspark.sql.tests.connect.streaming.test_parity_streaming import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
