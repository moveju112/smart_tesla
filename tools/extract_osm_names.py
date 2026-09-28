# OSM 원본에서 이름 있는 지점(노드·길 중심)을 뽑아 이름 → 좌표 목록을 만든다.
import json, sys, osmium
names = {}
class Handler(osmium.SimpleHandler):
    def add(self, tags, lat, lon, kind):
        for key in ("name", "name:ko"):
            value = tags.get(key)
            if value:
                names.setdefault(value.replace(" ", ""), []).append([round(lat, 5), round(lon, 5), kind])
    def node(self, n):
        if "name" in n.tags and n.location.valid():
            kind = n.tags.get("place") or n.tags.get("amenity") or n.tags.get("highway") or n.tags.get("railway") or "node"
            self.add(n.tags, n.location.lat, n.location.lon, kind)
    def way(self, w):
        if "name" in w.tags and ("building" in w.tags or "amenity" in w.tags or "bridge" in w.tags or "leisure" in w.tags or "landuse" in w.tags or "tourism" in w.tags):
            try:
                pts = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
            except Exception:
                return
            if pts:
                self.add(w.tags, sum(p[0] for p in pts)/len(pts), sum(p[1] for p in pts)/len(pts), w.tags.get("amenity") or w.tags.get("building") or "way")
Handler().apply_file(sys.argv[1], locations=True)
json.dump(names, open(sys.argv[2], "w"), ensure_ascii=False)
print(len(names))
