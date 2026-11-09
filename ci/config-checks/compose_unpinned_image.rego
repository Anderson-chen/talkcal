# METADATA
# title: 映像檔沒有釘版本
# description: 沒寫 tag 或寫 latest，每次 pull 都可能拿到不同的東西，壞了也不知道從哪一版開始
# custom:
#   id: TALKCAL-COMPOSE-004
#   avd_id: TALKCAL-COMPOSE-004
#   severity: HIGH
#   short_code: pin-image-version
#   recommended_action: 寫確切的版本（grafana/loki:3.3.0），第三方的最好再加 @sha256 digest
#   input:
#     selector:
#       - type: yaml
package user.compose.unpinned_image

import rego.v1

deny contains res if {
	some name, svc in input.services
	image := svc.image
	not contains(image, "@")
	unpinned(image)
	res := result.new(sprintf("服務 %s 的映像檔 %s 沒有釘版本", [name, image]), {})
}

# 最後一個 / 後面才是「名稱:tag」（前面可能是 registry:port）
unpinned(image) if not contains(last_segment(image), ":")

unpinned(image) if endswith(last_segment(image), ":latest")

last_segment(image) := parts[count(parts) - 1] if parts := split(image, "/")
