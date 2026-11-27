import { expect, test, type Page } from '@playwright/test'

const colleges = [
  { code: 'PETROLEUM_ENGINEERING', name: '石油工程学院' },
  { code: 'EARTH_SCIENCES_ENGINEERING', name: '地球科学与工程学院' },
  { code: 'CHEMICAL_ENGINEERING', name: '化学工程学院' },
  { code: 'SMART_MANUFACTURING_CONTROL', name: '智能制造与控制工程学院' },
  { code: 'ENERGY_ELECTRICAL_ENGINEERING', name: '能源与电气工程学院' },
  { code: 'INFORMATION_ENGINEERING', name: '信息科学与工程学院' },
  { code: 'ECONOMICS_MANAGEMENT', name: '经济管理学院' },
  { code: 'LITERATURE', name: '文学院' },
  { code: 'BIOMEDICINE_NURSING', name: '生物医药与护理学院' },
  { code: 'EDUCATION_ARTS', name: '教育与艺术学院' },
]
const schoolAdmin = {
  id: '1',
  username: 'admin',
  name: '校级管理员',
  role: 'ADMIN',
  collegeCode: null,
  collegeName: null,
  major: null,
  teacherId: null,
  teacherName: null,
  active: true,
  mustChangePassword: false,
}
type LoginInput = { username: string; password: string; collegeCode: string | null }
const browserEvidence = new WeakMap<
  Page,
  {
    runtimeErrors: string[]
    consoleErrors: string[]
    unexpected: string[]
    errorResponses: { path: string; status: number }[]
  }
>()

async function installLogin(
  page: Page,
  options: { signedIn?: boolean; slowDirectory?: boolean; expectedCollege?: string } = {},
) {
  let signedIn = options.signedIn ?? false
  let directoryState: 'ready' | 'error' | 'empty' = 'ready'
  let directoryRequests = 0
  let loginPending = false
  let releaseDirectory = () => {}
  let releaseLogin = () => {}
  const directoryGate = options.slowDirectory
    ? new Promise<void>((resolve) => {
        releaseDirectory = resolve
      })
    : Promise.resolve()
  const requests: LoginInput[] = []
  const unexpected: string[] = []
  const runtimeErrors: string[] = []
  const consoleErrors: string[] = []
  const errorResponses: { path: string; status: number }[] = []
  browserEvidence.set(page, { runtimeErrors, consoleErrors, unexpected, errorResponses })
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/') && response.status() >= 400)
      errorResponses.push({ path, status: response.status() })
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    const method = route.request().method()
    let status = 200
    let data: unknown = null
    let error: { code: string; message: string } | null = null
    if (path === '/api/auth/me') {
      if (signedIn) data = schoolAdmin
      else {
        status = 401
        error = { code: 'UNAUTHENTICATED', message: '请先登录' }
      }
    } else if (path === '/api/auth/colleges' && method === 'GET') {
      directoryRequests += 1
      await directoryGate
      if (directoryState === 'error') {
        status = 503
        error = { code: 'UNAVAILABLE', message: '学院列表暂时无法加载' }
      } else data = directoryState === 'empty' ? [] : colleges
    } else if (path === '/api/auth/csrf') {
      data = { token: 'login-college-fixture', headerName: 'X-CSRF-TOKEN' }
    } else if (path === '/api/auth/login' && method === 'POST') {
      const input = route.request().postDataJSON() as LoginInput
      requests.push(input)
      if (loginPending)
        await new Promise<void>((resolve) => {
          releaseLogin = resolve
        })
      const expectedCollege =
        input.username === 'admin' ? null : (options.expectedCollege ?? 'EDUCATION_ARTS')
      if (input.collegeCode !== expectedCollege) {
        status = 400
        error = {
          code: input.collegeCode === null ? 'COLLEGE_REQUIRED' : 'LOGIN_COLLEGE_MISMATCH',
          message:
            input.collegeCode === null
              ? '请选择账号所属学院'
              : '学院选择与账号不匹配，请核对；校级或未分配学院账号请留空。',
        }
      } else {
        signedIn = true
        data = { ...schoolAdmin, username: input.username, collegeCode: expectedCollege }
      }
    } else if (path === '/api/admin/dashboard') {
      data = {
        activeStudents: 8,
        activeTeachers: 2,
        totalSubmissions: 12,
        dailySubmissions: [],
        statusDistribution: [],
        studentsByCollege: [],
      }
    } else {
      unexpected.push(method + ' ' + path)
      status = 404
      error = { code: 'NOT_FOUND', message: '未建模的测试接口' }
    }
    await route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify({ data, error, requestId: 'login-college-fixture' }),
    })
  })
  return {
    requests,
    unexpected,
    runtimeErrors,
    consoleErrors,
    directoryRequests: () => directoryRequests,
    setDirectoryState: (state: typeof directoryState) => {
      directoryState = state
    },
    releaseDirectory,
    delayLogin: () => {
      loginPending = true
    },
    releaseLogin: () => releaseLogin(),
  }
}

async function fillCredentials(page: Page, username = 'college-admin') {
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill('ExistingPassword42')
}

async function chooseCollege(page: Page, name: string) {
  const college = page.getByRole('combobox', { name: '学院', exact: true })
  await college.click()
  await page.getByRole('option', { name, exact: true }).click()
  await expect(college).toHaveAttribute('aria-expanded', 'false')
}

test.afterEach(async ({ page }, testInfo) => {
  await page.screenshot({
    path: testInfo.outputPath('login-college.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await testInfo.attach('browser-evidence', {
    body: JSON.stringify(browserEvidence.get(page)),
    contentType: 'application/json',
  })
})

test('登录页从服务端加载学院，键盘选择后携带学院并阻止重复提交', async ({ page }, testInfo) => {
  const fixture = await installLogin(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/login')
  await expect(page).toHaveTitle('登录 - 论文审核平台')
  const college = page.getByRole('combobox', { name: '学院', exact: true })
  await expect(college).toBeEnabled()
  await college.focus()
  await page.keyboard.press('ArrowDown')
  const targetIndex = colleges.findIndex((item) => item.code === 'EDUCATION_ARTS')
  for (let index = 0; index < targetIndex; index += 1) await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(college).toHaveAttribute('aria-expanded', 'false')
  await fillCredentials(page)
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
  await page.screenshot({
    path: testInfo.outputPath('selected-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
  fixture.delayLogin()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect.poll(() => fixture.requests.length).toBe(1)
  await expect(page.getByRole('button', { name: '登录', exact: true })).toHaveAttribute(
    'aria-busy',
    'true',
  )
  await expect(page.getByLabel('账号', { exact: true })).toBeDisabled()
  await expect(college).toBeDisabled()
  expect(fixture.requests).toEqual([
    {
      username: 'college-admin',
      password: 'ExistingPassword42',
      collegeCode: 'EDUCATION_ARTS',
    },
  ])
  fixture.releaseLogin()
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  await expect(page.getByText('启用学生', { exact: true })).toBeVisible()
  expect(fixture.requests).toHaveLength(1)
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('学院错误在表单中显示并保留输入，改选学院可恢复登录', async ({ page }, testInfo) => {
  const fixture = await installLogin(page)
  await page.goto('/login')
  await chooseCollege(page, '信息科学与工程学院')
  await fillCredentials(page)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '学院选择与账号不匹配' })).toBeVisible()
  await expect(page.getByLabel('账号', { exact: true })).toHaveValue('college-admin')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('ExistingPassword42')
  await page.screenshot({ path: testInfo.outputPath('college-error.png'), fullPage: true })
  await chooseCollege(page, '教育与艺术学院')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  expect(fixture.requests.map((input) => input.collegeCode)).toEqual([
    'INFORMATION_ENGINEERING',
    'EDUCATION_ARTS',
  ])
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('校级管理员可留空学院登录，不创建虚构学院选项', async ({ page }) => {
  const fixture = await installLogin(page)
  await page.goto('/login')
  await expect(
    page.getByText('校级管理员及未分配学院的账号可不选。', { exact: true }),
  ).toBeVisible()
  const college = page.getByRole('combobox', { name: '学院', exact: true })
  await college.click()
  await expect(page.getByRole('option')).toHaveCount(10)
  await page.keyboard.press('Escape')
  await chooseCollege(page, '教育与艺术学院')
  await page.getByRole('button', { name: '清空学院', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: '清空学院', exact: true })).toHaveCount(0)
  await fillCredentials(page, 'admin')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  expect(fixture.requests[0]).toEqual({
    username: 'admin',
    password: 'ExistingPassword42',
    collegeCode: null,
  })
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('学院列表失败和空目录均可重试，不伪造选项或丢失账号密码', async ({ page }) => {
  const fixture = await installLogin(page)
  fixture.setDirectoryState('error')
  await page.goto('/login')
  await fillCredentials(page)
  await expect(page.getByRole('alert').filter({ hasText: '学院列表暂时无法加载' })).toBeVisible()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeDisabled()
  fixture.setDirectoryState('empty')
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '暂无可用学院' })).toBeVisible()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeDisabled()
  fixture.setDirectoryState('ready')
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '学院', exact: true })).toBeEnabled()
  await expect(page.getByLabel('账号', { exact: true })).toHaveValue('college-admin')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('ExistingPassword42')
  await chooseCollege(page, '教育与艺术学院')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('学院列表加载时有提示且不能提交，加载后可继续', async ({ page }) => {
  const fixture = await installLogin(page, { slowDirectory: true })
  await page.goto('/login')
  await fillCredentials(page)
  await expect(page.getByText('正在加载学院…', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeDisabled()
  fixture.releaseDirectory()
  await expect(page.getByRole('combobox', { name: '学院', exact: true })).toBeEnabled()
  expect(fixture.requests).toEqual([])
})

test('已分配学院账号缺少选择时展示服务端提示，补选后登录', async ({ page }) => {
  const fixture = await installLogin(page)
  await page.goto('/login')
  await fillCredentials(page)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '请选择账号所属学院' })).toBeVisible()
  await expect(page.getByLabel('账号', { exact: true })).toHaveValue('college-admin')
  await chooseCollege(page, '教育与艺术学院')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toHaveAttribute(
    'aria-busy',
    'false',
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  expect(fixture.requests.map((input) => input.collegeCode)).toEqual([null, 'EDUCATION_ARTS'])
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('已有成功会话直接进入原首页，不请求匿名学院目录', async ({ page }) => {
  const fixture = await installLogin(page, { signedIn: true })
  await page.goto('/login')
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  await expect(page.getByText('启用学生', { exact: true })).toBeVisible()
  expect(fixture.directoryRequests()).toBe(0)
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

test('375px可从十学院目录选择新增长名称学院并携带正确代码登录', async ({ page }, testInfo) => {
  const fixture = await installLogin(page, { expectedCollege: 'SMART_MANUFACTURING_CONTROL' })
  await page.setViewportSize({ width: 375, height: 900 })
  await page.goto('/login')
  const college = page.getByRole('combobox', { name: '学院', exact: true })
  await college.click()
  await expect(page.getByRole('option')).toHaveCount(10)
  await page.getByRole('option', { name: '智能制造与控制工程学院', exact: true }).click()
  await expect(college).toHaveAttribute('aria-expanded', 'false')
  await fillCredentials(page, 'manufacturing-admin')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1),
  ).toBe(false)
  await page.screenshot({
    path: testInfo.outputPath('ten-colleges-long-name-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new URL('/admin', page.url()).href)
  expect(fixture.requests).toEqual([
    {
      username: 'manufacturing-admin',
      password: 'ExistingPassword42',
      collegeCode: 'SMART_MANUFACTURING_CONTROL',
    },
  ])
  expect(fixture.unexpected).toEqual([])
  expect(fixture.runtimeErrors).toEqual([])
})

for (const size of [
  { width: 320, zoom: 1 },
  { width: 375, zoom: 1 },
  { width: 1440, zoom: 2 },
]) {
  test(
    '学院登录表单在' + size.width + 'px及' + size.zoom * 100 + '%内容缩放可操作',
    async ({ page }, testInfo) => {
      const fixture = await installLogin(page)
      await page.setViewportSize({ width: size.width, height: 900 })
      await page.goto('/login')
      if (size.zoom !== 1)
        await page.evaluate((zoom) => {
          document.documentElement.style.zoom = String(zoom)
        }, size.zoom)
      await chooseCollege(page, '教育与艺术学院')
      await fillCredentials(page)
      await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > window.innerWidth + 1,
      )
      expect(overflow).toBe(false)
      await page.screenshot({
        path: testInfo.outputPath('responsive-form.png'),
        fullPage: true,
        animations: 'disabled',
      })
      const button = page.getByRole('button', { name: '登录', exact: true })
      await button.focus()
      await page.keyboard.press('Enter')
      await expect(page).toHaveURL(new URL('/admin', page.url()).href)
      expect(fixture.runtimeErrors).toEqual([])
      expect(fixture.unexpected).toEqual([])
    },
  )
}
