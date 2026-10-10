import { ElMessageBox } from 'element-plus'

export const confirmAction = async (message: string, title: string) => {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}
