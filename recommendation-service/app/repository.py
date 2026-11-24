import json
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Protocol
from uuid import UUID

import psycopg
from psycopg.types.json import Jsonb

from app.domain import BasketRow, ModelMetadata, Rule
from app.schemas import EvaluationResult, IntegrationEvent, InteractionEvent, ProductSnapshot, RecommendationResponse

# `order_baskets.product_ids` must contain product IDs. Sales publishes variant IDs in
# ORDER_CREATED, so callers resolve them with app.enrichment.EventEnricher before apply_event.


class Repository(Protocol):
    def ping(self) -> bool: ...
    def product(self, product_id: UUID) -> ProductSnapshot | None: ...
    def rules_for(self, sources: set[UUID]) -> list[Rule]: ...
    def popular(self, excluded: set[UUID], limit: int) -> list[ProductSnapshot]: ...
    def products(self, product_ids: list[UUID]) -> list[ProductSnapshot]: ...
    def candidates(self) -> list[ProductSnapshot]: ...
    def snapshots(self, product_ids: list[UUID]) -> list[ProductSnapshot]: ...
    def save_interaction(self, event: InteractionEvent) -> bool: ...
    def save_interactions(self, events: list[InteractionEvent]) -> int: ...
    def apply_event(self, event: IntegrationEvent) -> bool: ...
    def baskets(self) -> list[set[UUID]]: ...
    def baskets_before(self, cutoff: datetime) -> list[BasketRow]: ...
    def replace_rules(self, model_version: str, rules: list[Rule], metadata: ModelMetadata) -> None: ...
    def record_response(self, response: RecommendationResponse, latency_ms: int | None = None,
                        user_ref: str | None = None) -> None: ...
    def save_evaluation(self, result: EvaluationResult) -> None: ...
    def has_product(self, product_id: UUID) -> bool: ...
    def upsert_product(self, product: ProductSnapshot, source_version: int) -> None: ...
    def basket_rows(self) -> list[tuple[UUID, list[UUID]]]: ...
    def replace_basket(self, order_id: UUID, product_ids: list[UUID]) -> None: ...
    def recompute_purchase_counts(self) -> int: ...


class MemoryRepository:
    def __init__(self):
        self.product_by_id: dict[UUID, ProductSnapshot] = {}
        self.rule_list: list[Rule] = []
        self.popularity: dict[UUID, int] = {}
        self.versions: dict[UUID, int] = {}
        self.order_baskets: dict[UUID, set[UUID]] = {}
        self.basket_times: dict[UUID, datetime] = {}
        self.models: dict[tuple[str, str], dict] = {}
        self.request_log: list[dict] = []
        self.processed: set[UUID] = set()
        self.interactions: list[InteractionEvent] = []
        self.responses: list[RecommendationResponse] = []
        self.evaluations: list[EvaluationResult] = []

    def ping(self) -> bool: return True
    def product(self, product_id): return self.product_by_id.get(product_id)
    def rules_for(self, sources): return [rule for rule in self.rule_list if rule.antecedent in sources]
    def products(self, product_ids): return [self.product_by_id[value] for value in product_ids if value in self.product_by_id and self.product_by_id[value].status == "ACTIVE" and self.product_by_id[value].availableQuantity > 0]
    def candidates(self): return sorted(self.products(list(self.product_by_id)), key=lambda p: str(p.id))
    def snapshots(self, product_ids): return [self.product_by_id[v] for v in product_ids if v in self.product_by_id]
    def popular(self, excluded, limit):
        values = [p for p in self.product_by_id.values() if p.id not in excluded and p.status == "ACTIVE" and p.availableQuantity > 0]
        return sorted(values, key=lambda p: (-self.popularity.get(p.id, 0), str(p.id)))[:limit]
    def save_interaction(self, event):
        if event.event_id in self.processed: return False
        self.processed.add(event.event_id); self.interactions.append(event); return True
    def save_interactions(self, events): return sum(1 for event in events if self.save_interaction(event))
    def apply_event(self, event):
        if event.event_id in self.processed: return False
        self.processed.add(event.event_id)
        if event.product:
            self.upsert_product(event.product, event.source_version)
        if event.order_id and event.items and event.order_id not in self.order_baskets:
            self.order_baskets[event.order_id] = set(event.items)
            self.basket_times[event.order_id] = event.occurred_at
            for product_id in set(event.items):
                if product_id in self.product_by_id:
                    self.popularity[product_id] = self.popularity.get(product_id, 0) + 1
        return True
    def baskets(self): return list(self.order_baskets.values())
    def baskets_before(self, cutoff):
        epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
        rows = [BasketRow(order_id, tuple(sorted(items, key=str)), self.basket_times.get(order_id, epoch))
                for order_id, items in self.order_baskets.items()]
        return [row for row in rows if row.occurred_at < cutoff]
    def replace_rules(self, model_version, rules, metadata):
        self.rule_list = list(rules)
        for key, model in self.models.items():
            if key[0] == metadata.algorithm: model["status"] = "ARCHIVED"
        self.models[(metadata.algorithm, model_version)] = {"status": "ACTIVE", "metadata": metadata}
    def record_response(self, response, latency_ms=None, user_ref=None):
        self.responses.append(response)
        self.request_log.append({"requestId": response.requestId, "latencyMs": latency_ms, "userRef": user_ref})
    def save_evaluation(self, result): self.evaluations.append(result)
    def has_product(self, product_id): return product_id in self.product_by_id
    def upsert_product(self, product, source_version):
        if self.versions.get(product.id, -1) <= source_version:
            self.product_by_id[product.id] = product
            self.versions[product.id] = source_version
    def basket_rows(self): return [(order_id, list(items)) for order_id, items in self.order_baskets.items()]
    def replace_basket(self, order_id, product_ids): self.order_baskets[order_id] = set(product_ids)
    def recompute_purchase_counts(self):
        self.popularity = {}
        for items in self.order_baskets.values():
            for product_id in items:
                if product_id in self.product_by_id:
                    self.popularity[product_id] = self.popularity.get(product_id, 0) + 1
        return len(self.popularity)


class PostgresRepository:
    def __init__(self, database_url: str): self.database_url = database_url
    @contextmanager
    def connection(self):
        with psycopg.connect(self.database_url, options="-c search_path=recommendation,public") as connection:
            yield connection
    def ping(self):
        try:
            with self.connection() as connection: connection.execute("select 1")
            return True
        except psycopg.Error: return False
    def product(self, product_id):
        values = self.products([product_id]); return values[0] if values else None
    def products(self, product_ids):
        if not product_ids: return []
        with self.connection() as connection:
            rows = connection.execute("select snapshot from catalog_products where product_id = any(%s) and status='ACTIVE' and available_quantity>0", (product_ids,)).fetchall()
        return [ProductSnapshot.model_validate(row[0]) for row in rows]
    def candidates(self):
        with self.connection() as connection:
            rows = connection.execute("select snapshot from catalog_products where status='ACTIVE' and available_quantity>0 order by product_id").fetchall()
        return [ProductSnapshot.model_validate(row[0]) for row in rows]
    def snapshots(self, product_ids):
        with self.connection() as connection:
            rows = connection.execute("select snapshot from catalog_products where product_id = any(%s)", (list(product_ids),)).fetchall()
        return [ProductSnapshot.model_validate(row[0]) for row in rows]
    def popular(self, excluded, limit):
        with self.connection() as connection:
            rows = connection.execute("select snapshot from catalog_products where not(product_id = any(%s)) and status='ACTIVE' and available_quantity>0 order by purchase_count desc, product_id limit %s", (list(excluded), limit)).fetchall()
        return [ProductSnapshot.model_validate(row[0]) for row in rows]
    def rules_for(self, sources):
        with self.connection() as connection:
            rows = connection.execute("select antecedent_product_id,consequent_product_id,support,confidence,lift,pair_count,model_version from association_rules where status='ACTIVE' and antecedent_product_id=any(%s) order by confidence desc,lift desc", (list(sources),)).fetchall()
        return [Rule(*row) for row in rows]
    def save_interaction(self, event):
        return self.save_interactions([event]) == 1
    def save_interactions(self, events):
        """Store a batch in ONE connection/transaction; returns how many were new (not duplicates)."""
        accepted = 0
        with self.connection() as connection:
            for event in events:
                inserted = connection.execute("insert into processed_events(consumer_name,event_id) values('interaction-api',%s) on conflict do nothing returning event_id", (event.event_id,)).fetchone()
                if not inserted: continue
                connection.execute("insert into user_interactions(event_id,occurred_at,user_ref,anonymous_id,event_type,product_id,variant_id,request_id,source,data_source,metadata) values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", (event.event_id,event.occurred_at,event.user_ref,event.anonymous_id,event.event_type,event.product_id,event.variant_id,event.request_id,event.source,event.data_source,json.dumps(event.metadata)))
                accepted += 1
        return accepted
    def apply_event(self, event):
        with self.connection() as connection:
            inserted = connection.execute("insert into processed_events(consumer_name,event_id,source_version) values('recommendation-events',%s,%s) on conflict do nothing returning event_id", (event.event_id,event.source_version)).fetchone()
            if not inserted: return False
            if event.product:
                self._upsert_product(connection, event.product, event.source_version)
            if event.order_id and event.items:
                stored = connection.execute("insert into order_baskets(order_id,user_ref,product_ids,occurred_at) values(%s,%s,%s,%s) on conflict(order_id) do nothing returning order_id", (event.order_id,event.user_ref,list(set(event.items)),event.occurred_at)).fetchone()
                # Count each order once even if Sales republishes it under a new event ID.
                if stored:
                    connection.execute("update catalog_products set purchase_count=purchase_count+1 where product_id=any(%s)", (list(set(event.items)),))
        return True
    @staticmethod
    def _upsert_product(connection, product, source_version):
        connection.execute("insert into catalog_products(product_id,source_version,status,available_quantity,snapshot) values(%s,%s,%s,%s,%s) on conflict(product_id) do update set source_version=excluded.source_version,status=excluded.status,available_quantity=excluded.available_quantity,snapshot=excluded.snapshot,updated_at=now() where catalog_products.source_version<=excluded.source_version", (product.id,source_version,product.status,product.availableQuantity,json.dumps(product.model_dump(mode='json'))))
    def has_product(self, product_id):
        with self.connection() as connection:
            return connection.execute("select 1 from catalog_products where product_id=%s", (product_id,)).fetchone() is not None
    def upsert_product(self, product, source_version):
        with self.connection() as connection: self._upsert_product(connection, product, source_version)
    def basket_rows(self):
        with self.connection() as connection:
            return [(row[0], list(row[1])) for row in connection.execute("select order_id,product_ids from order_baskets order by occurred_at,order_id").fetchall()]
    def replace_basket(self, order_id, product_ids):
        with self.connection() as connection:
            connection.execute("update order_baskets set product_ids=%s where order_id=%s", (list(product_ids),order_id))
    def recompute_purchase_counts(self):
        """Rebuild popularity from stored baskets; used after baskets are repaired."""
        with self.connection() as connection:
            return connection.execute("""
                update catalog_products c set purchase_count=coalesce(x.orders,0)
                  from (select p.product_id, (select count(*) from order_baskets b where p.product_id=any(b.product_ids)) orders
                          from catalog_products p) x
                 where x.product_id=c.product_id and c.purchase_count is distinct from coalesce(x.orders,0)
            """).rowcount
    def baskets(self):
        with self.connection() as connection: rows = connection.execute("select product_ids from order_baskets").fetchall()
        return [set(row[0]) for row in rows]
    def baskets_before(self, cutoff):
        with self.connection() as connection:
            rows = connection.execute("select order_id,product_ids,occurred_at from order_baskets where occurred_at < %s", (cutoff,)).fetchall()
        return [BasketRow(row[0], tuple(sorted(row[1], key=str)), row[2]) for row in rows]
    def replace_rules(self, model_version, rules, metadata):
        # One transaction: archive the previous rules and model rows, then store the new ones.
        with self.connection() as connection:
            connection.execute("update association_rules set status='ARCHIVED' where status='ACTIVE'")
            connection.execute("update recommendation_models set status='ARCHIVED' where algorithm=%s and status='ACTIVE' and version<>%s", (metadata.algorithm, model_version))
            for rule in rules:
                connection.execute("insert into association_rules(antecedent_product_id,consequent_product_id,support,confidence,lift,pair_count,model_version,status) values(%s,%s,%s,%s,%s,%s,%s,'ACTIVE')", (rule.antecedent,rule.consequent,rule.support,rule.confidence,rule.lift,rule.pair_count,model_version))
            connection.execute("insert into recommendation_models(algorithm,version,config,training_cutoff_at,training_event_checksum,status,trained_at) values(%s,%s,%s,%s,%s,'ACTIVE',now()) on conflict(algorithm,version) do update set config=excluded.config,training_cutoff_at=excluded.training_cutoff_at,training_event_checksum=excluded.training_event_checksum,status='ACTIVE',trained_at=excluded.trained_at", (metadata.algorithm, model_version, Jsonb(metadata.config), metadata.training_cutoff_at, metadata.training_event_checksum))
    def record_response(self, response, latency_ms=None, user_ref=None):
        with self.connection() as connection:
            connection.execute("insert into recommendation_requests(id,model_version,user_ref,context_type,source_product_ids,latency_ms) values(%s,%s,%s,%s,%s,%s)", (response.requestId,response.modelVersion,user_ref,response.type,response.sourceProductIds,latency_ms))
            for rank, item in enumerate(response.items, 1):
                connection.execute("insert into recommendation_items(request_id,rank,product_id,score,reason_code) values(%s,%s,%s,%s,%s)", (response.requestId,rank,item.product.id,item.score,item.algorithm))
    def save_evaluation(self, result):
        with self.connection() as connection:
            # Columns after recall_at_k come from migration V3: apply V3 before deploying this version.
            connection.execute("insert into evaluation_runs(model_version,dataset_version,k,precision_at_k,recall_at_k,hit_rate_at_k,ndcg_at_k,coverage_at_k,task,segment,case_count,report_sha256,evaluated_at) values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", (result.modelVersion,result.datasetVersion,result.k,result.precisionAtK,result.recallAtK,result.hitRateAtK,result.ndcgAtK,result.coverageAtK,result.task,result.segment,result.caseCount,result.reportSha256,result.evaluatedAt))
