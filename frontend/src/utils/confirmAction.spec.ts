import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import { confirmAction } from './confirmAction'

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: vi.fn() }
}))

describe('confirmAction', () => {
  beforeEach(() => vi.clearAllMocks())

  it('treats dialog cancellation as a normal false result', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce(new Error('cancel'))
    await expect(confirmAction('确认吗', '确认')).resolves.toBe(false)
  })

  it('returns true only after explicit confirmation', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as never)
    await expect(confirmAction('确认吗', '确认')).resolves.toBe(true)
  })
})
