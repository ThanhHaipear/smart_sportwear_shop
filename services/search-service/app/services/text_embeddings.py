import httpx

from app.config import Settings


class TextEmbedder:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def embed(self, text: str) -> list[float] | None:
        if self.settings.text_embedding_provider == "disabled":
            return None
        async with httpx.AsyncClient(timeout=20) as client:
            response = await client.post(
                "https://api.openai.com/v1/embeddings",
                headers={"Authorization": f"Bearer {self.settings.openai_api_key}"},
                json={"model": self.settings.text_embedding_model, "input": text,
                      "dimensions": self.settings.text_embedding_dims},
            )
        response.raise_for_status()
        return [float(value) for value in response.json()["data"][0]["embedding"]]
