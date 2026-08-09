#!/usr/bin/env python3
import pathlib,sys,unittest
BUILD=pathlib.Path(sys.argv[1]);sys.argv=sys.argv[:1]
class BrainRouteCompatibility(unittest.TestCase):
 def read(self,p):return (BUILD/p).read_text()
 def test_request_identity_and_atomic_terminal(self):
  manager=self.read("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
  policy=self.read("app/src/main/java/com/pineapple/sage/SageBrainRequestPolicy.java")
  self.assertIn("request.requestId",manager);self.assertIn("terminal==Terminal.ACTIVE",policy)
  self.assertIn("timeoutCurrentRequest",manager)
 def test_compact_prompt_and_ready_success(self):
  manager=self.read("app/src/main/java/com/pineapple/sage/SageBrainManager.java")
  self.assertIn("SageBrainRequestPolicy.build",manager)
  self.assertIn('state=State.READY; status="Sage Brain ready"; lastError=""',manager)
 def test_repair_filename(self):
  repair=self.read("app/src/main/java/com/pineapple/sage/SageRepairManager.java")
  self.assertIn('"Sage-" + installedVersion + "-repair-bundle.zip"',repair)
 def test_identity(self):
  gradle=self.read("app/build.gradle.kts")
  for value in ('applicationId = "com.pineapple.sagecommander.stable"',"versionCode = 41",'versionName = "1.29.0"',"sagePermanentSigning"):self.assertIn(value,gradle)
if __name__=="__main__":unittest.main(verbosity=2)
