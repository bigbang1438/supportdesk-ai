export const statusLabels = { open: '待处理', processing: '处理中', resolved: '已解决' }
export const orderLabels = { paid: '已付款', shipped: '已发货', delivered: '已签收', cancelled: '已取消' }
export const actionLabels = { processing: '开始处理', resolved: '标记已解决', open: '重新打开' }

export function statusActions(actor, ticket) {
  if (!actor || !ticket) return []
  if (actor.role === 'agent') return ticket.status === 'open' ? ['processing'] : ticket.status === 'processing' ? ['resolved'] : []
  return actor.role === 'customer' && ticket.status === 'resolved' ? ['open'] : []
}

export function validateTicket(draft, orders) {
  const order = orders.find(item => item.id === Number(draft.orderId))
  if (!order) return '请选择自己的订单。'
  if (order.status === 'cancelled') return '已取消的订单不能提交售后工单。'
  if (!draft.subject?.trim() || draft.subject.length > 100) return '请填写 1 至 100 字的工单主题。'
  if (!draft.body?.trim() || draft.body.length > 2000) return '请填写 1 至 2000 字的问题描述。'
  return ''
}

export function approvalIssue(run, ticket, now = Date.now(), busy = false) {
  if (busy) return '正在处理中，请稍候。'
  if (!run || run.state !== 'pending') return '该回复不是待确认草稿。'
  if (!run.reply?.trim()) return '草稿内容为空。'
  if (!ticket || run.ticketVersion !== ticket.version) return '工单已更新，请重新检索并生成回复。'
  if (ticket.status === 'resolved') return '工单已解决，请由客户重新打开后再回复。'
  const expiry = Date.parse(run.expiresAt)
  if (!Number.isFinite(expiry)) return '草稿有效期不可用，请重新生成。'
  if (now >= expiry) return '草稿已过期，请重新生成。'
  return ''
}

export function createResponseScope() {
  let epoch = 0
  let sequence = 0
  const channels = new Map()
  return {
    begin(channel, key = null) {
      const token = { epoch, sequence: ++sequence, channel, key }
      channels.set(channel, token)
      return token
    },
    accepts(token) { return token.epoch === epoch && channels.get(token.channel) === token },
    invalidate() { epoch++; channels.clear() }
  }
}

export function dateLabel(value) {
  if (!value) return ''
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(parsed)
}

export function moneyLabel(cents) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(cents / 100) }
