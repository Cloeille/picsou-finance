from camoufox.pkgman import CamoufoxFetcher, Version
from camoufox.locale import download_mmdb
from camoufox.addons import maybe_download_addons, DefaultAddons

class Pinned(CamoufoxFetcher):
    def fetch_latest(self):
        self._version_obj = Version(release='beta.30', version='152.0.4')
        self._url = 'https://github.com/daijro/camoufox/releases/download/v152.0.4-beta.30/camoufox-152.0.4-beta.30-lin.x86_64.zip'
Pinned().install()
download_mmdb()
maybe_download_addons(list(DefaultAddons))