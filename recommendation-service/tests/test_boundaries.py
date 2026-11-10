from pathlib import Path


def test_runtime_has_no_commercial_repository_or_database_reference():
    root = Path(__file__).parents[1] / "app"
    source = "\n".join(path.read_text(encoding="utf-8") for path in root.rglob("*.py"))
    forbidden = ("modules.product.repository", "modules.order.repository", "public.products",
                 "public.orders", "public.user_interactions", "ai_search.", "chatbot.")
    assert not [value for value in forbidden if value in source]


def test_migration_has_no_cross_owner_foreign_keys():
    migration = (Path(__file__).parents[1] / "migrations" / "V1__create_recommendation_schema.sql").read_text(encoding="utf-8")
    assert "references public." not in migration.lower()
    assert "revoke all on schema public from recommendation_app" in migration.lower()
