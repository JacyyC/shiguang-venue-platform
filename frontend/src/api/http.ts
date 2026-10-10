import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

export type HttpRequestConfig = AxiosRequestConfig & {
  skipGlobalErrorToast?: boolean
  /** 403 时跳过全局 /403 跳转：仅供「降级可容忍」调用（组件自带 catch 降级）使用，缺省行为不变。 */
  skipGlobalErrorRedirect?: boolean
}

export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  pageNum: number
  pageSize: number
}

const rawHttp = axios.create({
  baseURL: '/api',
  timeout: 15000
})

rawHttp.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

const unwrapResponse = (response: AxiosResponse<ApiResult<unknown>>) => {
    const payload = response.data
    if (payload && payload.code !== 0) {
      // L7: 不再在此处弹 toast，由组件 catch 块自行决定是否提示，避免重复弹窗
      return Promise.reject(new Error(payload.message || '请求失败'))
    }
    return payload.data
}

rawHttp.interceptors.response.use(
  unwrapResponse as never,
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status
    if (status === 401) {
      const auth = useAuthStore()
      const currentRoute = router.currentRoute.value
      auth.logoutLocal()
      const redirect = currentRoute.meta.public || currentRoute.path === '/login' ? undefined : currentRoute.fullPath
      router.replace({ path: '/login', query: redirect ? { redirect } : undefined })
    } else if (status === 403 && !(error.config as HttpRequestConfig | undefined)?.skipGlobalErrorRedirect) {
      router.replace('/403')
    } else if (status === 409 && !(error.config as HttpRequestConfig | undefined)?.skipGlobalErrorToast) {
      // L7: 409 冲突仍由拦截器统一提示 warning，其余错误交由组件 catch 块处理
      const conflictMessage = error.response?.data?.message || error.message || '数据冲突，请刷新后重试'
      ElMessage.warning(conflictMessage)
    }
    // L7: 其余 HTTP 错误不再弹 toast，由组件 catch 块决定是否提示
    return Promise.reject(error)
  }
)

interface HttpClient {
  get<T = unknown>(url: string, config?: HttpRequestConfig): Promise<T>
  post<T = unknown>(url: string, data?: unknown, config?: HttpRequestConfig): Promise<T>
  put<T = unknown>(url: string, data?: unknown, config?: HttpRequestConfig): Promise<T>
  patch<T = unknown>(url: string, data?: unknown, config?: HttpRequestConfig): Promise<T>
  delete<T = unknown>(url: string, config?: HttpRequestConfig): Promise<T>
}

const http = rawHttp as unknown as HttpClient
export default http
