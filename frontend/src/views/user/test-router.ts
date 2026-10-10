import { createRouter, createWebHistory, type Router } from 'vue-router'

const routes = [
  { path: '/', component: { template: '<div />' } },
  { path: '/venues', component: { template: '<div />' } },
  { path: '/venues/:id', component: { template: '<div />' } },
  { path: '/reservation/confirm', component: { template: '<div />' } }
]

export async function createTestRouter(path = '/'): Promise<Router> {
  const router = createRouter({ history: createWebHistory(), routes })
  await router.push(path)
  await router.isReady()
  return router
}
