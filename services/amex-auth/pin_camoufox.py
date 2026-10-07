import platform

from camoufox.pkgman import CamoufoxFetcher, Version
from camoufox.locale import MMDB_FILE, geoip_allowed, webdl
from camoufox.addons import maybe_download_addons, DefaultAddons

MMDB_URL = (
    'https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/'
    'GeoLite2-City.mmdb'
)


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


def download_mmdb():
    geoip_allowed()

    with open(MMDB_FILE, 'wb') as database:
        webdl(
            MMDB_URL,
            desc='Downloading GeoIP database',
            buffer=database,
        )


def install():
    Pinned().install()
    download_mmdb()
    maybe_download_addons(list(DefaultAddons))


if __name__ == '__main__':
    install()