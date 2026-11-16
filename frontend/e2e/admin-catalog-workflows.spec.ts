import { expect, test, type Locator, type Page } from '@playwright/test'

function envelope(data: unknown) {
  return { data, error: null, requestId: 'admin-catalog-browser-fixture' }
}

const school = {
  schoolName: '山东石油化工学院',
  colleges: [
    { code: 'INFORMATION_ENGINEERING', name: '信息科学与工程学院' },
    { code: 'EDUCATION_ARTS', name: '教育与艺术学院' },
    { code: 'ECONOMICS_MANAGEMENT', name: '经济管理学院' },
  ],
}

const admin = {
  id: '1',
  username: 'admin',
  name: '校级管理员',
  role: 'ADMIN',
  major: null,
  teacherId: null,
  teacherName: null,
  collegeCode: null,
  collegeName: null,
  active: true,
  mustChangePassword: false,
}

async function selectOption(page: Page, combobox: Locator, label: string) {
  await combobox.click()
  const dropdown = page.locator('.ant-select-dropdown:visible')
  await expect(dropdown).toHaveCount(1)
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: label })
  await expect(option).toHaveText(label)
  await option.click()
  await expect(dropdown).toBeHidden()
}

test('管理员创建学生时切换学院清空专业与导师，并仅加载所选学院选项', async ({ page }, testInfo) => {
  const errors: string[] = []
  const unexpectedRequests: string[] = []
  const optionRequests: { resource: string; collegeCode: string | null; query: string | null }[] =
    []
  const createdBodies: unknown[] = []
  const students: unknown[] = []
  let listRequests = 0
  const student = {
    ...admin,
    id: '20',
    username: '22000002',
    name: '学生乙',
    role: 'STUDENT',
    collegeCode: 'EDUCATION_ARTS',
    collegeName: '教育与艺术学院',
    major: '学前教育',
    grade: 2023,
    teacherId: '11',
    teacherName: '教育教师',
    mustChangePassword: false,
  }
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me') data = admin
    else if (path === '/api/auth/csrf')
      data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/admin/catalog') data = school
    else if (path === '/api/admin/users' && request.method() === 'GET') {
      listRequests += 1
      data = { items: students, total: students.length, page: 1, size: 10 }
    } else if (
      request.method() === 'GET' &&
      (path === '/api/admin/majors' || path === '/api/admin/teachers')
    ) {
      const collegeCode = url.searchParams.get('collegeCode')
      optionRequests.push({
        resource: path.endsWith('/majors') ? 'majors' : 'teachers',
        collegeCode,
        query: url.searchParams.get('q'),
      })
      const information = collegeCode === 'INFORMATION_ENGINEERING'
      const options = path.endsWith('/majors')
        ? [
            {
              id: information ? '101' : '102',
              collegeCode,
              name: information ? '计算机科学与技术' : '学前教育',
              active: true,
            },
          ]
        : [
            {
              ...admin,
              id: information ? '10' : '11',
              username: information ? 'teacher_info' : 'teacher_edu',
              name: information ? '信息教师' : '教育教师',
              role: 'TEACHER',
              collegeCode,
              collegeName: information ? '信息科学与工程学院' : '教育与艺术学院',
            },
          ]
      data = path.endsWith('/majors')
        ? options
        : { items: options, total: options.length, page: 1, size: 50 }
    } else if (path === '/api/admin/users' && request.method() === 'POST') {
      createdBodies.push(request.postDataJSON())
      students.push(student)
      data = student
    } else {
      unexpectedRequests.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'admin-catalog-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })

  await page.goto('/admin/users')
  await expect(page).toHaveTitle(/账号管理/)
  await page.getByRole('button', { name: /新建学生/ }).click()
  const dialog = page.getByRole('dialog', { name: '新建学生账号' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('combobox').first()).toBeDisabled()
  await expect(dialog.getByText('山东石油化工学院', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('专业（选填）')).toBeDisabled()
  await expect(dialog.getByLabel('指导教师（选填）')).toBeDisabled()
  expect(optionRequests).toEqual([])
  await dialog.getByLabel('学号').fill('22000002')
  await dialog.getByLabel('姓名').fill(' 学生乙 ')
  await dialog.getByLabel('入学年级').fill('2023')
  await selectOption(page, dialog.getByLabel('学院', { exact: true }), '信息科学与工程学院')
  await selectOption(page, dialog.getByLabel('专业（选填）'), '计算机科学与技术')
  await selectOption(page, dialog.getByLabel('指导教师（选填）'), '信息教师 · teacher_info')
  expect(optionRequests.map((request) => request.collegeCode)).toEqual([
    'INFORMATION_ENGINEERING',
    'INFORMATION_ENGINEERING',
  ])
  const requestsBeforeChange = optionRequests.length
  await selectOption(page, dialog.getByLabel('学院', { exact: true }), '教育与艺术学院')
  await expect(dialog.getByText('选择本学院专业', { exact: true })).toBeVisible()
  await expect(dialog.getByText('输入工号或姓名搜索', { exact: true })).toBeVisible()
  await expect(dialog.getByText('计算机科学与技术', { exact: true })).toHaveCount(0)
  await expect(dialog.getByText('信息教师 · teacher_info', { exact: true })).toHaveCount(0)
  await expect.poll(() => optionRequests.length).toBe(requestsBeforeChange + 2)
  expect(optionRequests.slice(requestsBeforeChange)).toEqual(
    expect.arrayContaining([
      { resource: 'majors', collegeCode: 'EDUCATION_ARTS', query: null },
      { resource: 'teachers', collegeCode: 'EDUCATION_ARTS', query: '' },
    ]),
  )
  await selectOption(page, dialog.getByLabel('专业（选填）'), '学前教育')
  await selectOption(page, dialog.getByLabel('指导教师（选填）'), '教育教师 · teacher_edu')
  await page.screenshot({
    path: testInfo.outputPath('student-college-changed.png'),
    fullPage: true,
  })
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('row', { name: /22000002/ })).toContainText('教育教师')
  await expect(page.getByText('已创建学生乙的账号，初始密码为 123456，可直接登录。')).toBeVisible()
  await page.getByRole('button', { name: '编辑学生乙' }).click()
  const editDialog = page.getByRole('dialog', { name: '编辑学生账号' })
  await editDialog.getByLabel('入学年级').fill('')
  await editDialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(editDialog.getByText('请填写四位入学年份')).toBeVisible()
  await editDialog.getByRole('button', { name: '取消', exact: true }).click()
  expect(createdBodies).toEqual([
    {
      username: '22000002',
      name: '学生乙',
      role: 'STUDENT',
      collegeCode: 'EDUCATION_ARTS',
      major: '学前教育',
      grade: 2023,
      teacherId: '11',
    },
  ])
  expect(listRequests).toBeGreaterThanOrEqual(2)
  expect(unexpectedRequests).toEqual([])
  expect(errors).toEqual([])
})

test('管理员按学院创建专业，保存失败可重试，并确认停用后保留专业记录', async ({
  page,
}, testInfo) => {
  const errors: string[] = []
  const unexpectedRequests: string[] = []
  const requestedColleges: (string | null)[] = []
  const createdBodies: unknown[] = []
  const activeBodies: unknown[] = []
  const major = {
    id: '301',
    collegeCode: 'ECONOMICS_MANAGEMENT',
    name: '市场营销',
    active: true,
  }
  let created = false
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me') data = admin
    else if (path === '/api/auth/csrf')
      data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/admin/catalog') data = school
    else if (path === '/api/admin/majors' && request.method() === 'GET') {
      requestedColleges.push(url.searchParams.get('collegeCode'))
      data = created ? [major] : []
    } else if (path === '/api/admin/majors' && request.method() === 'POST') {
      createdBodies.push(request.postDataJSON())
      if (createdBodies.length === 1) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            data: null,
            error: { code: 'TEMPORARY_UNAVAILABLE', message: '专业保存暂时失败，请重试。' },
            requestId: 'admin-catalog-browser-fixture',
          }),
        })
        return
      }
      created = true
      data = major
    } else if (path === '/api/admin/majors/301/active' && request.method() === 'PATCH') {
      activeBodies.push(request.postDataJSON())
      major.active = false
      data = major
    } else {
      unexpectedRequests.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'admin-catalog-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })

  await page.goto('/admin/majors')
  await expect(page).toHaveTitle(/专业目录/)
  await expect(page.getByRole('heading', { name: '专业目录' })).toBeVisible()
  await expect(page.getByText('请选择学院后维护专业。')).toBeVisible()
  await expect(page.getByLabel('新增专业名称')).toBeDisabled()
  expect(requestedColleges).toEqual([])
  await selectOption(page, page.getByLabel('专业所属学院'), '经济管理学院')
  await expect(page.getByText('该学院尚未录入专业。')).toBeVisible()
  await page.getByLabel('新增专业名称').fill(' 市场营销 ')
  const addMajor = page.getByRole('button', { name: /添加专业$/ })
  await addMajor.click()
  await expect(page.getByRole('alert')).toContainText('专业保存暂时失败，请重试。')
  await expect(page.getByLabel('新增专业名称')).toHaveValue(' 市场营销 ')
  await expect(page.getByRole('row', { name: /市场营销/ })).toHaveCount(0)
  await expect(page.getByText('专业已添加。', { exact: true })).toHaveCount(0)
  expect(createdBodies).toHaveLength(1)
  await expect(addMajor).toBeEnabled()
  await addMajor.click()
  const majorRow = page.getByRole('row', { name: /市场营销/ })
  await expect(majorRow).toBeVisible()
  await expect(majorRow.getByText('启用', { exact: true })).toBeVisible()
  await expect(page.getByLabel('新增专业名称')).toHaveValue('')
  const expectedBody = { collegeCode: 'ECONOMICS_MANAGEMENT', name: '市场营销' }
  expect(createdBodies).toEqual([expectedBody, expectedBody])
  await majorRow.getByRole('button', { name: '停用', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '停用专业' })
  await expect(dialog).toContainText('已有学生的专业信息保留。')
  await dialog.getByRole('button', { name: '确认停用', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(majorRow.getByText('停用', { exact: true })).toBeVisible()
  await expect(majorRow.getByRole('button', { name: '启用', exact: true })).toBeVisible()
  await expect(page.getByText('专业已停用，已有学生资料不会删除。')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('major-deactivated.png'), fullPage: true })
  expect(activeBodies).toEqual([{ active: false }])
  expect(requestedColleges.length).toBeGreaterThanOrEqual(3)
  expect(requestedColleges.every((code) => code === 'ECONOMICS_MANAGEMENT')).toBe(true)
  expect(unexpectedRequests).toEqual([])
  expect(errors).toEqual([])
})

test('学院管理员的账号和专业操作固定在本学院，前端不提供跨院入口', async ({ page }, testInfo) => {
  const errors: string[] = []
  const unexpectedRequests: string[] = []
  const scopedRequests: { path: string; collegeCode: string | null }[] = []
  const scopedAdmin = {
    ...admin,
    id: '2',
    username: 'education_admin',
    name: '教育学院管理员',
    collegeCode: 'EDUCATION_ARTS',
    collegeName: '教育与艺术学院',
  }
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (!path.startsWith('/api/')) {
      await route.continue()
      return
    }
    let data: unknown
    if (path === '/api/auth/me') data = scopedAdmin
    else if (path === '/api/admin/catalog') data = school
    else if (path === '/api/admin/users' && request.method() === 'GET') {
      scopedRequests.push({ path, collegeCode: url.searchParams.get('collegeCode') })
      data = { items: [], total: 0, page: 1, size: 10 }
    } else if (path === '/api/admin/majors' && request.method() === 'GET') {
      scopedRequests.push({ path, collegeCode: url.searchParams.get('collegeCode') })
      data = [{ id: '102', collegeCode: 'EDUCATION_ARTS', name: '学前教育', active: true }]
    } else if (path === '/api/admin/teachers' && request.method() === 'GET') {
      scopedRequests.push({ path, collegeCode: url.searchParams.get('collegeCode') })
      const options = [
        {
          ...scopedAdmin,
          id: '11',
          username: 'teacher_edu',
          name: '教育教师',
          role: 'TEACHER',
        },
      ]
      data = { items: options, total: options.length, page: 1, size: 50 }
    } else {
      unexpectedRequests.push(request.method() + ' ' + path)
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'NOT_FOUND', message: '测试接口不存在' },
          requestId: 'admin-catalog-browser-fixture',
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    })
  })

  await page.goto('/admin/users')
  await expect(page.getByRole('tab', { name: '学院管理员', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('按学院筛选')).toHaveCount(0)
  await page.getByRole('button', { name: /新建学生/ }).click()
  const dialog = page.getByRole('dialog', { name: '新建学生账号' })
  await expect(dialog.getByLabel('学院', { exact: true })).toBeDisabled()
  await expect(dialog.getByText('教育与艺术学院', { exact: true })).toBeVisible()
  await expect(dialog.getByText('信息科学与工程学院', { exact: true })).toHaveCount(0)
  await expect(dialog.getByText('经济管理学院', { exact: true })).toHaveCount(0)
  await selectOption(page, dialog.getByLabel('专业（选填）'), '学前教育')
  await selectOption(page, dialog.getByLabel('指导教师（选填）'), '教育教师 · teacher_edu')
  await page.screenshot({ path: testInfo.outputPath('college-admin-student.png'), fullPage: true })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('menuitem', { name: /专业目录/ }).click()
  await expect(page).toHaveTitle(/专业目录/)
  await expect(page.getByLabel('专业所属学院')).toBeDisabled()
  await expect(page.getByText('教育与艺术学院', { exact: true })).toBeVisible()
  await expect(page.getByRole('row', { name: /学前教育/ })).toBeVisible()
  expect(scopedRequests.length).toBeGreaterThanOrEqual(3)
  expect(scopedRequests.every((request) => request.collegeCode === 'EDUCATION_ARTS')).toBe(true)
  expect(unexpectedRequests).toEqual([])
  expect(errors).toEqual([])
})
