# METADATA
# title: 容器拿到主機層級的權限
# description: privileged、network_mode host、pid host 讓容器裡的程式等同主機上的 root，容器被打穿就是整台機器
# custom:
#   id: TALKCAL-COMPOSE-001
#   avd_id: TALKCAL-COMPOSE-001
#   severity: CRITICAL
#   short_code: no-host-access
#   recommended_action: 拿掉；真的要某個能力時用 cap_add 只加那一個
#   input:
#     selector:
#       - type: yaml
package user.compose.host_access

import rego.v1

deny contains res if {
	some name, svc in input.services
	svc.privileged == true
	res := result.new(sprintf("服務 %s 開了 privileged", [name]), {})
}

deny contains res if {
	some name, svc in input.services
	some key in ["network_mode", "pid", "ipc"]
	svc[key] == "host"
	res := result.new(sprintf("服務 %s 的 %s 是 host", [name, key]), {})
}
