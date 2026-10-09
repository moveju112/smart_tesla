#!/usr/bin/env python3
"""네이버 앱 없이 로컬 에뮬레이터의 가상 화면 경로를 확인할 대체 APK만 만든다."""
import pathlib
import subprocess
import zipfile
import os
import argparse

# 테스트 소스는 보관하고 빌드 산출물은 Git에서 제외된 경로에 둔다.
root = pathlib.Path(__file__).resolve().parents[2]
source = root / "tools/nav-smoke"
parser = argparse.ArgumentParser()
parser.add_argument("--tesla", action="store_true")
arguments = parser.parse_args()
if arguments.tesla:
    source = source / "tesla"
output = pathlib.Path(os.environ.get("NAV_SMOKE_OUTPUT", str(root / "local-replay" / ("tesla-share-smoke" if arguments.tesla else "nav-smoke"))))
sdk = pathlib.Path(os.environ.get("ANDROID_HOME", "/home/ubuntu/Android/Sdk"))
tools = sdk / "build-tools/35.0.0"
android = sdk / "platforms/android-35/android.jar"
(output / "classes").mkdir(parents=True, exist_ok=True)
(output / "dex").mkdir(exist_ok=True)


# 외부 셸 확장 없이 SDK 도구를 실행하고 실패는 호출자에게 그대로 전달한다.
def run(arguments):
    subprocess.run([str(value) for value in arguments], check=True)


run(["javac", "-source", "8", "-target", "8", "-classpath", android, "-d", output / "classes", *sorted(source.glob("*.java"))])
class_directory = output / "classes" / ("com/tesla/share" if arguments.tesla else "com/nhn/android/nmap")
run([tools / "d8", "--lib", android, "--output", output / "dex", *sorted(class_directory.glob("*.class"))])
if (source / "res").is_dir():
    run([tools / "aapt2", "compile", "--dir", source / "res", "-o", output / "resources.zip"])
    run([tools / "aapt2", "link", "-I", android, "--manifest", source / "AndroidManifest.xml", output / "resources.zip", "-o", output / "fixture.apk"])
else:
    run([tools / "aapt2", "link", "-I", android, "--manifest", source / "AndroidManifest.xml", "-o", output / "fixture.apk"])
with zipfile.ZipFile(output / "fixture.apk", "a") as archive:
    archive.write(output / "dex/classes.dex", "classes.dex")
run([tools / "apksigner", "sign", "--ks", pathlib.Path.home() / ".android/debug.keystore", "--ks-pass", "pass:android",
     "--out", output / "fixture-signed.apk", output / "fixture.apk"])
