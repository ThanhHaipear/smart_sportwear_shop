# Experimental services

The V2 core runtime does not depend on these services:

- `ai_forecasting_service`: demand forecasting and replenishment experiments.
- `chatbot-admin-service`: Admin Copilot and controlled operational-agent experiments.

They remain in their legacy directories temporarily to preserve Git history and avoid a large path-only change while database/API ownership is being redesigned. A later isolated PR may move them under `experiments/` after scripts and documentation have been updated.

Rules:

- Do not add them to the default Docker Compose or normal startup path.
- Do not make their database migrations part of the V2 Commerce baseline.
- Keep `NEXT_PUBLIC_ADMIN_AI_WORKSPACES_ENABLED=false` for the default Admin build.
- Changes here must not block Commerce, AI Retrieval, Assistant, Storefront or Admin CI.
- Test data created by these experiments must be marked `DEMO` or `SYNTHETIC` and excluded from primary evaluation unless explicitly selected.
