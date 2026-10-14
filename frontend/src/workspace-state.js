export function resetWorkspaceCounts({ summary, ticketMeta, ordersMeta }) {
  Object.assign(summary, { total: 0, open: 0, processing: 0, resolved: 0 })
  Object.assign(ticketMeta, { page: 0, size: 20, total: 0, totalPages: 0 })
  Object.assign(ordersMeta, { page: 0, size: 20, total: 0, totalPages: 0 })
}
