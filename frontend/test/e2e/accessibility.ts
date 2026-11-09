import type { Page } from '@playwright/test'

/**
 * Automated WCAG-oriented checks for the visible page: text contrast against the rendered background,
 * keyboard reachability, accessible names, keyboard-scrollable regions and horizontal page scroll.
 * Returns one human-readable line per problem; an empty list means the page passed.
 */
export async function auditPage(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const problems: string[] = []
    const describe = (element: Element) => {
      const text = (element.getAttribute('aria-label') ?? element.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 50)
      const classes = (element.getAttribute('class') ?? '').split(/\s+/).filter(name => /(text|bg)-/.test(name)).join(' ')
      return `<${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ''}${classes ? ` .${classes}` : ''}> "${text}"`
    }
    const visible = (element: Element) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'
        && element.closest('.sr-only, [hidden]') === null
    }

    if (document.documentElement.scrollWidth > window.innerWidth + 1) {
      problems.push(`page scrolls sideways: ${document.documentElement.scrollWidth}px content in a ${window.innerWidth}px window`)
    }

    // Contrast: composite every background from the root down, then paint the text colour on top.
    const context2d = document.createElement('canvas').getContext('2d', { willReadFrequently: true })!
    const paint = (colours: string[]) => {
      context2d.fillStyle = '#ffffff'
      context2d.fillRect(0, 0, 1, 1)
      for (const colour of colours) { context2d.fillStyle = colour; context2d.fillRect(0, 0, 1, 1) }
      return Array.from(context2d.getImageData(0, 0, 1, 1).data.slice(0, 3))
    }
    const luminance = (rgb: number[]) => rgb.map(value => value / 255)
      .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
      .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0)
    for (const element of document.body.querySelectorAll('*')) {
      const ownText = Array.from(element.childNodes).some(node => node.nodeType === Node.TEXT_NODE && node.textContent!.trim())
      if (!ownText || !visible(element) || element.closest(':disabled, [aria-disabled="true"]')) continue
      const style = getComputedStyle(element)
      const backgrounds: string[] = []
      for (let node: Element | null = element; node; node = node.parentElement) backgrounds.unshift(getComputedStyle(node).backgroundColor)
      const [text, background] = [luminance(paint([...backgrounds, style.color])), luminance(paint(backgrounds))]
      const ratio = (Math.max(text, background) + 0.05) / (Math.min(text, background) + 0.05)
      const size = parseFloat(style.fontSize)
      const large = size >= 24 || (size >= 18.66 && Number(style.fontWeight) >= 700)
      if (ratio < (large ? 3 : 4.5)) problems.push(`low contrast ${ratio.toFixed(2)}:1 for ${describe(element)}`)
    }

    const focusable = 'a[href], button, input, select, textarea, summary, [tabindex]'
    for (const element of document.body.querySelectorAll(focusable)) {
      if (!visible(element)) continue
      const tabIndex = element.getAttribute('tabindex')
      if (tabIndex !== null && Number(tabIndex) > 0) problems.push(`positive tabindex on ${describe(element)}`)
      if (!element.matches('button, a[href], input, select, textarea')) continue
      const labelled = element.getAttribute('aria-label') || element.getAttribute('aria-labelledby') || element.getAttribute('title')
        || element.textContent?.trim() || (element.id && document.querySelector(`label[for="${element.id}"]`)) || element.closest('label')
        || element.getAttribute('placeholder') && element.matches('input')
      if (!labelled) problems.push(`no accessible name for ${describe(element)}`)
    }

    // Clickable-looking elements must be operable by keyboard.
    for (const element of document.body.querySelectorAll('*')) {
      if (!visible(element) || getComputedStyle(element).cursor !== 'pointer') continue
      if (element.closest(focusable) || element.querySelector(focusable)) continue
      if (element.parentElement && getComputedStyle(element.parentElement).cursor === 'pointer') continue
      problems.push(`pointer-only control ${describe(element)}`)
    }

    // Scrollable regions need a keyboard path: focusable themselves or containing a focusable control.
    for (const element of document.body.querySelectorAll('*')) {
      const style = getComputedStyle(element)
      const scrolls = /(auto|scroll)/.test(style.overflowY + style.overflowX)
        && (element.scrollHeight > element.clientHeight + 1 || element.scrollWidth > element.clientWidth + 1)
      if (!scrolls || !visible(element)) continue
      const reachable = element.matches(focusable) || element.querySelector(focusable) !== null
        || element.closest('[tabindex]:not([tabindex="-1"])') !== null
      if (!reachable) problems.push(`scrollable region without keyboard access ${describe(element)}`)
    }
    return problems
  })
}

/**
 * Tabs through the page and reports focused elements whose focus indicator is missing or has less than
 * 3:1 contrast against the background behind it (WCAG 1.4.11).
 */
export async function auditFocus(page: Page, maxTabs = 60): Promise<string[]> {
  await page.locator('body').click({ position: { x: 1, y: 1 } })
  const problems = new Set<string>()
  for (let index = 0; index < maxTabs; index++) {
    await page.keyboard.press('Tab')
    const problem = await page.evaluate(async () => {
      const element = document.activeElement
      if (!element || element === document.body) return null
      // Focus rings often fade in; judge the finished state, not the first frame of the transition.
      await Promise.all(element.getAnimations().map(animation => animation.finished.catch(() => undefined)))
      const style = getComputedStyle(element)
      const text = (element.getAttribute('aria-label') ?? element.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 50)
      const label = `<${element.tagName.toLowerCase()}> "${text}"`
      // The outline wins when present; otherwise the box-shadow layer with the widest spread is the ring.
      // A ring offset is a narrower background-coloured layer drawn under it, so it must not be measured.
      let indicator: string | null = null
      if (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) indicator = style.outlineColor
      else if (style.boxShadow !== 'none') {
        const layers = style.boxShadow.split(/,(?![^(]*\))/).map(layer => layer.trim())
          .map(layer => ({ layer, spread: Number(layer.match(/\s(-?\d+(?:\.\d+)?)px$/)?.[1] ?? 0) }))
          .filter(entry => entry.spread > 0).sort((first, second) => second.spread - first.spread)
        indicator = layers[0]?.layer.match(/^((?:[a-z-]+\([^)]*\))|#[0-9a-f]+|[a-z]+)/i)?.[1] ?? null
      }
      if (!indicator) return `no visible focus indicator on ${label}`
      const context2d = document.createElement('canvas').getContext('2d', { willReadFrequently: true })!
      const paint = (colours: string[]) => {
        context2d.fillStyle = '#ffffff'
        context2d.fillRect(0, 0, 1, 1)
        for (const colour of colours) { context2d.fillStyle = colour; context2d.fillRect(0, 0, 1, 1) }
        return Array.from(context2d.getImageData(0, 0, 1, 1).data.slice(0, 3))
      }
      const luminance = (rgb: number[]) => rgb.map(value => value / 255)
        .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
        .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0)
      // The ring is drawn just outside the element, over its parent's background.
      const backgrounds: string[] = []
      for (let node = element.parentElement; node; node = node.parentElement) backgrounds.unshift(getComputedStyle(node).backgroundColor)
      const [ring, background] = [luminance(paint([...backgrounds, indicator])), luminance(paint(backgrounds))]
      const ratio = (Math.max(ring, background) + 0.05) / (Math.min(ring, background) + 0.05)
      return ratio < 3 ? `focus indicator contrast ${ratio.toFixed(2)}:1 on ${label}` : null
    })
    if (problem) problems.add(problem)
  }
  return [...problems]
}
