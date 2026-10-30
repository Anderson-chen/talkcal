# METADATA
# title: 容器掛了 docker.sock
# description: docker.sock 等同主機上 Docker 的完整控制權，:ro 只擋寫檔，擋不住 API 呼叫（開一個 privileged 容器就出來了）
# custom:
#   id: TALKCAL-COMPOSE-002
#   avd_id: TALKCAL-COMPOSE-002
#   severity: HIGH
#   short_code: no-docker-socket
#   recommended_action: 改用不需要 Docker API 的做法；真的需要時寫進 ci/trivyignore.yaml 並附理由
#   input:
#     selector:
#       - type: yaml
package user.compose.docker_socket

import rego.v1

deny contains res if {
	some name, svc in input.services
	some volume in svc.volumes
	contains(volume_source(volume), "docker.sock")
	res := result.new(sprintf("服務 %s 掛了 docker.sock", [name]), {})
}

# volumes 有短寫（"來源:目的:模式" 字串）和長寫（{type, source, target}）兩種
volume_source(v) := v if is_string(v)

volume_source(v) := object.get(v, "source", "") if is_object(v)
