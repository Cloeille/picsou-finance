/**
 * A stub for the global `Image` that reports a load outcome on a microtask, so a test can drive
 * Radix `Avatar`'s success and failure paths deterministically.
 *
 * Radix does not read the rendered `<img>`: it probes with a synthetic `new Image()` and derives
 * the status from `complete`/`naturalWidth` in its `load` handler. So the element stays invisible
 * to a plain `render()` assertion, and the stub has to answer as the constructor does.
 *
 * Two details are load-bearing, both of them learned by getting it wrong:
 * the `load` listeners are called with an event-shaped argument (`{ currentTarget: this }`),
 * because Radix dereferences `event.currentTarget`; and the outcome is decided by the `src`
 * containing `broken`, since a test cannot make a real network request fail on cue.
 */
export class StubImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  complete = false
  naturalWidth = 0
  private listeners = new Map<string, Set<(event: { currentTarget: StubImage }) => void>>()
  private _src = ''

  addEventListener(type: string, listener: (event: { currentTarget: StubImage }) => void) {
    const listeners = this.listeners.get(type) ?? new Set()
    listeners.add(listener)
    this.listeners.set(type, listeners)
  }

  removeEventListener(type: string, listener: (event: { currentTarget: StubImage }) => void) {
    this.listeners.get(type)?.delete(listener)
  }

  set src(value: string) {
    this._src = value
    this.complete = false
    this.naturalWidth = 0
    queueMicrotask(() => {
      this.complete = true
      if (value.includes('broken')) {
        this.naturalWidth = 0
        this.onerror?.()
        this.listeners.get('error')?.forEach(listener => listener({ currentTarget: this }))
      } else {
        this.naturalWidth = 1
        this.onload?.()
        this.listeners.get('load')?.forEach(listener => listener({ currentTarget: this }))
      }
    })
  }

  get src() {
    return this._src
  }
}
