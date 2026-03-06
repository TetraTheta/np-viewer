#!/usr/bin/env python3
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent
APP_NAME = "NovelpiaViewer"

# --- 환경 변수 로딩 ---


def load_dotenv():
    env_file = PROJECT_ROOT / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line: str = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() not in os.environ:
            os.environ[key.strip()] = value.strip()


def require_env(key: str) -> str:
    value = os.environ.get(key)
    if not value:
        print(f"[ERROR] 환경 변수 '{key}'가 설정되어 있지 않습니다.", file=sys.stderr)
        sys.exit(1)
    return value


load_dotenv()

# --- 필수 환경 변수 수집 ---

ANDROID_HOME = Path(require_env("ANDROID_HOME"))
KEYSTORE_PATH = Path(require_env("KEYSTORE_PATH"))
KEY_ALIAS = require_env("KEY_ALIAS")
KEYSTORE_PASS = require_env("KEYSTORE_PASSWORD")
KEY_PASS = os.environ.get("KEY_PASSWORD") or KEYSTORE_PASS

# --- 버전 파싱 ---

gradle_kts = (PROJECT_ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
version_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_kts)
if not version_match:
    print(
        "[ERROR] app/build.gradle.kts에서 versionName을 찾을 수 없습니다.",
        file=sys.stderr,
    )
    sys.exit(1)
VERSION = version_match.group(1)

print(f"[INFO] {APP_NAME} v{VERSION} 빌드를 시작합니다.")

# --- 1. Release APK 빌드 ---

gradlew = PROJECT_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
print(f"\n[STEP 1] assembleRelease 실행: {gradlew}")

result = subprocess.run([str(gradlew), "assembleRelease"], cwd=PROJECT_ROOT)
if result.returncode != 0:
    print("[ERROR] Gradle 빌드에 실패했습니다.", file=sys.stderr)
    sys.exit(result.returncode)

# --- 빌드 결과물 탐색 ---

release_dir = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release"
built_apk = release_dir / "app-release-unsigned.apk"
if not built_apk.exists():
    built_apk = release_dir / "app-release.apk"
if not built_apk.exists():
    print(f"[ERROR] 빌드된 APK를 찾을 수 없습니다: {release_dir}", file=sys.stderr)
    sys.exit(1)

print(f"[INFO] 빌드된 APK: {built_apk}")

# --- 2. apksigner 탐색 및 APK 서명 ---

build_tools_dir = ANDROID_HOME / "build-tools"
if not build_tools_dir.exists():
    print(
        f"[ERROR] build-tools 디렉토리를 찾을 수 없습니다: {build_tools_dir}",
        file=sys.stderr,
    )
    sys.exit(1)

apksigner_name = "apksigner.bat" if sys.platform == "win32" else "apksigner"
apksigner = next(
    (
        v / apksigner_name
        for v in sorted(build_tools_dir.iterdir(), reverse=True)
        if (v / apksigner_name).exists()
    ),
    None,
)
if apksigner is None:
    print("[ERROR] apksigner를 build-tools에서 찾을 수 없습니다.", file=sys.stderr)
    sys.exit(1)

signed_apk = release_dir / "app-release-signed.apk"
print(f"\n[STEP 2] APK 서명: {apksigner}")

sign_env = os.environ.copy()
sign_env["JAVA_TOOL_OPTIONS"] = "--enable-native-access=ALL-UNNAMED"

result = subprocess.run(
    [
        str(apksigner),
        "sign",
        "--ks",
        str(KEYSTORE_PATH),
        "--ks-key-alias",
        KEY_ALIAS,
        "--ks-pass",
        f"pass:{KEYSTORE_PASS}",
        "--key-pass",
        f"pass:{KEY_PASS}",
        "--out",
        str(signed_apk),
        str(built_apk),
    ],
    env=sign_env,
)
if result.returncode != 0:
    print("[ERROR] APK 서명에 실패했습니다.", file=sys.stderr)
    sys.exit(result.returncode)

# --- 3. 서명된 APK 이동 ---

output_dir = PROJECT_ROOT / "apk"
output_dir.mkdir(exist_ok=True)
output_apk = output_dir / f"{APP_NAME}-{VERSION}.apk"

shutil.copy2(signed_apk, output_apk)
signed_apk.unlink()  # 중간 산출물 정리

print(f"\n[DONE] 서명된 APK: {output_apk}")
