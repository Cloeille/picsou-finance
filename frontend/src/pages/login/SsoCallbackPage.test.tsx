import '@testing-library/jest-dom'
import { act, render, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'

const { SsoCallbackPage } = await import('./SsoCallbackPage')

interface Submission {
  action: string | null
  method: string | null
  hidden: boolean
  data: FormData
  /** window.location.search at the moment the submit event fires. */
  searchAtSubmit: string
}

/** Captures every form submission (and cancels the navigation jsdom cannot perform). */
function captureSubmissions(): Submission[] {
  const submissions: Submission[] = []
  document.addEventListener('submit', (e) => {
    e.preventDefault()
    const form = e.target as HTMLFormElement
    submissions.push({
      action: form.getAttribute('action'),
      method: form.getAttribute('method'),
      hidden: form.hidden,
      data: new FormData(form),
      searchAtSubmit: window.location.search,
    })
  })
  return submissions
}

function renderCallback(url: string, opts: { strict?: boolean } = {}) {
  window.history.replaceState(null, '', url)
  const tree = (
    <BrowserRouter>
      <Routes>
        <Route path="/login/sso/callback" element={<SsoCallbackPage />} />
        <Route path="/login" element={<div>login-page</div>} />
      </Routes>
    </BrowserRouter>
  )
  return render(opts.strict ? <StrictMode>{tree}</StrictMode> : tree)
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('SsoCallbackPage', () => {
  it('posts the authorization response to the code endpoint exactly once, after clearing the URL', async () => {
    const submissions = captureSubmissions()

    renderCallback('/login/sso/callback?code=abc123&state=xyz&iss=https%3A%2F%2Fidp.example%2Fdefault')

    await waitFor(() => expect(submissions).toHaveLength(1))
    const [submission] = submissions
    expect(submission.action).toBe('/oauth2/sso/code/oidc')
    expect(submission.method).toBe('post')
    expect(submission.hidden).toBe(true)
    expect(submission.data.get('code')).toBe('abc123')
    expect(submission.data.get('state')).toBe('xyz')
    expect(submission.data.get('iss')).toBe('https://idp.example/default')
    // The authorization code must leave the address bar BEFORE the POST goes out.
    expect(submission.searchAtSubmit).toBe('')
    expect(window.location.pathname).toBe('/login/sso/callback')
  })

  it('forwards IdP error parameters instead of a code', async () => {
    const submissions = captureSubmissions()

    renderCallback('/login/sso/callback?error=access_denied&error_description=User%20cancelled&state=xyz')

    await waitFor(() => expect(submissions).toHaveLength(1))
    const [submission] = submissions
    expect(submission.data.get('error')).toBe('access_denied')
    expect(submission.data.get('error_description')).toBe('User cancelled')
    expect(submission.data.get('state')).toBe('xyz')
    expect(submission.data.get('code')).toBeNull()
  })

  it('forwards only the authorization response parameters', async () => {
    const submissions = captureSubmissions()

    renderCallback(
      '/login/sso/callback?code=abc123&state=xyz&session_state=s1&action=https%3A%2F%2Fevil.example&requestSubmit=x',
    )

    await waitFor(() => expect(submissions).toHaveLength(1))
    const [submission] = submissions
    expect(submission.action).toBe('/oauth2/sso/code/oidc')
    expect([...submission.data.keys()]).toEqual(['code', 'state'])
  })

  it('submits only once under React StrictMode', async () => {
    const submissions = captureSubmissions()

    renderCallback('/login/sso/callback?code=abc123&state=xyz', { strict: true })

    await waitFor(() => expect(submissions).toHaveLength(1))
    expect(document.body.querySelectorAll('form')).toHaveLength(1)
  })

  it('goes back to /login when Back restores the page from the back/forward cache', async () => {
    const submissions = captureSubmissions()
    const { findByText } = renderCallback('/login/sso/callback?code=abc123&state=xyz')
    await waitFor(() => expect(submissions).toHaveLength(1))

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })

    expect(await findByText('login-page')).toBeInTheDocument()
    expect(submissions).toHaveLength(1)
  })

  it('redirects a bare visit (neither code nor error) to /login', async () => {
    const submissions = captureSubmissions()

    const { findByText } = renderCallback('/login/sso/callback')

    expect(await findByText('login-page')).toBeInTheDocument()
    expect(submissions).toHaveLength(0)
  })
})
