import json
from contextlib import contextmanager
from typing import Protocol
from uuid import UUID

import psycopg

from app.domain import Rule
from app.schemas import EvaluationResult, IntegrationEvent, InteractionEvent, ProductSnapshot, RecommendationResponse


class Repository(Protocol):
    def ping(self) -> bool: ...
    def product(self, product_id: UUID) -> ProductSnapshot | None: ...
    def rules_for(self, sources: set[UUID]) -> list[Rule]: ...
    def popular(self, excluded: set[UUID], limit: int) -> list[ProductSnapshot]: ...
    def products(self, product_ids: list[UUID]) -> list[ProductSnapshot]: ...
    def save_interaction(self, event: InteractionEvent) -> bool: ...
    def apply_event(self, event: IntegrationEvent) -> bool: ...
    def baskets(self) -> list[set[UUID]]: ...
    def replace_rules(self, model_version: str, rules: list[Rule]) -> None: ...
    def record_response(self, response: RecommendationResponse) -> None: ...
    def save_evaluation(self, result: EvaluationResult) -> None: ...


class MemoryRepository:
    def __init__(self):
        self.product_by_id: dict[UUID, ProductSnapshot] = {}
        self.rule_list: list[Rule] = []
        self.popularity: dict[UUID, int] = {}
        self.order_baskets: dict[UUID, set[UUID]] = {}
        self.processed: set[UUID] = set()
        self.interactions: list[InteractionEvent] = []
        self.responses: list[RecommendationResponse] = []
        self.evaluations: list[EvaluationResult] = []

    def ping(self) -> bool: return True
    def product(self, product_id): return self.product_by_id.get(product_id)
    def rules_for(self, sources): return [rule for rule in self.rule_list if rule.antecedent in sources]
    def products(self, product_ids): return [self.product_by_id[value] for value in product_ids if value in self.product_by_id and self.product_by_id[value].status == "ACTIVE" and self.product_by_id[value].availableQuantity > 0]
    def popular(self, excluded, limit):
        values = [p for p in self.product_by_id.values() if p.id not in excluded and p.status == "ACTIVE" and p.availableQuantity > 0]
        return sorted(values, key=lambda p: (-self.popularity.get(p.id, 0), str(p.id)))[:limit]
    def save_interaction(self, event):
        if event.event_id in self.processed: return False
        self.processed.add(event.event_id); self.interactions.append(event); return True
    def apply_event(self, event):
        if event.event_id in self.processed: return False
        self.processed.add(event.event_id)
        if event.product:
            self.product_by_id[event.product.id] = event.product
        if event.order_id and event.items:
            self.order_baskets[event.order_id] = set(event.items)
            for product_id in set(event.items): self.popularity[product_id] = self.popularity.get(product_id, 0) + 1
        return True
    def baskets(self): return list(self.order_baskets.values())
    def replace_rules(self, model_version, rules): self.rule_list = list(rules)
    def record_response(self, response): self.responses.append(response)
    def save_evaluation(self, result): self.evaluations.append(result)


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
    def popular(self, excluded, limit):
        with self.connection() as connection:
            rows = connection.execute("select snapshot from catalog_products where not(product_id = any(%s)) and status='ACTIVE' and available_quantity>0 order by purchase_count desc, product_id limit %s", (list(excluded), limit)).fetchall()
        return [ProductSnapshot.model_validate(row[0]) for row in rows]
    def rules_for(self, sources):
        with self.connection() as connection:
            rows = connection.execute("select antecedent_product_id,consequent_product_id,support,confidence,lift,pair_count,model_version from association_rules where status='ACTIVE' and antecedent_product_id=any(%s) order by confidence desc,lift desc", (list(sources),)).fetchall()
        return [Rule(*row) for row in rows]
    def save_interaction(self, event):
        with self.connection() as connection:
            inserted = connection.execute("insert into processed_events(consumer_name,event_id) values('interaction-api',%s) on conflict do nothing returning event_id", (event.event_id,)).fetchone()
            if not inserted: return False
            connection.execute("insert into user_interactions(event_id,occurred_at,user_ref,anonymous_id,event_type,product_id,variant_id,request_id,source,data_source,metadata) values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", (event.event_id,event.occurred_at,event.user_ref,event.anonymous_id,event.event_type,event.product_id,event.variant_id,event.request_id,event.source,event.data_source,json.dumps(event.metadata)))
        return True
    def apply_event(self, event):
        with self.connection() as connection:
            inserted = connection.execute("insert into processed_events(consumer_name,event_id,source_version) values('recommendation-events',%s,%s) on conflict do nothing returning event_id", (event.event_id,event.source_version)).fetchone()
            if not inserted: return False
            if event.product:
                p = event.product
                connection.execute("insert into catalog_products(product_id,source_version,status,available_quantity,snapshot) values(%s,%s,%s,%s,%s) on conflict(product_id) do update set source_version=excluded.source_version,status=excluded.status,available_quantity=excluded.available_quantity,snapshot=excluded.snapshot,updated_at=now() where catalog_products.source_version<=excluded.source_version", (p.id,event.source_version,p.status,p.availableQuantity,json.dumps(p.model_dump(mode='json'))))
            if event.order_id and event.items:
                connection.execute("insert into order_baskets(order_id,user_ref,product_ids,occurred_at) values(%s,%s,%s,%s) on conflict(order_id) do nothing", (event.order_id,event.user_ref,list(set(event.items)),event.occurred_at))
                connection.execute("update catalog_products set purchase_count=purchase_count+1 where product_id=any(%s)", (list(set(event.items)),))
        return True
    def baskets(self):
        with self.connection() as connection: rows = connection.execute("select product_ids from order_baskets").fetchall()
        return [set(row[0]) for row in rows]
    def replace_rules(self, model_version, rules):
        with self.connection() as connection:
            connection.execute("update association_rules set status='ARCHIVED' where status='ACTIVE'")
            for rule in rules:
                connection.execute("insert into association_rules(antecedent_product_id,consequent_product_id,support,confidence,lift,pair_count,model_version,status) values(%s,%s,%s,%s,%s,%s,%s,'ACTIVE')", (rule.antecedent,rule.consequent,rule.support,rule.confidence,rule.lift,rule.pair_count,model_version))
            connection.execute("insert into recommendation_models(algorithm,version,status,trained_at) values('ASSOCIATION_RULE',%s,'ACTIVE',now()) on conflict(algorithm,version) do update set status='ACTIVE',trained_at=excluded.trained_at", (model_version,))
    def record_response(self, response):
        with self.connection() as connection:
            connection.execute("insert into recommendation_requests(id,model_version,context_type,source_product_ids) values(%s,%s,%s,%s)", (response.requestId,response.modelVersion,response.type,response.sourceProductIds))
            for rank, item in enumerate(response.items, 1):
                connection.execute("insert into recommendation_items(request_id,rank,product_id,score,reason_code) values(%s,%s,%s,%s,%s)", (response.requestId,rank,item.product.id,item.score,item.algorithm))
    def save_evaluation(self, result):
        with self.connection() as connection:
            connection.execute("insert into evaluation_runs(model_version,dataset_version,k,precision_at_k,recall_at_k,evaluated_at) values(%s,%s,%s,%s,%s,%s)", (result.modelVersion,result.datasetVersion,result.k,result.precisionAtK,result.recallAtK,result.evaluatedAt))
