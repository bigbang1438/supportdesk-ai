import test from 'node:test'
import assert from 'node:assert/strict'
import { resetWorkspaceCounts } from '../src/workspace-state.js'

test('switching from enterprise A to B removes A counters before B requests return', () => {
  const summary = { total: 12, open: 4, processing: 7, resolved: 1 }
  const ticketMeta = { page: 2, size: 20, total: 47, totalPages: 3 }
  const ordersMeta = { page: 1, size: 20, total: 25, totalPages: 2 }
  resetWorkspaceCounts({ summary, ticketMeta, ordersMeta })
  assert.deepEqual(summary, { total: 0, open: 0, processing: 0, resolved: 0 })
  assert.deepEqual(ticketMeta, { page: 0, size: 20, total: 0, totalPages: 0 })
  assert.deepEqual(ordersMeta, { page: 0, size: 20, total: 0, totalPages: 0 })
})
