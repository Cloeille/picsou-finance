from camoufox.pkgman import CamoufoxFetcher, Version
from camoufox.locale import MMDB_FILE, geoip_allowed, webdl
from camoufox.addons import maybe_download_addons, DefaultAddons

# camoufox.locale.download_mmdb() finds this asset through the unauthenticated GitHub
# releases API, whose per-IP quota shared CI runners exhaust (403 rate limit exceeded).
# The release's direct asset URL is a plain download with no API call.
MMDB_URL = (
    'https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/'
    'GeoLite2-City.mmdb'
)


class Pinned(CamoufoxFetcher):
    def fetch_latest(self):
        self._version_obj = Version(release='beta.30', version='152.0.4')
        self._url = 'https://github.com/daijro/camoufox/releases/download/v152.0.4-beta.30/camoufox-152.0.4-beta.30-lin.x86_64.zip'


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
