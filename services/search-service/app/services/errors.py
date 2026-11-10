class ImagePipelineError(ValueError):
    """A permanent image/source error which must not be retried."""


class ImageDownloadError(RuntimeError):
    """A transient upstream/network error which may be retried."""
