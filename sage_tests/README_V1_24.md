# Sage 1.24 double-test gates

The release workflow deliberately checks the update in two independent layers:

1. It reconstructs and patches Sage 1.23, runs the inherited regression tests, and runs the new continuity-report test.
2. It reconstructs the source again in a clean directory, applies every patch again, reruns the 1.24 test, and compares the generated source trees.

After compilation, unit tests, lint, and APK assembly, the workflow repeats APK ZIP, alignment, certificate, package, and version verification a second time before uploading the artifact.
