let count = 0

export function lockScroll() {
  count += 1
  document.documentElement.classList.add('is-locked')
}

export function unlockScroll() {
  count = Math.max(0, count - 1)
  if (!count) document.documentElement.classList.remove('is-locked')
}
