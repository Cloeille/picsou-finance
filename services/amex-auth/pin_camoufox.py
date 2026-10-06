import platform

from camoufox.pkgman import CamoufoxFetcher, Version
from camoufox.locale import download_mmdb
from camoufox.addons import maybe_download_addons, DefaultAddons

class Pinned(CamoufoxFetcher):
    @staticmethod
    def get_platform_arch():
        machine = platform.machine().lower()
        architectures = {
            'x86_64': 'x86_64',
            'amd64': 'x86_64',
            'aarch64': 'arm64',
            'arm64': 'arm64',
        }
        try:
            return architectures[machine]
        except KeyError:
            raise RuntimeError(f'Unsupported architecture: {machine}') from None

    def fetch_latest(self):
        self._version_obj = Version(release='beta.30', version='152.0.4')
        self._url = (
            'https://github.com/daijro/camoufox/releases/download/'
            f'v152.0.4-beta.30/camoufox-152.0.4-beta.30-lin.{self.arch}.zip'
        )


if __name__ == '__main__':
    Pinned().install()
    download_mmdb()
    maybe_download_addons(list(DefaultAddons))