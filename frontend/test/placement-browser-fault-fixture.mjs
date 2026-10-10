import http from 'node:http'

const port = Number(process.env.PLACEMENT_FAULT_PROXY_PORT || 18080)
const backendTarget = process.env.PLACEMENT_BACKEND_TARGET || 'http://127.0.0.1:8080'
const badImagePath = '/s09-placement-bad-image.webp'

const writeJson = (response, status, body) => {
  const payload = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload)
  })
  response.end(payload)
}

const readBody = async (request) => {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks)
}

const forward = async (request, response) => {
  try {
    const body = request.method === 'GET' || request.method === 'HEAD' ? undefined : await readBody(request)
    const headers = new Headers(request.headers)
    headers.set('host', new URL(backendTarget).host)
    const upstream = await fetch(`${backendTarget}${request.url}`, {
      method: request.method,
      headers,
      body,
      duplex: body ? 'half' : undefined
    })
    const responseBody = Buffer.from(await upstream.arrayBuffer())
    response.writeHead(upstream.status, {
      'content-type': upstream.headers.get('content-type') || 'application/octet-stream',
      'content-length': responseBody.length
    })
    response.end(responseBody)
  } catch {
    writeJson(response, 502, { code: 50000, message: '测试代理无法连接后端', data: null })
  }
}

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/api/placements/HOME_PRIMARY/decision') {
    writeJson(response, 200, {
      code: 0,
      message: 'success',
      data: {
        decisionId: 990901,
        candidateVersionId: 990902,
        token: 's09-browser-fault-fixture-token',
        reused: false,
        title: 'S09 图片故障注入',
        summary: '图片请求返回 503 时仍保留宣传文本和首页入口。',
        imageUrl: `http://127.0.0.1:${port}${badImagePath}`,
        deepLink: '/me/messages',
        expiresAt: '2099-01-01T00:00:00'
      }
    })
    return
  }

  if (request.method === 'POST' && request.url?.startsWith('/api/placements/decisions/')) {
    request.resume()
    writeJson(response, 200, {
      code: 0,
      message: 'success',
      data: { resultStatus: 'ACCEPTED', eventType: 'CLIENT_VIEWABLE' }
    })
    return
  }

  if (request.method === 'GET' && request.url === badImagePath) {
    response.writeHead(503, { 'content-type': 'image/webp' })
    response.end('s09 image failure')
    return
  }

  if (request.url?.startsWith('/api/')) {
    void forward(request, response)
    return
  }

  response.writeHead(404)
  response.end()
})

server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`S09 browser fault fixture listening on http://127.0.0.1:${port}\n`)
})

const shutdown = () => server.close(() => process.exit(0))
process.once('SIGINT', shutdown)
process.once('SIGTERM', shutdown)
