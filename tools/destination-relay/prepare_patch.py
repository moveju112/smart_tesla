"""검토한 서버 원본과 일치할 때만 로컬 배포 패치를 만든다. 서버에는 접속하지 않는다."""
import difflib
from pathlib import Path


# 고정 원본 조각을 검증해 다른 서버 버전에 잘못된 패치를 만들지 않는다.
def replace(source, old, new, count=1):
    if source.count(old) != count:
        raise ValueError("검토한 서버 원본과 달라요: " + old[:60])
    return source.replace(old, new)


# 읽기 전용으로 가져온 원본을 로컬에서 변환하고 리뷰 가능한 diff를 출력한다.
def prepare(root):
    sources = root / "app/build/destination-relay-source"
    outputs = sources / "patched"
    outputs.mkdir(exist_ok=True)
    original = (sources / "map_server.py").read_text()
    changed = replace(original, "from device_auth import DeviceAuth", "from device_auth import DeviceAuth\nfrom destination_relay import DestinationRelay, RelayError")
    changed = replace(changed, "        self.rates = {}", '        self.destination_relay = (DestinationRelay(os.environ["MAP_DESTINATION_DB"])\n                                  if os.environ.get("MAP_DESTINATION_DB") else None)\n        self.rates = {}')
    changed = replace(changed, '("/v1/match", "/v1/devices", "/v1/session")', '("/v1/match", "/v1/devices", "/v1/session", "/v1/destinations")', 2)
    changed = replace(changed, '        if target == "/v1/devices":', '''        if target == "/v1/destinations":
            # 기기 서명 인증과 기존 사용자별 가입 토큰을 구분해 수신함 접근을 격리한다.
            if not authorization.startswith("Bearer rm1.") or self.device_auth is None:
                raise APIError(401, "authentication_required")
            try:
                owner, device = self.device_auth.verify_access(authorization[7:])
            except ValueError:
                raise APIError(401, "authentication_required") from None
            if self.destination_relay is None:
                raise APIError(503, "destination_unavailable")
            try:
                return self.destination_relay.dispatch(owner, device, body)
            except RelayError as error:
                raise APIError(error.status, error.code) from None
        if target == "/v1/devices":''')
    versions = [("map_server.py", original, changed)]
    original = (sources / "deploy-nginx-gps-map.conf").read_text()
    versions.append(("deploy/nginx-gps-map.conf", original, replace(original, "(match|devices|session)", "(match|devices|session|destinations)")))
    original = (sources / "deploy-gps-map.service").read_text()
    versions.append(("deploy/gps-map.service", original, replace(original, "Environment=MAP_PORT=8091", "Environment=MAP_PORT=8091\nStateDirectory=gps-map-destinations\nStateDirectoryMode=0700\nEnvironment=MAP_DESTINATION_DB=/var/lib/gps-map-destinations/relay.sqlite3")))
    patch = ""
    for path, before, after in versions:
        patch += "".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile="a/" + path, tofile="b/" + path))
        (outputs / Path(path).name).write_text(after)
    # 빈 문맥 줄의 공백만 제거해 패치 파일 자체의 공백 검사를 통과시킨다.
    patch = "\n".join("" if line == " " else line for line in patch.split("\n"))
    (root / "tools/destination-relay/server.patch").write_text(patch)
    return outputs


if __name__ == "__main__":
    prepare(Path(__file__).resolve().parents[2])
