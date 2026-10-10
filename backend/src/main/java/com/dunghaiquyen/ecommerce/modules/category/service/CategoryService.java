package com.dunghaiquyen.ecommerce.modules.category.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.config.CacheConfig;
import com.dunghaiquyen.ecommerce.modules.category.dto.CategoryCreateRequest;
import com.dunghaiquyen.ecommerce.modules.category.dto.CategoryResponse;
import com.dunghaiquyen.ecommerce.modules.category.dto.CategoryTreeResponse;
import com.dunghaiquyen.ecommerce.modules.category.dto.CategoryUpdateRequest;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.category.entity.CategoryNodeType;
import com.dunghaiquyen.ecommerce.modules.category.entity.CategoryStatus;
import com.dunghaiquyen.ecommerce.modules.category.mapper.CategoryMapper;
import com.dunghaiquyen.ecommerce.modules.category.repository.CategoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    @Cacheable(value = CacheConfig.CATEGORIES, key = "'flat'")
    @Transactional(readOnly = true)
    public List<CategoryResponse> listActive() {
        return categoryRepository.findAllByStatusOrderBySortOrderAscNameAsc(CategoryStatus.ACTIVE).stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    @Cacheable(value = CacheConfig.CATEGORIES, key = "'tree'")
    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> listActiveTree() {
        return buildTree(categoryRepository.findAllActiveWithParent(CategoryStatus.ACTIVE));
    }

    @Transactional(readOnly = true)
    public CategoryResponse getActiveDetail(String slugOrId) {
        Category category = tryFindActiveById(slugOrId)
                .or(() -> categoryRepository.findBySlugAndStatus(slugOrId, CategoryStatus.ACTIVE))
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        return categoryMapper.toResponse(category);
    }

    @CacheEvict(value = CacheConfig.CATEGORIES, allEntries = true)
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }

        CategoryNodeType nodeType = request.nodeType() != null ? request.nodeType() : CategoryNodeType.LEAF;

        Category category = new Category();
        category.setCode(toCode(request.slug()));
        category.setName(request.name().trim());
        category.setSlug(request.slug());
        category.setDescription(request.description());
        category.setStatus(request.status() != null ? request.status() : CategoryStatus.ACTIVE);
        category.setNodeType(nodeType);
        category.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        category.setParent(resolveParent(request.parentId(), null));
        validateHierarchy(category.getNodeType(), category.getParent());

        try {
            category = categoryRepository.save(category);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        return categoryMapper.toResponse(category);
    }

    @CacheEvict(value = CacheConfig.CATEGORIES, allEntries = true)
    @Transactional
    public CategoryResponse update(UUID id, CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        if (request.slug() != null && categoryRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }

        if (request.name() != null) {
            category.setName(request.name().trim());
        }
        if (request.slug() != null) {
            category.setSlug(request.slug());
        }
        if (request.description() != null) {
            category.setDescription(request.description());
        }
        if (request.status() != null) {
            category.setStatus(request.status());
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.nodeType() != null) {
            category.setNodeType(request.nodeType());
        }
        if (Boolean.TRUE.equals(request.clearParent())) {
            category.setParent(null);
        } else if (request.parentId() != null) {
            category.setParent(resolveParent(request.parentId(), id));
        }
        validateHierarchy(category.getNodeType(), category.getParent());
        if (category.getNodeType() == CategoryNodeType.LEAF && categoryRepository.existsByParentId(id)) {
            throw new BusinessRuleException("Category with children cannot become LEAF.");
        }

        try {
            category = categoryRepository.save(category);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        return categoryMapper.toResponse(category);
    }

    @CacheEvict(value = CacheConfig.CATEGORIES, allEntries = true)
    @Transactional
    public void delete(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (categoryRepository.existsByParentId(id)) {
            throw new BusinessRuleException("Khong the xoa danh muc dang co danh muc con.");
        }
        try {
            categoryRepository.delete(category);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Khong the xoa danh muc dang duoc dung boi san pham.");
        }
    }

    private Optional<Category> tryFindActiveById(String slugOrId) {
        try {
            UUID id = UUID.fromString(slugOrId);
            return categoryRepository.findByIdAndStatus(id, CategoryStatus.ACTIVE);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Category resolveParent(UUID parentId, UUID selfId) {
        if (parentId == null) {
            return null;
        }
        if (selfId != null && selfId.equals(parentId)) {
            throw new BusinessRuleException("Category cannot be its own parent.");
        }

        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent category not found"));
        if (parent.getNodeType() == CategoryNodeType.LEAF) {
            throw new BusinessRuleException("Parent category must be GROUP.");
        }
        if (createsCycle(parent, selfId)) {
            throw new BusinessRuleException("Parent category creates a cycle.");
        }
        return parent;
    }

    private boolean createsCycle(Category parent, UUID selfId) {
        if (selfId == null) {
            return false;
        }
        Category cursor = parent;
        while (cursor != null) {
            if (selfId.equals(cursor.getId())) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    /**
     * The storefront and product query intentionally use a two-level taxonomy:
     * root GROUP -> LEAF. Keeping this invariant here prevents categories that
     * exist in the API tree but cannot be rendered or included by parent filters.
     */
    private void validateHierarchy(CategoryNodeType nodeType, Category parent) {
        if (nodeType == CategoryNodeType.GROUP && parent != null) {
            throw new BusinessRuleException("GROUP category must be a root category.");
        }
        if (parent != null && parent.getParent() != null) {
            throw new BusinessRuleException("Category taxonomy supports at most two levels.");
        }
    }

    private List<CategoryTreeResponse> buildTree(List<Category> categories) {
        Map<UUID, List<Category>> childrenByParent = new LinkedHashMap<>();
        List<Category> roots = new ArrayList<>();

        for (Category category : categories) {
            if (category.getParent() == null) {
                roots.add(category);
            } else {
                childrenByParent.computeIfAbsent(category.getParent().getId(), ignored -> new ArrayList<>())
                        .add(category);
            }
        }

        return roots.stream()
                .map(root -> toTree(root, childrenByParent))
                .toList();
    }

    private String toCode(String slug) {
        return slug.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private CategoryTreeResponse toTree(Category category, Map<UUID, List<Category>> childrenByParent) {
        List<CategoryTreeResponse> children = childrenByParent.getOrDefault(category.getId(), List.of()).stream()
                .map(child -> toTree(child, childrenByParent))
                .toList();
        return categoryMapper.toTreeResponse(category, children);
    }
}
