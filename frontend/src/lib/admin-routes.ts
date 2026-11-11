export const adminRoutes = {
  root: '/admin',
  audit: '/admin/audit',
  accounts: '/admin/accounts',
  provision: '/admin/accounts/new',
} as const

export const adminRoutePaths = Object.values(adminRoutes)
