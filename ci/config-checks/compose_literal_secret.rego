# METADATA
# title: 密碼直接寫在 compose 裡
# description: compose 檔會進 git，寫在裡面的密碼等於公開；要從 .env（不進 git）用 ${變數} 帶進來
# custom:
#   id: TALKCAL-COMPOSE-005
#   avd_id: TALKCAL-COMPOSE-005
#   severity: CRITICAL
#   short_code: no-literal-secret
#   recommended_action: 改成 ${變數}，值寫在不進 git 的 .env
#   input:
#     selector:
#       - type: yaml
package user.compose.literal_secret

import rego.v1

deny contains res if {
	some name, svc in input.services
	some key, value in env(svc)
	regex.match(`(?i)(password|passwd|secret|token|api_key|private_key)$`, key)
	value != ""
	not contains(value, "${")
	res := result.new(sprintf("服務 %s 的 %s 是寫死的值", [name, key]), {})
}

# environment 有 map（KEY: 值）和 list（- KEY=值）兩種寫法，都轉成 map
env(svc) := {k: sprintf("%v", [v]) | some k, v in svc.environment} if is_object(svc.environment)

env(svc) := {kv[0]: concat("=", array.slice(kv, 1, count(kv))) |
	some item in svc.environment
	kv := split(item, "=")
} if is_array(svc.environment)
