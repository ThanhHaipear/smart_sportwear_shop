package com.dunghaiquyen.ecommerce.modules.catalog.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDataset;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetItem;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetStatus;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.CatalogDatasetItemRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.CatalogDatasetRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogDatasetService {
    private final CatalogDatasetRepository datasets;
    private final CatalogDatasetItemRepository items;
    private final ProductRepository products;

    public CatalogDatasetService(
            CatalogDatasetRepository datasets,
            CatalogDatasetItemRepository items,
            ProductRepository products) {
        this.datasets = datasets;
        this.items = items;
        this.products = products;
    }

    @Transactional
    public CatalogDatasetItem addItem(
            UUID datasetId, UUID productId, String sourceRecordId, String sourceUri, String checksum) {
        CatalogDataset dataset = requireMutable(datasetId);
        CatalogDatasetItem item = new CatalogDatasetItem();
        item.setDataset(dataset);
        item.setProduct(products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found")));
        item.setSourceRecordId(sourceRecordId.trim());
        item.setSourceUri(sourceUri);
        item.setNormalizedChecksum(checksum);
        item.setImportedAt(Instant.now());
        return items.save(item);
    }

    @Transactional
    public CatalogDataset validate(UUID datasetId) {
        CatalogDataset dataset = requireStatus(datasetId, CatalogDatasetStatus.DRAFT);
        dataset.setStatus(CatalogDatasetStatus.VALIDATED);
        dataset.setImportedAt(Instant.now());
        return datasets.save(dataset);
    }

    @Transactional
    public CatalogDataset freeze(UUID datasetId) {
        CatalogDataset dataset = requireStatus(datasetId, CatalogDatasetStatus.VALIDATED);
        if (!items.existsByDatasetId(datasetId)) {
            throw new BusinessRuleException("Catalog dataset must contain at least one item before freeze");
        }
        dataset.setStatus(CatalogDatasetStatus.FROZEN);
        return datasets.save(dataset);
    }

    private CatalogDataset requireMutable(UUID id) {
        CatalogDataset dataset = datasets.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog dataset not found"));
        if (dataset.getStatus() == CatalogDatasetStatus.FROZEN) {
            throw new BusinessRuleException("Frozen catalog dataset is immutable");
        }
        return dataset;
    }

    private CatalogDataset requireStatus(UUID id, CatalogDatasetStatus status) {
        CatalogDataset dataset = requireMutable(id);
        if (dataset.getStatus() != status) {
            throw new BusinessRuleException("Invalid catalog dataset status transition");
        }
        return dataset;
    }
}
