// 跟後端 POST /api/chat 的契約。形狀照抄 ChatController 的三個巢狀 record，
// 後端那邊改了欄位，這裡要跟著改 —— 這正是前後端放同一個 repo 的理由：同一個 commit 一起改。

export interface ChatRequest {
  question: string
  // 不帶就開新對話
  conversationId?: string
}

export interface ChatResponse {
  conversationId: string
  reply: string
}

interface Failure {
  error: string
}

/**
 * 問一題。非 2xx 一律丟 Error，訊息優先用後端 Failure.error（給人看的原因），
 * 拿不到（例如 proxy 連不上 app，回的不是 JSON）就退回 HTTP 狀態碼。
 */
export async function ask(request: ChatRequest): Promise<ChatResponse> {
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!res.ok) {
    const failure = (await res.json().catch(() => null)) as Failure | null
    throw new Error(failure?.error ?? `HTTP ${res.status}`)
  }
  return (await res.json()) as ChatResponse
}
