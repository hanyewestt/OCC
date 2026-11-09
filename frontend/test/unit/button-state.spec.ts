import { expect, test } from '@playwright/test'
import { renderToString } from 'react-dom/server'
import { createElement } from 'react'
import { PagedCollectionControls } from '../../src/components/PagedCollectionControls'

test('PagedCollectionControls disables button when isLoading is true during reload', () => {
  const html = renderToString(
    createElement(PagedCollectionControls, {
      label: 'items',
      hasItems: false,
      hasMore: false,
      isLoading: true,
      isLoadingMore: false,
      error: new Error('fail'),
      reload: () => {},
      loadNext: () => {},
    })
  )
  expect(html).toContain('disabled=""')
  expect(html).toContain('Retrying items')
})

test('PagedCollectionControls enables button when idle after error', () => {
  const html = renderToString(
    createElement(PagedCollectionControls, {
      label: 'items',
      hasItems: false,
      hasMore: false,
      isLoading: false,
      isLoadingMore: false,
      error: new Error('fail'),
      reload: () => {},
      loadNext: () => {},
    })
  )
  expect(html).not.toContain('disabled=""')
  expect(html).toContain('Retry items')
})

test('PagedCollectionControls disables button during refresh error reload', () => {
  const html = renderToString(
    createElement(PagedCollectionControls, {
      label: 'items',
      hasItems: true,
      hasMore: true,
      isLoading: true,
      isLoadingMore: false,
      error: new Error('refresh fail'),
      refreshError: true,
      reload: () => {},
      loadNext: () => {},
    })
  )
  expect(html).toContain('disabled=""')
  expect(html).toContain('Retrying items')
})

test('PagedCollectionControls disables loadNext when isLoadingMore is true', () => {
  const html = renderToString(
    createElement(PagedCollectionControls, {
      label: 'items',
      hasItems: true,
      hasMore: true,
      isLoading: false,
      isLoadingMore: true,
      error: null,
      reload: () => {},
      loadNext: () => {},
    })
  )
  expect(html).toContain('disabled=""')
  expect(html).toContain('Loading more items')
})

test('PagedCollectionControls disables loadNext when collection is refreshing (isLoading is true)', () => {
  const html = renderToString(
    createElement(PagedCollectionControls, {
      label: 'items',
      hasItems: true,
      hasMore: true,
      isLoading: true,
      isLoadingMore: false,
      error: null,
      reload: () => {},
      loadNext: () => {},
    })
  )
  expect(html).toContain('disabled=""')
})
