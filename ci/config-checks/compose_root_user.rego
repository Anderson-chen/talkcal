# METADATA
# title: 容器明寫用 root 跑
# description: 映像檔本來用一般使用者，compose 又改回 root；容器被打穿時拿到的就是 root
# custom:
#   id: TALKCAL-COMPOSE-003
#   avd_id: TALKCAL-COMPOSE-003
#   severity: HIGH
#   short_code: no-root-user
#   recommended_action: 拿掉 user，或改成一般使用者的 uid
#   input:
#     selector:
#       - type: yaml
package user.compose.root_user

import rego.v1

deny contains res if {
	some name, svc in input.services
	split(sprintf("%v", [svc.user]), ":")[0] in {"root", "0"}
	res := result.new(sprintf("服務 %s 的 user 是 %v", [name, svc.user]), {})
}
