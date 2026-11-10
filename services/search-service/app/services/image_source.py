from dataclasses import dataclass
from ipaddress import ip_address
from urllib.parse import quote, urlsplit

from app.config import Settings

from .errors import ImagePipelineError


@dataclass(frozen=True, slots=True)
class CatalogImageSource:
    provider: str
    download_url: str


class ImageSourceResolver:
    def __init__(self, settings: Settings):
        self.settings = settings

    def resolve(self, image_url: str, public_id: str | None = None) -> CatalogImageSource:
        parsed = self._validated_url(image_url)
        if parsed.hostname == "res.cloudinary.com":
            return CatalogImageSource("cloudinary", self._cloudinary_url(image_url, public_id))
        if parsed.hostname == "cdn.shopify.com":
            return CatalogImageSource("shopify", image_url)
        raise ImagePipelineError("Catalog image host is not supported")

    def validate_redirect(self, url: str) -> None:
        self._validated_url(url)

    def _validated_url(self, url: str):
        parsed = urlsplit(url)
        host = (parsed.hostname or "").lower()
        if parsed.scheme != "https" or not host or parsed.username or parsed.password or parsed.port not in (None, 443):
            raise ImagePipelineError("Catalog image URL must be a plain HTTPS URL")
        if host not in self.settings.allowed_hosts:
            raise ImagePipelineError("Catalog image host is not allowlisted")
        try:
            if ip_address(host).is_private:
                raise ImagePipelineError("Private IP image sources are forbidden")
        except ValueError:
            pass
        if host == "res.cloudinary.com":
            cloud = self.settings.cloudinary_cloud_name.strip()
            if not cloud or not parsed.path.startswith(f"/{cloud}/"):
                raise ImagePipelineError("Cloudinary URL is outside the configured cloud")
        elif host == "cdn.shopify.com" and not parsed.path.startswith("/s/files/"):
            raise ImagePipelineError("Shopify URL is outside /s/files/")
        return parsed

    def _cloudinary_url(self, fallback_url: str, public_id: str | None) -> str:
        if not public_id:
            return fallback_url
        public_id = public_id.strip().lstrip("/")
        if not public_id or ".." in public_id.split("/"):
            raise ImagePipelineError("Invalid Cloudinary public_id")
        encoded_id = quote(public_id, safe="/")
        cloud = quote(self.settings.cloudinary_cloud_name, safe="")
        return (
            f"https://res.cloudinary.com/{cloud}/image/upload/"
            f"c_limit,w_1024,h_1024,q_auto:good,f_jpg/{encoded_id}"
        )
