"""Frame traversal regressions that also run with the Python standard library."""

import unittest

import captcha


class Owner:
    def __init__(self, *, portion=(0, 0, 1, 1), box=None):
        self.portion = portion
        self.box = box or {"x": 0, "y": 0, "width": 400, "height": 300}

    async def evaluate(self, _script):
        return self.portion

    async def bounding_box(self):
        return self.box

    async def dispose(self):
        pass


class Frame:
    def __init__(self, url="https://sso.revolut.com/", *, parent=None, owner=None, error=None,
                 detached=False):
        self.url = url
        self.parent_frame = parent
        self.owner = owner or Owner()
        self.error = error
        self.detached = detached

    async def frame_element(self):
        if self.error:
            raise self.error
        return self.owner

    def is_detached(self):
        return self.detached


class Page:
    def __init__(self, *frames):
        self.frames = frames


class CaptchaDetectionTest(unittest.IsolatedAsyncioTestCase):
    async def test_visible_challenge(self):
        root = Frame()
        frame = Frame("https://newassets.hcaptcha.com/challenge", parent=root)
        self.assertTrue(await captcha.visible(Page(root, frame)))

    async def test_nested_challenge(self):
        root = Frame()
        wrapper = Frame(parent=root)
        frame = Frame("https://hcaptcha.com/challenge", parent=wrapper)
        self.assertTrue(await captcha.visible(Page(root, wrapper, frame)))

    async def test_hidden_parent_blocks_nested_challenge(self):
        root = Frame()
        wrapper = Frame(parent=root, owner=Owner(portion=None))
        frame = Frame("https://hcaptcha.com/challenge", parent=wrapper)
        self.assertFalse(await captcha.visible(Page(root, wrapper, frame)))

    async def test_parked_frame_is_not_a_challenge(self):
        root = Frame()
        frame = Frame("https://hcaptcha.com/challenge", parent=root, owner=Owner(portion=None))
        self.assertFalse(await captcha.visible(Page(root, frame)))

    async def test_unrelated_urls_are_not_challenges(self):
        root = Frame()
        for url in ("https://hcaptcha.com.evil.test/challenge", "https://nothcaptcha.com/",
                    "https://example.test/?next=hcaptcha.com", "https://hcaptcha.com@evil.test/",
                    "data:text/html,hcaptcha.com", "about:blank", "https://[invalid"):
            with self.subTest(url=url):
                frame = Frame(url, parent=root, error=AssertionError("Unrelated frame inspected"))
                self.assertFalse(await captcha.visible(Page(root, frame)))

    async def test_hostname_is_case_insensitive(self):
        root = Frame()
        frame = Frame("https://NEWASSETS.HCAPTCHA.COM/challenge", parent=root)
        self.assertTrue(await captcha.visible(Page(root, frame)))

    async def test_main_frame_is_not_a_challenge_iframe(self):
        self.assertFalse(await captcha.visible(Page(Frame("https://hcaptcha.com/"))))

    async def test_no_frames(self):
        self.assertFalse(await captcha.visible(Page()))

    async def test_nested_frame_must_intersect_visible_part_of_parent(self):
        root = Frame()
        wrapper = Frame(parent=root, owner=Owner(portion=(0, 0, 0.25, 1)))
        frame = Frame("https://hcaptcha.com/challenge", parent=wrapper,
                      owner=Owner(box={"x": 200, "y": 0, "width": 100, "height": 100}))
        self.assertFalse(await captcha.visible(Page(root, wrapper, frame)))

    async def test_partially_clipped_nested_challenge(self):
        root = Frame()
        wrapper = Frame(parent=root, owner=Owner(portion=(0, 0, 0.75, 1)))
        frame = Frame("https://hcaptcha.com/challenge", parent=wrapper,
                      owner=Owner(box={"x": 200, "y": 0, "width": 400, "height": 300}))
        self.assertTrue(await captcha.visible(Page(root, wrapper, frame)))

    async def test_scaled_visible_portion(self):
        root = Frame()
        wrapper = Frame(parent=root, owner=Owner(box={"x": 100, "y": 0, "width": 400, "height": 300},
                                                portion=(0.75, 0, 1, 1)))
        frame = Frame("https://hcaptcha.com/challenge", parent=wrapper,
                      owner=Owner(box={"x": 200, "y": 0, "width": 100, "height": 100}))
        self.assertFalse(await captcha.visible(Page(root, wrapper, frame)))

    async def test_detached_frame_does_not_hide_another_challenge(self):
        root = Frame()
        detached = Frame("https://hcaptcha.com/old", parent=root, error=RuntimeError("Detached"),
                         detached=True)
        live = Frame("https://hcaptcha.com/new", parent=root)
        self.assertTrue(await captcha.visible(Page(root, detached, live)))

    async def test_unexpected_browser_failure_is_not_reported_as_no_captcha(self):
        root = Frame()
        frame = Frame("https://hcaptcha.com/challenge", parent=root, error=RuntimeError("Disconnected"))
        with self.assertRaisesRegex(RuntimeError, "Disconnected"):
            await captcha.visible(Page(root, frame))


if __name__ == "__main__":
    unittest.main()
